package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Plugin list: built-in tools toggle in place, each MCP server opens its own page. */
@Composable
internal fun PluginPage(state: PluginState, phoneUseEnabled: Boolean, nativePlanner: Boolean, nav: Navigation) {
    LaunchedEffect(Unit) { state.reload() }
    Column(Modifier.testTag("plugin-settings")) {
        state.error?.let { ErrorGroup(it) }
        SmallTitle("内置工具")
        Group {
            state.entries.filter { it.kind() == "builtin" }.forEach { entry ->
                val background = entry.id() == PluginSettings.BACKGROUND_ID
                SwitchPreference(title = if (background) "后台控屏" else "前台控屏",
                    checked = entry.enabled(), enabled = !state.busy,
                    summary = if (background) "在虚拟屏中操作" else "在当前屏幕操作",
                    onCheckedChange = { state.setEnabled(entry.id(), it) })
            }
            ArrowPreference(title = "控屏配置", summary = when {
                !phoneUseEnabled -> "未开启"
                !nativePlanner -> "请选择小爱当前模型"
                else -> null
            }, onClick = { nav.open(Route.Control) })
        }
        Spacer(Modifier.height(12.dp))
        TermuxGroup(state) { nav.open(Route.Termux) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SmallTitle("MCP 插件", modifier = Modifier.weight(1f).testTag("mcp-section-title"),
                insideMargin = PaddingValues(0.dp))
            TextButton("添加", { state.create() }, enabled = !state.busy,
                modifier = Modifier.height(48.dp).testTag("add-mcp-plugin"),
                insideMargin = PaddingValues(horizontal = 12.dp),
                textStyle = MiuixTheme.textStyles.footnote1,
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(8.dp))
        val remotes = state.entries.filter { it.kind() == "mcp" }
        Group {
            if (remotes.isEmpty()) BasicComponent(title = "还没有 MCP 插件")
            remotes.forEach { entry ->
                ArrowPreference(title = entry.label(), summary = mcpSummary(entry, state.refreshingId == entry.id()),
                    onClick = { nav.openMcp(entry.id()) }, modifier = Modifier.testTag("mcp-${entry.id()}"))
            }
        }
    }
}

private fun mcpSummary(entry: PluginSettings.Entry, refreshing: Boolean): String {
    val tools = when {
        refreshing -> "连接中…"
        entry.tools().isEmpty() -> "待获取工具"
        else -> "${entry.tools().size} 个工具"
    }
    return (if (entry.enabled()) "已启用" else "未启用") + " · " + tools
}

@Composable
private fun TermuxGroup(state: PluginState, openSettings: () -> Unit) {
    val entry = state.termuxEntry
    Group {
        SwitchPreference(title = "Termux MCP",
            summary = if (entry == null) "请先配置" else "以 Termux 权限运行 shell",
            checked = entry?.enabled() == true, enabled = entry != null && !state.busy,
            onCheckedChange = { entry?.let { configured -> state.setEnabled(configured.id(), it) } },
            modifier = Modifier.testTag("termux-enabled"))
        ArrowPreference(title = "Termux 配置", onClick = openSettings,
            modifier = Modifier.testTag("termux-settings"))
    }
}

/** One MCP server: enable, connection, discovered tools and removal. */
@Composable
internal fun McpPluginPage(state: PluginState, id: String?, nav: Navigation) {
    val entry = state.entries.firstOrNull { it.id() == id && it.kind() == "mcp" }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf<String?>(null) }
    if (entry == null) {
        state.error?.let { ErrorGroup(it) }
        Group { BasicComponent(title = "插件不存在", summary = "可能已被删除") }
        return
    }
    val refreshing = state.refreshingId == entry.id()
    Column(Modifier.testTag("mcp-plugin-page")) {
        state.error?.let { ErrorGroup(it) }
        Group {
            SwitchPreference(title = "启用", checked = entry.enabled(), enabled = !state.busy,
                summary = if (entry.tools().isEmpty()) "获取工具后小爱才能调用" else null,
                onCheckedChange = { enabled -> if (enabled) confirmEnable = true else state.setEnabled(entry.id(), false) },
                modifier = Modifier.testTag("mcp-enabled"))
        }
        Section("连接") {
            BasicComponent(title = "服务地址", summary = entry.endpoint())
            BasicComponent(title = "协议", summary = entry.protocolVersion())
            ArrowPreference(title = "编辑连接", enabled = !state.busy, onClick = { state.edit(entry.id()) })
        }
        Section("工具") {
            entry.tools().forEach { tool ->
                ArrowPreference(title = tool.displayName(), summary = tool.description().ifBlank { null }?.let(::clip),
                    onClick = { inspect = tool.name() })
            }
            ActionRow(title = when {
                refreshing -> "连接中…"
                entry.tools().isEmpty() -> "获取工具"
                else -> "刷新工具"
            }, enabled = !state.busy, onClick = { state.refreshTools(entry.id()) })
        }
        Spacer(Modifier.height(16.dp))
        Group {
            ActionRow(title = "删除插件", destructive = true, enabled = !state.busy, onClick = { confirmDelete = true })
        }
    }
    OverlayDialog(show = confirmEnable, title = "启用 MCP 插件？",
        summary = "调用参数将发送给「${entry.label()}」。" +
            if (entry.endpoint().startsWith("http://")) "当前连接未加密。" else "",
        onDismissRequest = { confirmEnable = false }) {
        DialogButtons("启用", { state.setEnabled(entry.id(), true); confirmEnable = false }, { confirmEnable = false })
    }
    OverlayDialog(show = confirmDelete, title = "删除插件？", summary = "「${entry.label()}」及其 Token 将一并删除。",
        onDismissRequest = { confirmDelete = false }) {
        DialogButtons("删除", {
            confirmDelete = false
            if (state.delete(entry.id())) nav.back()
        }, { confirmDelete = false })
    }
    val tool = entry.tools().firstOrNull { it.name() == inspect }
    OverlayDialog(show = tool != null, title = tool?.displayName() ?: "工具", onDismissRequest = { inspect = null }) {
        if (tool != null) ToolDetail(tool) { inspect = null }
    }
}

