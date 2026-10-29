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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
        SmallTitle("内置工具")
        PluginCard {
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
            }, onClick = openPhoneUse)
        }
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
        if (remotes.isEmpty()) PluginCard { BasicComponent(title = "还没有 MCP 插件") }
        remotes.forEach { entry ->
            PluginCard {
                SwitchPreference(title = entry.label(), checked = entry.enabled(), enabled = !state.busy,
                    summary = if (entry.tools().isEmpty()) "待获取工具" else "${entry.tools().size} 个工具",
                    onCheckedChange = { enabled ->
                        if (enabled) confirmEnable = entry.id() else state.setEnabled(entry.id(), false)
                    })
                if (entry.tools().isNotEmpty()) ArrowPreference(title = "工具列表",
                    summary = entry.tools().joinToString(" · ") { it.displayName() }.take(80),
                    onClick = { inspectTools = entry.id() })
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("编辑", { state.edit(entry.id()) }, Modifier.weight(1f), enabled = !state.busy)
                    TextButton(if (state.refreshingId == entry.id()) "连接中…"
                        else if (entry.tools().isEmpty()) "获取工具" else "刷新工具",
                        { state.refreshTools(entry.id()) }, Modifier.weight(1f), enabled = !state.busy)
                    TextButton("删除", { confirmDelete = entry.id() }, Modifier.weight(1f), enabled = !state.busy)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (remotes.isNotEmpty()) TextButton("停止调用", { state.stopRemoteCalls() },
            Modifier.padding(horizontal = 12.dp).fillMaxWidth())
    }
    McpPluginEditor(state)
    val enableEntry = state.entries.firstOrNull { it.id() == confirmEnable }
    OverlayDialog(show = enableEntry != null, title = "启用 MCP 插件？",
        summary = "调用参数将发送给「${enableEntry?.label().orEmpty()}」。" +
            if (enableEntry?.endpoint()?.startsWith("http://") == true) "当前连接未加密。" else "",
        onDismissRequest = { confirmEnable = null }) {
        PluginDialogButtons("启用", {
            enableEntry?.let { state.setEnabled(it.id(), true) }
            confirmEnable = null
        }, { confirmEnable = null })
    }
    val deleteEntry = state.entries.firstOrNull { it.id() == confirmDelete }
    OverlayDialog(show = deleteEntry != null, title = "删除插件？", summary = deleteEntry?.label().orEmpty(),
        onDismissRequest = { confirmDelete = null }) {
        PluginDialogButtons("删除", {
            deleteEntry?.let { state.delete(it.id()) }
            confirmDelete = null
        }, { confirmDelete = null })
    }
    val toolsEntry = state.entries.firstOrNull { it.id() == inspectTools }
    OverlayDialog(show = toolsEntry != null, title = "${toolsEntry?.label().orEmpty()} · 工具",
        onDismissRequest = { inspectTools = null }) {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(toolsEntry?.tools().orEmpty(), key = { it.name() }) { tool ->
                var expanded by remember(tool.name(), tool.inputSchema()) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tool.displayName(), style = MiuixTheme.textStyles.body2)
                            tool.description().takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
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
    // Keep editing state above the overlay host so delayed draft snapshots cannot reset the cursor.
    // Plain remember also keeps credentials out of SavedState.
    var labelValue by remember(draft?.id) { mutableStateOf(pluginEditingValue(draft?.label.orEmpty())) }
    var endpointValue by remember(draft?.id) { mutableStateOf(pluginEditingValue(draft?.endpoint.orEmpty())) }
    var tokenValue by remember(draft?.id) { mutableStateOf(pluginEditingValue(draft?.bearerToken.orEmpty())) }
    OverlayDialog(show = draft != null, title = if (draft?.id.isNullOrEmpty()) "添加 MCP 插件" else "编辑 MCP 插件",
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
            Text("例如 https://example.com/mcp", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(value = tokenValue, onValueChange = {
                tokenValue = it
                if (it.text != state.draft?.bearerToken) state.updateDraft(bearerToken = it.text)
            }, label = "Token（选填）", singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("plugin-token"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation())
            SwitchPreference(title = "显示 Token", checked = revealToken, onCheckedChange = { revealToken = it })
            val versions = PluginSettings.MCP_PROTOCOL_VERSIONS
            OverlayDropdownPreference(title = "协议版本", enabled = !state.busy,
                items = versions.map { if (it == PluginSettings.MCP_PROTOCOL_VERSION) "$it（默认）" else "$it（兼容）" },
                selectedIndex = versions.indexOf(draft.protocolVersion).coerceAtLeast(0),
                onSelectedIndexChange = { state.updateDraft(protocolVersion = versions[it]) })
            state.error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            PluginDialogButtons("保存", { state.save() }, { state.discardDraft() })
        }
    }
}

private fun pluginEditingValue(text: String) = TextFieldValue(text, TextRange(text.length))

@Composable
private fun PluginCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), content = content)
}

@Composable
private fun PluginDialogButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton("取消", onDismiss, Modifier.weight(1f))
        TextButton(confirm, onConfirm, Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(
            color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
    }
}
