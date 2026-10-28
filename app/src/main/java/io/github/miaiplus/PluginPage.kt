package io.github.miaiplus

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PluginPage(state: PluginState, phoneUseEnabled: Boolean, nativePlanner: Boolean,
               openPhoneUse: () -> Unit) {
    var confirmEnable by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var inspectTools by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.notice = null }
    }
    LaunchedEffect(Unit) { state.reload() }
    Column(Modifier.testTag("plugin-settings")) {
        state.error?.let {
            PluginCard { BasicComponent(title = "未能完成", summary = it) }
            Spacer(Modifier.height(12.dp))
        }
        PluginCard {
            BasicComponent(title = "小爱插件", summary = state.summary)
            ArrowPreference(title = "添加 MCP 插件", summary = "Streamable HTTP · 手动发现工具",
                enabled = !state.busy, onClick = { state.create() })
            ArrowPreference(title = "停止 MCP 调用", summary = "停止当前远程请求；已执行的远端操作无法撤销",
                onClick = { state.stopRemoteCalls() })
        }
        state.entries.forEach { entry ->
            val builtin = entry.kind() == "builtin"
            Spacer(Modifier.height(12.dp))
            PluginCard {
                SwitchPreference(title = entry.label(), checked = entry.enabled(), enabled = !state.busy,
                    summary = if (builtin) "内置 · ${if (entry.id() == PluginSettings.BACKGROUND_ID) "独立虚拟屏" else "用户当前主屏"}"
                        else "MCP · ${entry.tools().size} 个已发现工具",
                    onCheckedChange = { enabled ->
                        if (enabled && !builtin) confirmEnable = entry.id()
                        else state.setEnabled(entry.id(), enabled)
                    })
                if (builtin) {
                    ArrowPreference(title = "控屏配置", summary = when {
                        !phoneUseEnabled -> "还需开启控屏工具；此开关只控制该插件"
                        !nativePlanner -> "还需选择小爱当前模型作为规划模型"
                        else -> "沿用控屏权限、会话和停止规则"
                    }, onClick = openPhoneUse)
                } else {
                    ArrowPreference(title = "工具列表", summary = if (entry.tools().isEmpty()) "尚未发现，请手动刷新"
                        else entry.tools().joinToString(" · ") { it.displayName() }.take(160),
                        onClick = { inspectTools = entry.id() })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton("编辑", { state.edit(entry.id()) }, Modifier.weight(1f), enabled = !state.busy)
                        TextButton(if (state.refreshingId == entry.id()) "正在刷新…" else "刷新工具",
                            { state.refreshTools(entry.id()) }, Modifier.weight(1f), enabled = !state.busy)
                        TextButton("删除", { confirmDelete = entry.id() }, Modifier.weight(1f), enabled = !state.busy)
                    }
                }
            }
        }
        PluginHint("MCP 插件独立于控屏开关。只有启用且已发现的工具才会提供给小爱；保存配置不会自动连接服务器。")
        PluginHint("MCP 工具参数会发送至你配置的服务器。建议使用 HTTPS；HTTP 局域网连接没有传输加密。支持显式选择 2025-11-25 或 2025-06-18 的 Streamable HTTP 工具，不包含 stdio、OAuth 或任意代码加载。")
    }

    McpPluginEditor(state)
    val enableEntry = state.entries.firstOrNull { it.id() == confirmEnable }
    OverlayDialog(show = enableEntry != null, title = "启用 MCP 插件？",
        summary = "小爱可选择该插件的工具，并将工具参数发送至「${enableEntry?.label().orEmpty()}」的服务器。请只启用你信任的服务；HTTP 连接没有传输加密。",
        onDismissRequest = { confirmEnable = null }) {
        PluginDialogButtons("启用", {
            enableEntry?.let { state.setEnabled(it.id(), true) }
            confirmEnable = null
        }, { confirmEnable = null })
    }
    val deleteEntry = state.entries.firstOrNull { it.id() == confirmDelete }
    OverlayDialog(show = deleteEntry != null, title = "删除插件？",
        summary = "将删除「${deleteEntry?.label().orEmpty()}」的连接配置、认证和工具目录。",
        onDismissRequest = { confirmDelete = null }) {
        PluginDialogButtons("删除", {
            deleteEntry?.let { state.delete(it.id()) }
            confirmDelete = null
        }, { confirmDelete = null })
    }
    val toolsEntry = state.entries.firstOrNull { it.id() == inspectTools }
    OverlayDialog(show = toolsEntry != null, title = "${toolsEntry?.label().orEmpty()} · 工具",
        onDismissRequest = { inspectTools = null }) {
        LazyColumn(Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (toolsEntry?.tools().isNullOrEmpty()) item { Text("尚未发现工具，请返回后点击「刷新工具」。") }
            items(toolsEntry?.tools().orEmpty(), key = { it.name() }) { tool ->
                var expanded by remember(tool.name(), tool.inputSchema()) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tool.displayName(), style = MiuixTheme.textStyles.body2)
                            Text("来源名称：${tool.sourceName()}", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text("注入名称：${tool.name()}", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            tool.description().takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                    TextButton(if (expanded) "收起参数结构" else "查看参数结构", { expanded = !expanded },
                        Modifier.fillMaxWidth())
                    if (expanded) SelectionContainer {
                        Text(tool.inputSchema(), modifier = Modifier.fillMaxWidth().testTag("plugin-schema-${tool.name()}"),
                            fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item { TextButton("关闭", { inspectTools = null }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun McpPluginEditor(state: PluginState) {
    val draft = state.draft
    var revealToken by remember(draft?.id) { mutableStateOf(false) }
    OverlayDialog(show = draft != null, title = if (draft?.id.isNullOrEmpty()) "添加 MCP 插件" else "编辑 MCP 插件",
        onDismissRequest = { state.discardDraft() }) {
        if (draft != null) Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = draft.label, onValueChange = { state.updateDraft(label = it) }, label = "插件名称",
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plugin-label"))
            TextField(value = draft.endpoint, onValueChange = { state.updateDraft(endpoint = it) }, label = "MCP 服务地址",
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plugin-endpoint"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            Text("填写完整 Streamable HTTP 地址，如 https://example.com/mcp。修改连接或认证后需重新刷新工具。",
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(value = draft.bearerToken, onValueChange = { state.updateDraft(bearerToken = it) },
                label = "Bearer Token（可留空）", singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("plugin-token"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation())
            SwitchPreference(title = "显示认证", checked = revealToken, onCheckedChange = { revealToken = it })
            val protocolVersions = PluginSettings.MCP_PROTOCOL_VERSIONS
            OverlayDropdownPreference(title = "MCP 协议版本", enabled = !state.busy,
                items = protocolVersions.map { if (it == PluginSettings.MCP_PROTOCOL_VERSION) "$it（默认）" else "$it（兼容）" },
                selectedIndex = protocolVersions.indexOf(draft.protocolVersion).coerceAtLeast(0),
                summary = "按服务器支持版本选择；不自动降级。修改后需重新刷新工具。",
                onSelectedIndexChange = { state.updateDraft(protocolVersion = protocolVersions[it]) })
            Text("兼容协议 ${draft.protocolVersion} · 新插件默认停用。保存后，请手动刷新工具并确认启用。工具参数会发送至此服务器，建议使用 HTTPS。",
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            state.error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            PluginDialogButtons("保存", { state.save() }, { state.discardDraft() })
        }
    }
}

@Composable
private fun PluginCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), content = content)
}

@Composable
private fun PluginHint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun PluginDialogButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton("取消", onDismiss, Modifier.weight(1f))
        TextButton(confirm, onConfirm, Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(
            color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
    }
}