@Composable
private fun ToolDetail(tool: PluginToolSpec, onClose: () -> Unit) {
    var expanded by remember(tool.name(), tool.inputSchema()) { mutableStateOf(false) }
    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tool.description().takeIf { it.isNotBlank() }?.let {
            SelectionContainer {
                Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        TextButton(if (expanded) "收起详情" else "参数详情", { expanded = !expanded }, Modifier.fillMaxWidth())
        if (expanded) SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("来源：${tool.sourceName()}", style = MiuixTheme.textStyles.footnote1)
                Text("工具名：${tool.name()}", style = MiuixTheme.textStyles.footnote1)
                Text(tool.inputSchema(), modifier = Modifier.fillMaxWidth().testTag("plugin-schema-${tool.name()}"),
                    fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        TextButton("关闭", onClose, Modifier.fillMaxWidth())
    }
}

private fun protocolLabel(version: String) =
    if (version == PluginSettings.MCP_PROTOCOL_VERSION) "$version（默认）" else "$version（兼容）"

private fun clip(text: String) = if (text.length > 60) text.take(60) + "…" else text

/** Add/edit dialog. Hosted by the app shell so both the list and the plugin page can open it. */
@Composable
internal fun McpPluginEditor(state: PluginState, onCreated: (String) -> Unit) {
    val draft = state.draft
    var revealToken by remember(draft?.id) { mutableStateOf(false) }
    // Keep editing state above the overlay host so delayed draft snapshots cannot reset the cursor.
    // Plain remember also keeps credentials out of SavedState.
    var labelValue by remember(draft?.id) { mutableStateOf(editingValue(draft?.label.orEmpty())) }
    var endpointValue by remember(draft?.id) { mutableStateOf(editingValue(draft?.endpoint.orEmpty())) }
    var tokenValue by remember(draft?.id) { mutableStateOf(editingValue(draft?.bearerToken.orEmpty())) }
    OverlayDialog(show = draft != null, title = if (draft?.id.isNullOrEmpty()) "添加 MCP 插件" else "编辑连接",
        onDismissRequest = { state.discardDraft() }) {
        if (draft != null) Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = labelValue, onValueChange = {
                labelValue = it
                if (it.text != state.draft?.label) state.updateDraft(label = it.text)
            }, label = "插件名称", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plugin-label"))
            TextField(value = endpointValue, onValueChange = {
                endpointValue = it
                if (it.text != state.draft?.endpoint) state.updateDraft(endpoint = it.text)
            }, label = "MCP 服务地址", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plugin-endpoint"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            FieldNote("例如 https://example.com/mcp")
            SecretField("Token（选填）", " Token", tokenValue, {
                tokenValue = it
                if (it.text != state.draft?.bearerToken) state.updateDraft(bearerToken = it.text)
            }, revealToken, { revealToken = it }, Modifier.testTag("plugin-token"))
            val versions = PluginSettings.MCP_PROTOCOL_VERSIONS
            OverlayDropdownPreference(title = "协议版本", enabled = !state.busy,
                items = versions.map(::protocolLabel),
                selectedIndex = versions.indexOf(draft.protocolVersion).coerceAtLeast(0),
                onSelectedIndexChange = { state.updateDraft(protocolVersion = versions[it]) })
            DialogError(state.error)
            DialogButtons("保存", {
                val creating = draft.id.isEmpty()
                val before = state.entries.map { it.id() }.toSet()
                if (state.save() && creating) {
                    state.entries.firstOrNull { it.kind() == "mcp" && it.id() !in before }?.let { onCreated(it.id()) }
                }
            }, { state.discardDraft() })
        }
    }
}

private fun editingValue(text: String) = TextFieldValue(text, TextRange(text.length))

@Composable
internal fun TermuxSettingsPage(state: PluginState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.reload() }
    }
    val entry = state.termuxEntry
    Column(Modifier.testTag("termux-settings-page")) {
        (state.termuxError ?: state.error?.takeIf { state.snapshot == null })?.let { ErrorGroup(it) }
        Group {
            SwitchPreference(title = "按需启动 Termux", summary = "调用时后台启动",
                checked = state.termuxAutoStart, enabled = entry?.enabled() == true && !state.busy,
                onCheckedChange = { state.setTermuxAutoStart(it) },
                modifier = Modifier.testTag("termux-autostart"))
        }
        Section("服务") {
            ActionRow(title = if (state.termuxAction == TermuxAction.Start) "启动中…" else "启动服务",
                enabled = entry != null && state.termuxInstalled && !state.busy,
                summary = if (entry == null) "完成配置后可用" else null,
                onClick = { state.startTermux() }, modifier = Modifier.testTag("termux-start"))
            ActionRow(title = if (state.termuxAction == TermuxAction.Configure) "配置中…"
                else if (entry == null) "自动配置" else "重新配置",
                summary = when {
                    !state.termuxInstalled -> "请先安装 Termux"
                    entry == null -> "需 root 授权，自动补装 Python"
                    else -> "需要 root 授权"
                },
                enabled = state.termuxInstalled && !state.busy,
                onClick = { state.configureTermux() }, modifier = Modifier.testTag("termux-setup"))
        }
    }
}
