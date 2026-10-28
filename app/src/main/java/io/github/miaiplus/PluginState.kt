package io.github.miaiplus

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Credentials remain in memory while editing; this draft is never placed in SavedState. */
data class McpPluginDraft(val id: String = "", val label: String = "", val endpoint: String = "",
                          val bearerToken: String = "", val protocolVersion: String = PluginSettings.MCP_PROTOCOL_VERSION) {
    constructor(entry: PluginSettings.Entry) : this(entry.id(), entry.label(), entry.endpoint(),
        entry.bearerToken(), entry.protocolVersion())

    override fun toString(): String = "McpPluginDraft(id=$id, label=$label, endpoint=[redacted], " +
        "bearerToken=[redacted], protocolVersion=$protocolVersion)"
}

/** The settings process manages private configuration; discovery is always an explicit user action. */
class PluginState(private val application: Application, private val scope: CoroutineScope) {
    var snapshot by mutableStateOf<PluginSettings.Snapshot?>(null)
        private set
    var draft by mutableStateOf<McpPluginDraft?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var refreshingId by mutableStateOf<String?>(null)
        private set

    val entries: List<PluginSettings.Entry> get() = snapshot?.entries() ?: emptyList()
    val busy: Boolean get() = refreshingId != null
    val summary: String get() {
        val current = snapshot ?: return "插件配置不可读"
        val builtins = current.entries().count { it.kind() == "builtin" }
        val remoteTools = current.entries().filter { it.kind() == "mcp" }.sumOf { it.tools().size }
        return "$builtins 个内置工具 · $remoteTools 个 MCP 工具"
    }

    init { reload() }

    fun reload(): Boolean {
        val loaded = attempt { snapshot = PluginSettings.load(application) }
        if (!loaded) snapshot = null
        return loaded
    }

    fun create() {
        if (busy) return
        draft = McpPluginDraft(); error = null
    }

    fun edit(id: String) {
        if (busy) return
        attempt {
            val entry = entries.firstOrNull { it.id() == id }
                ?: throw IllegalStateException("插件已更改，请刷新设置")
            require(entry.kind() == "mcp") { "内置插件请前往控屏配置" }
            draft = McpPluginDraft(entry)
        }
    }

    fun updateDraft(label: String? = null, endpoint: String? = null, bearerToken: String? = null,
                    protocolVersion: String? = null) {
        val current = draft ?: return
        draft = current.copy(label = label ?: current.label, endpoint = endpoint ?: current.endpoint,
            bearerToken = bearerToken ?: current.bearerToken, protocolVersion = protocolVersion ?: current.protocolVersion)
        error = null
    }

    fun discardDraft() { draft = null; error = null }

    fun save(): Boolean = attempt {
        check(!busy) { "请先等待工具刷新结束" }
        val current = requireNotNull(draft) { "没有待保存的插件" }
        snapshot = PluginSettings.saveMcp(application, current.id, current.label, current.endpoint,
            current.bearerToken, current.protocolVersion)
        draft = null
        notice = "已保存；请手动刷新工具目录"
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = attempt {
        check(!busy) { "请先等待工具刷新结束" }
        snapshot = PluginSettings.setEnabled(application, id, enabled)
        notice = if (enabled) "已启用插件" else "已停用插件"
    }

    fun delete(id: String): Boolean = attempt {
        check(!busy) { "请先等待工具刷新结束" }
        snapshot = PluginSettings.deleteMcp(application, id)
        if (draft?.id == id) draft = null
        notice = "已删除插件"
    }

    fun stopRemoteCalls(): Boolean = attempt {
        val count = PluginRuntime.stopRemoteCalls()
        notice = if (count > 0) "已请求停止 $count 个 MCP 调用" else "没有正在执行的 MCP 调用"
    }

    fun refreshTools(id: String) {
        if (busy) return
        val source = snapshot ?: return
        val entry = source.entries().firstOrNull { it.id() == id && it.kind() == "mcp" } ?: return
        refreshingId = id; error = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    PluginRuntime.refresh(application, entry.id(), source.revision())
                }
                snapshot = updated
                notice = "工具目录已刷新；启用后可供小爱选择"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Server bodies, endpoint query strings and credential values are never UI errors.
                error = "刷新失败，请检查服务地址、协议和认证；原工具目录保持不变。配置已变更时请重新刷新。"
                reloadPreservingError()
            }
            finally { refreshingId = null }
        }
    }

    private fun reloadPreservingError() {
        try { snapshot = PluginSettings.load(application) } catch (_: Exception) { snapshot = null }
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block(); error = null; true
    } catch (failure: IllegalArgumentException) {
        error = failure.message ?: "插件配置无效，请检查后重试"; false
    } catch (failure: IllegalStateException) {
        error = failure.message ?: "插件配置不可用，请保留数据后重试"; false
    } catch (_: Exception) {
        error = "插件配置操作失败，请保留数据后重试"; false
    }
}
