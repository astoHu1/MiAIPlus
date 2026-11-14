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

/** Short aliases for the flat runtime keys Config owns; the host reads them through prompt_settings. */
internal object PromptKeys {
    const val MODE = Config.CUSTOM_PROMPT_MODE_KEY
    const val ROUTE_TEXT = Config.PROMPT_ROUTE_TEXT_CHAT_KEY
    const val APPLY_VOICE = Config.PROMPT_APPLY_VOICE_KEY
    const val APPLY_STANDALONE = Config.PROMPT_APPLY_STANDALONE_CHAT_KEY
    const val MODE_OFF = Config.CUSTOM_PROMPT_OFF
    const val MODE_APPEND = Config.CUSTOM_PROMPT_APPEND
    const val MODE_REPLACE = Config.CUSTOM_PROMPT_REPLACE
    const val MAX_NAME_CHARS = PersonaProfiles.MAX_NAME_CHARS
}

/** Active persona plus the global prompt options; text never goes to SavedStateHandle. */
data class PersonaDraft(val id: String, val name: String, val text: String, val mode: String,
                        val routeText: Boolean, val applyVoice: Boolean, val applyStandalone: Boolean) {
    fun withSwitch(key: String, value: Boolean) = when (key) {
        PromptKeys.ROUTE_TEXT -> copy(routeText = value)
        PromptKeys.APPLY_VOICE -> copy(applyVoice = value)
        PromptKeys.APPLY_STANDALONE -> copy(applyStandalone = value)
        else -> this
    }
}

data class DisplayGeometry(val width: Int, val height: Int, val density: Int)

