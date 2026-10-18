package io.github.miaiplus

import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProfileDraft(val id: String, val name: String, val endpoint: String,
                        val apiKey: String, val model: String, val api: String) {
    constructor(profile: EndpointProfiles.Profile) : this(profile.id, profile.name, profile.endpoint,
        profile.apiKey, profile.model, profile.api)
    fun toProfile() = EndpointProfiles.Profile(id, name, endpoint, apiKey, model, api)
}

data class DisplayGeometry(val width: Int, val height: Int, val density: Int)

/** UI state only. Runtime configuration and task safeguards stay in their existing owners. */
class SettingsState(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val prefs = Config.prefs(context)
    val virtualScreen = VirtualScreenState(application, viewModelScope)
    var config by mutableStateOf(Config(context))
        private set
    var profiles by mutableStateOf<EndpointProfiles?>(null)
        private set
    var draft by mutableStateOf<ProfileDraft?>(null)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var modelList by mutableStateOf<List<String>?>(null)
    var fetching by mutableStateOf(false)
        private set
    var guardEnabled by mutableStateOf(AccessibilityGuard.enabled(context))
        private set
    var guardChanging by mutableStateOf(false)
        private set
    var accessible by mutableStateOf(false)
        private set
    var hookReport by mutableStateOf("")
        private set
    var hookRecordedAt by mutableLongStateOf(0L)
        private set
    var reloadStatus by mutableStateOf("")
        private set
    var guardStatus by mutableStateOf("")
        private set
    var query by mutableStateOf("")
    var testMode by mutableIntStateOf(0)
    var job by mutableStateOf<String?>(null)
        private set
    var starting by mutableStateOf(false)
        private set
    var result by mutableStateOf("")
        private set
    // Editing an already-dirty draft must not invalidate navigation and the save bar on every key.
    val dirty: Boolean by derivedStateOf(structuralEqualityPolicy()) {
        draft != profiles?.active()?.let(::ProfileDraft)
    }
    val external: Boolean get() = config.globalModel || config.planner == "external"

    init {
        try { loadProfiles() } catch (failure: Exception) { error = failure.message }
        refresh()
    }

    private fun loadProfiles() {
        profiles = EndpointProfiles(prefs)
        draft = ProfileDraft(profiles!!.active())
        config = Config(context)
        modelList = null
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block(); error = null; true
    } catch (failure: Exception) {
        error = failure.message ?: "操作失败，请重试"; false
    }

    fun updateProfile(name: String? = null, endpoint: String? = null, key: String? = null,
                      model: String? = null, api: String? = null) {
        val current = draft ?: return
        draft = ProfileDraft(current.id, name ?: current.name,
            endpoint ?: current.endpoint, key ?: current.apiKey, model ?: current.model, api ?: current.api)
        error = null
    }

    fun discard() { draft = profiles?.active()?.let(::ProfileDraft); error = null; modelList = null }

    fun saveProfile(newName: String? = null): Boolean = attempt {
        val current = requireNotNull(draft) { "无法读取配置" }
        val selected = if (newName == null) current.toProfile() else EndpointProfiles.Profile(
            UUID.randomUUID().toString(), newName, current.endpoint, current.apiKey, current.model, current.api)
        val editor = prefs.edit()
        requireNotNull(profiles).write(editor, selected.validated(external), false)
        check(editor.commit()) { "保存失败，请重试" }
        loadProfiles(); notice = if (newName == null) "已保存" else "已新增配置"
    }

    fun switchProfile(id: String): Boolean = attempt {
        val collection = requireNotNull(profiles)
        val selected = collection.find(id).validated(external)
        val editor = prefs.edit()
        collection.write(editor, selected, false)
        check(editor.commit()) { "切换失败，请重试" }
        loadProfiles(); notice = "已切换至${selected.name}"
    }

    fun deleteProfile(): Boolean = attempt {
        val collection = requireNotNull(profiles)
        check(collection.profiles.size > 1) { "至少保留一份配置" }
        val next = collection.profiles.first { it.id != collection.activeId }.validated(external)
        val editor = prefs.edit()
        collection.write(editor, next, true)
        check(editor.commit()) { "删除失败，请重试" }
        loadProfiles(); notice = "配置已删除"
    }

    fun setOption(key: String, value: Any): Boolean = attempt {
        if ((key == "globalModel" && value == true) || (key == "planner" && value == "external")) {
            requireNotNull(profiles).active().validated(true)
        }
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> { require(value in 1..Config.MAX_PHONE_USE_STEPS) { "步数需在 1–${Config.MAX_PHONE_USE_STEPS} 之间" }; editor.putInt(key, value) }
        }
        check(editor.commit()) { "保存失败，请重试" }
        config = Config(context)
        if (key == "enabled" && value == false) BridgeProvider.cancelActive()
        notice = if (key == "globalModel" || key == "enabled") "已保存，请重启小爱" else "已保存"
    }

    fun setConversationTimeoutMinutes(minutes: Int): Boolean = attempt {
        require(minutes in 0..Config.MAX_CONVERSATION_TIMEOUT_MINUTES) {
            "时长需在 0–${Config.MAX_CONVERSATION_TIMEOUT_MINUTES} 分钟之间，0 表示关闭"
        }
        check(prefs.edit().putInt("conversationTimeoutMinutes", minutes).commit()) { "保存失败，请重试" }
        config = Config(context)
        notice = "已保存"
    }

    fun currentDisplayGeometry(): DisplayGeometry {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("无法读取本机屏幕分辨率")
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return DisplayGeometry(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    fun setDisplayGeometry(width: Int, height: Int, density: Int): Boolean = attempt {
        require(width in Config.MIN_DISPLAY_SIZE..Config.MAX_DISPLAY_SIZE
            && height in Config.MIN_DISPLAY_SIZE..Config.MAX_DISPLAY_SIZE) {
            "宽度和高度需在 ${Config.MIN_DISPLAY_SIZE}–${Config.MAX_DISPLAY_SIZE} 像素之间"
        }
        require(density in Config.MIN_DISPLAY_DENSITY..Config.MAX_DISPLAY_DENSITY) {
            "密度需在 ${Config.MIN_DISPLAY_DENSITY}–${Config.MAX_DISPLAY_DENSITY} dpi 之间"
        }
        check(prefs.edit()
            .putInt("displayWidth", width)
            .putInt("displayHeight", height)
            .putInt("displayDensity", density)
            .commit()) { "保存失败，请重试" }
        config = Config(context)
        notice = "已保存；新任务将使用此画布"
    }

    fun setGuard(enabled: Boolean) {
        guardChanging = true
        AccessibilityGuard.setEnabled(context, enabled) { applied ->
            guardEnabled = applied; guardChanging = false; refresh()
        }
    }

    fun refresh() {
        accessible = ScreenService.isHealthy()
        hookReport = prefs.getString("hook_report", null) ?: "暂无报告，请在 LSPosed 中启用模块后重启小爱"
        hookRecordedAt = prefs.getLong("hook_at", 0L)
        reloadStatus = HotReload.status()
        guardStatus = AccessibilityGuard.status(context)
        if (!AccessibilityGuard.isChanging()) guardEnabled = AccessibilityGuard.enabled(context)
    }

    fun refreshLight() {
        accessible = ScreenService.isHealthy()
        reloadStatus = HotReload.status()
    }

    fun reload() { HotReload.request(); refresh() }

    fun fetchModels() {
        val source = draft ?: return
        if (fetching) return
        fetching = true; error = null
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { ModelClient.listModels(source.endpoint, source.apiKey).distinct() }
                if (draft?.id == source.id && draft?.endpoint == source.endpoint && draft?.apiKey == source.apiKey) {
                    if (models.isEmpty()) error = "服务未返回模型，可手动填写" else modelList = models
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (draft?.id == source.id && draft?.endpoint == source.endpoint && draft?.apiKey == source.apiKey)
                    error = "获取失败，请检查服务地址和密钥"
            }
            finally { fetching = false }
        }
    }

    fun startTest(onScreenTask: () -> Unit) {
        if (job != null || starting) return
        if (virtualScreen.busy) { error = "请先等待虚拟屏检查完成"; return }
        if (query.isBlank()) { error = "请先填写任务或问题"; return }
        if (testMode == 0 && config.planner == "native") {
            result = "请向小爱说出任务。这里仅测试独立外部模型。"; return
        }
        if (!attempt { requireNotNull(profiles).active().validated(true) }) return
        if (!config.enabled) { error = "请先开启控屏工具"; return }
        starting = true
        result = "正在启动…"
        val testQuery = query.trim()
        val screen = testMode == 0
        // Physical tasks need the target app visible; background tasks use their own display.
        // This callback runs immediately and is not retained by the polling coroutine.
        if (screen && !config.backgroundDisplay) onScreenTask()
        viewModelScope.launch {
            try {
                val extras = Bundle().apply {
                    putString("query", testQuery); putString("mode", if (screen) "screen" else "chat")
                }
                val response = withContext(Dispatchers.IO) {
                    context.contentResolver.call(BridgeProvider.URI, "start", null, extras)
                }
                if (response == null) { error = "无法连接模块"; return@launch }
                if (response.containsKey("error")) { error = response.getString("error") ?: "无法启动任务"; return@launch }
                job = checkNotNull(response.getString("id")) { "未收到任务编号" }
                starting = false
                while (job != null) {
                    val current = job
                    val status = withContext(Dispatchers.IO) {
                        context.contentResolver.call(BridgeProvider.URI, "poll", current, null)
                    }
                    if (status == null || status.containsKey("error")) { result = "任务已结束或连接中断"; break }
                    if (status.getString("status") != "running") { result = status.getString("result") ?: "任务已结束"; break }
                    result = status.getString("progress") ?: "正在执行…"
                    delay(500)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "模块连接失败，请检查运行状态" }
            finally { job = null; starting = false }
        }
    }

    fun stopTest() { BridgeProvider.cancelActive(); result = "已请求停止" }
}

data class BackgroundCheck(val title: String, val summary: String) {
    companion object {
        fun from(response: Bundle?): BackgroundCheck = when {
            response == null -> BackgroundCheck("检查失败", "未收到模块返回的结果")
            response.getString("error") == "任务不存在或不可访问" -> BackgroundCheck("后端需更新", "当前版本不支持虚拟屏检查")
            response.getString("error") != null -> BackgroundCheck("暂不可用", response.getString("error")!!)
            !response.getBoolean("root") -> BackgroundCheck("未取得 root 授权", "请在 root 管理器中允许 MiAIPlus")
            response.getBoolean("deviceLocked") -> BackgroundCheck("手机已锁定", "解锁后重新检查")
            else -> BackgroundCheck("前置条件已满足", "已取得 root，手机已解锁。")
        }
    }
}
