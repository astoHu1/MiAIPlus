package io.github.miaiplus

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** UI client for the module's optional virtual-display IPC. Never starts a helper itself. */
class VirtualScreenState(private val context: Application, private val scope: CoroutineScope) {
    var checking by mutableStateOf(false)
        private set
    var probing by mutableStateOf(false)
        private set
    var loadingApps by mutableStateOf(false)
        private set
    var apps by mutableStateOf<List<ProbeApp>?>(null)
        private set
    var selectedApp by mutableStateOf<ProbeApp?>(null)
        private set
    var availability by mutableStateOf<BackgroundCheck?>(null)
        private set
    var report by mutableStateOf<DisplayProbeReport?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val busy: Boolean get() = checking || probing

    fun selectApp(app: ProbeApp?) { selectedApp = app; apps = null }
    fun dismissApps() { apps = null }

    fun loadApps() {
        if (loadingApps) return
        loadingApps = true; error = null
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    context.packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
                        val activity = info.activityInfo ?: return@mapNotNull null
                        val packageName = activity.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val className = activity.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (!activity.exported || packageName == context.packageName) return@mapNotNull null
                        val label = runCatching { info.loadLabel(context.packageManager)?.toString() }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
                        ProbeApp(label, ComponentName(packageName, className).flattenToString())
                    }.distinctBy { it.component }.sortedBy { it.name }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法读取应用列表" }
            finally { loadingApps = false }
        }
    }

    fun check() {
        if (busy) return
        checking = true; error = null; availability = null
        scope.launch {
            try {
                availability = withContext(Dispatchers.IO) {
                    requireIdle()
                    BackgroundCheck.from(context.contentResolver.call(BridgeProvider.URI,
                        "background_display_status", null, null))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) { error = failure.message ?: "检查失败，请重试" }
            catch (_: Exception) { error = "无法检查，请确认后端已更新" }
            finally { checking = false }
        }
    }

    private fun requireIdle() {
        val screen = context.contentResolver.call(BridgeProvider.URI, "screen_state", null, null)
        val health = context.contentResolver.call(BridgeProvider.URI, "accessibility_health", null, null)
        check(screen != null && health != null) { "无法确认任务状态，请稍后重试" }
        check(!screen.getBoolean("active") && !health.getBoolean("busy") && !health.getBoolean("preparing")) {
            "请先结束当前控屏任务"
        }
    }

    fun probe() {
        if (busy) return
        val app = selectedApp
        probing = true; error = null; report = null
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) {
                    requireIdle()
                    DisplayProbeReport.from(context.contentResolver.call(BridgeProvider.URI,
                        "background_display_probe", app?.component, null), app?.name ?: "不启动应用")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) { error = failure.message ?: "检测失败，请重试" }
            catch (_: Exception) { error = "检测失败，请确认后端已更新" }
            finally { probing = false }
        }
    }
}

data class ProbeApp(val name: String, val component: String)

data class DisplayProbeItem(val id: String, val status: String, val detail: String) {
    val title: String get() = when (id) {
        "createOwnDisplay" -> "创建屏幕"
        "capture" -> "截图"
        "launch" -> "启动应用"
        "tree" -> "无障碍树"
        "pointer" -> "触控"
        "text/key" -> "按键通路"
        "focusIsolation" -> "主屏焦点"
        "teardownIsolation" -> "关闭与回收"
        else -> id
    }
    val verdict: String get() = when (status) {
        "supported" -> "通过"
        "unsupported" -> "未通过"
        else -> "未验证"
    }
}

data class DisplayProbeReport(val appName: String, val items: List<DisplayProbeItem>, val finishedAt: Long) {
    val passed: Int get() = items.count { it.status == "supported" }
    val failed: Int get() = items.count { it.status == "unsupported" }
    val untested: Int get() = items.size - passed - failed
    val summary: String get() = listOfNotNull(
        "$passed 项通过", if (failed > 0) "$failed 项未通过" else null,
        if (untested > 0) "$untested 项未验证" else null,
    ).joinToString(" · ")

    companion object {
        fun from(response: Bundle?, appName: String): DisplayProbeReport {
            check(response != null) { "未收到检测结果" }
            response.getString("error")?.let { failure ->
                check(false) { if (failure == "任务不存在或不可访问") "当前后端不支持能力检测" else failure }
            }
            val encoded = response.getString("items")
            check(!encoded.isNullOrBlank()) { "后端未返回检测项目，请更新后重试" }
            val array = try { JSONArray(encoded) } catch (_: Exception) {
                throw IllegalStateException("检测结果格式有误，请更新后端后重试")
            }
            check(array.length() in 1..64) { "检测结果不完整" }
            val items = (0 until array.length()).map { index ->
                val entry = array.optJSONObject(index)
                    ?: throw IllegalStateException("检测结果不完整")
                val id = entry.optString("item")
                check(id.isNotBlank()) { "检测结果缺少项目名称" }
                DisplayProbeItem(id, entry.optString("status"), entry.toString(2))
            }
            check(items.map { it.id }.distinct().size == items.size) { "检测结果包含重复项目" }
            val complete = items.toMutableList()
            listOf("createOwnDisplay", "capture", "launch", "tree", "pointer", "text/key",
                "focusIsolation", "teardownIsolation").filter { id -> items.none { it.id == id } }.forEach { id ->
                complete += DisplayProbeItem(id, "untested", "后端未返回该项结果")
            }
            // Counts are derived from individual evidence, never from the backend's summary numbers.
            return DisplayProbeReport(appName, complete, SystemClock.elapsedRealtime())
        }
    }
}