/** UI state only. Runtime configuration and task safeguards stay in their existing owners. */
class SettingsState(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val prefs = Config.prefs(context)
    val virtualScreen = VirtualScreenState(application, viewModelScope)
    val logs = DiagnosticLogState(application, viewModelScope)
    val plugins = PluginState(application, viewModelScope)
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
    var personas by mutableStateOf<PersonaProfiles?>(null)
        private set
    /** Last committed persona state; the home summary reads this, never the draft. */
    var personaSaved by mutableStateOf<PersonaDraft?>(null)
        private set
    var personaDraft by mutableStateOf<PersonaDraft?>(null)
        private set
    val promptDirty: Boolean by derivedStateOf(structuralEqualityPolicy()) { personaDraft != personaSaved }

    init {
        try { loadProfiles() } catch (failure: Exception) { error = failure.message }
        try { loadPersonas() } catch (failure: Exception) { error = failure.message ?: "无法读取人设" }
        refresh()
    }

    private fun loadPersonas() {
        val collection = PersonaProfiles(prefs)
        val active = collection.active()
        val values = prefs.all
        val saved = PersonaDraft(active.id, active.name, Config.truncatePrompt(active.text),
            Config.normalizePromptMode(values[PromptKeys.MODE]),
            values[PromptKeys.ROUTE_TEXT] == true, values[PromptKeys.APPLY_VOICE] == true,
            values[PromptKeys.APPLY_STANDALONE] == true)
        personas = collection; personaSaved = saved; personaDraft = saved
    }

    fun updatePersona(name: String? = null, text: String? = null, mode: String? = null) {
        val current = personaDraft ?: return
        personaDraft = current.copy(name = name?.take(PromptKeys.MAX_NAME_CHARS) ?: current.name,
            text = text?.let(Config::truncatePrompt) ?: current.text, mode = mode ?: current.mode)
        error = null
    }

    fun discardPersona() { personaDraft = personaSaved; error = null }

    /** Saves the draft into the active persona, or as a new persona when [newName] is given. */
    fun savePersona(newName: String? = null): Boolean = attempt {
        val current = requireNotNull(personaDraft) { "无法读取人设" }
        val collection = requireNotNull(personas) { "无法读取人设" }
        val name = (newName ?: current.name).trim().take(PromptKeys.MAX_NAME_CHARS)
        require(name.isNotEmpty()) { "请填写人设名称" }
        // An empty persona is only kept while the prompt is off; a new persona always needs content.
        require(current.text.isNotBlank() || (newName == null && current.mode == PromptKeys.MODE_OFF)) {
            "请填写提示词内容"
        }
        val id = if (newName == null) current.id else UUID.randomUUID().toString()
        require(collection.profiles.none { it.id != id && it.name == name }) { "人设名称已存在" }
        val editor = prefs.edit()
        // write() also stages the customPromptText snapshot, so the host only reads flat keys.
        collection.write(editor, PersonaProfiles.Profile(id, name, Config.truncatePrompt(current.text)), false)
        editor.putString(PromptKeys.MODE, current.mode)
        check(editor.commit()) { "保存失败，请重试" }
        loadPersonas(); config = Config(context)
        notice = if (newName == null) "已保存" else "已新增人设"
    }

    fun switchPersona(id: String): Boolean = attempt {
        val collection = requireNotNull(personas) { "无法读取人设" }
        val selected = collection.find(id)
        val editor = prefs.edit()
        collection.write(editor, selected, false)
        check(editor.commit()) { "切换失败，请重试" }
        loadPersonas(); config = Config(context); notice = "已切换人设"
    }

    fun deletePersona(): Boolean = attempt {
        val collection = requireNotNull(personas) { "无法读取人设" }
        check(collection.profiles.size > 1) { "至少保留一个人设" }
        val next = collection.profiles.first { it.id != collection.activeId }
        val editor = prefs.edit()
        collection.write(editor, next, true)
        check(editor.commit()) { "删除失败，请重试" }
        loadPersonas(); config = Config(context); notice = "人设已删除"
    }

    /** Prompt switches save at once through setOption and leave other unsaved draft edits intact. */
    fun setPromptSwitch(key: String, value: Boolean): Boolean {
        require(key == PromptKeys.ROUTE_TEXT || key == PromptKeys.APPLY_VOICE || key == PromptKeys.APPLY_STANDALONE)
        val saved = setOption(key, value)
        if (saved) {
            personaSaved = personaSaved?.withSwitch(key, value)
            personaDraft = personaDraft?.withSwitch(key, value)
        }
        return saved
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
        require(key != SpeechReplyPolicy.KEY || value == SpeechReplyPolicy.TEXT_ONLY
            || value == SpeechReplyPolicy.FOLLOW_INPUT || value == SpeechReplyPolicy.HOST_DEFAULT) {
            "请选择有效的语音回复策略"
        }
        require(key != Config.PHONE_USE_PERMISSION_MODE_KEY || value == Config.PHONE_USE_PERMISSION_MANUAL
            || value == Config.PHONE_USE_PERMISSION_AUTO || value == Config.PHONE_USE_PERMISSION_YOLO) {
            "请选择 Manual、Auto 或 YOLO 权限预设"
        }
        require(key != "planner" || value != "external" || !config.globalModel) {
            "全局外部模型已启用，控屏使用小爱当前模型"
        }
        if ((key == "globalModel" && value == true) || (key == "planner" && value == "external")) {
            requireNotNull(profiles).active().validated(true)
        }
        val editor = prefs.edit()
        if (key == "globalModel" && value == true) editor.putString("planner", "native")
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> { require(value in 1..Config.MAX_PHONE_USE_STEPS) { "步数需在 1–${Config.MAX_PHONE_USE_STEPS} 之间" }; editor.putInt(key, value) }
        }
        check(editor.commit()) { "保存失败，请重试" }
        config = Config(context)
        if (key == "enabled" && value == false) BridgeProvider.cancelActive("module_disabled")
        notice = if (key == "globalModel" || key == "enabled") "已保存，请重启小爱" else "已保存"
    }

    fun setPhoneUsePermissionMode(mode: String): Boolean {
        val saved = setOption(Config.PHONE_USE_PERMISSION_MODE_KEY, mode)
        if (saved) notice = "已保存；新任务使用此权限预设"
        return saved
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

    fun stopTest() { BridgeProvider.cancelActive("settings_stop"); result = "已请求停止" }
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
