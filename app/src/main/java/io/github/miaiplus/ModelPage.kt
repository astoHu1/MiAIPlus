package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val ApiValues = listOf("auto", "responses", "chat")
private val ApiLabels = listOf("自动", "Responses", "Chat Completions")

/** Endpoint profiles. Edits stay in a draft until the user saves from the bar above the tabs. */
@Composable
internal fun ModelPage(state: SettingsState) {
    val draft = state.draft ?: run { Hint("配置读取失败，请重新打开设置。"); return }
    val profiles = state.profiles ?: return
    var confirmGlobal by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    // Re-mask whenever another profile is shown.
    var revealKey by remember(draft.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.testTag("model-settings"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        state.error?.let { message -> item(key = "error") { ErrorGroup(message) } }
        item(key = "profile") {
            Group {
                OverlayDropdownPreference(
                    title = "当前配置", items = profiles.profiles.map { it.name },
                    selectedIndex = profiles.profiles.indexOfFirst { it.id == profiles.activeId }.coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val id = profiles.profiles[index].id
                        if (id != profiles.activeId) {
                            if (state.dirty) switchTo = id else state.switchProfile(id)
                        }
                    },
                )
            }
        }
        item(key = "connection") {
            Section("连接") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Input("配置名称", draft.name, { state.updateProfile(name = it) })
                    Input("服务地址", draft.endpoint, { state.updateProfile(endpoint = it) }, type = KeyboardType.Uri)
                    FieldNote("例如 https://api.example.com/v1")
                    SecretField("API 密钥（选填）", "密钥", draft.apiKey, { state.updateProfile(key = it) },
                        revealKey, { revealKey = it })
                }
            }
        }
        item(key = "model") {
            Section("模型") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Input("模型名称", draft.model, { state.updateProfile(model = it) })
                }
                ArrowPreference(title = if (state.fetching) "正在获取…" else "从服务选择",
                    summary = if (draft.endpoint.isBlank()) "先填写服务地址" else null,
                    enabled = !state.fetching && draft.endpoint.isNotBlank(),
                    onClick = { state.fetchModels() })
                OverlayDropdownPreference(title = "API 协议", items = ApiLabels,
                    selectedIndex = ApiValues.indexOf(draft.api).coerceAtLeast(0),
                    onSelectedIndexChange = { state.updateProfile(api = ApiValues[it]) })
            }
        }
        item(key = "conversation") {
            Section("小爱对话") {
                SwitchPreference(title = "使用外部模型", checked = state.config.globalModel,
                    enabled = !state.dirty, summary = if (state.dirty) "保存后可更改" else "更改后重启小爱",
                    onCheckedChange = { if (it) confirmGlobal = true else state.setOption("globalModel", false) })
            }
        }
        item(key = "management") {
            Section("管理配置") {
                ArrowPreference(title = "另存为新配置", onClick = { newName = ""; showNew = true })
                ActionRow(title = "删除当前配置", destructive = true, enabled = profiles.profiles.size > 1,
                    summary = if (profiles.profiles.size == 1) "至少保留一份配置" else null,
                    onClick = { showDelete = true })
            }
        }
    }
    ConfirmDialog(show = confirmGlobal, title = "使用外部模型对话？",
        summary = "对话和工具信息将发送到当前 API 服务。",
        confirm = "开启", onDismiss = { confirmGlobal = false },
        onConfirm = { state.setOption("globalModel", true); confirmGlobal = false })
    ConfirmDialog(show = switchTo != null, title = "切换前保存修改？", summary = null,
        confirm = "保存并切换", onDismiss = { switchTo = null },
        error = state.error,
        onConfirm = { val id = switchTo!!; if (state.saveProfile() && state.switchProfile(id)) switchTo = null },
        alternative = "放弃并切换", onAlternative = { if (state.switchProfile(switchTo!!)) switchTo = null })
    OverlayDialog(show = showNew, title = "另存为新配置", summary = "以当前填写的内容新建，并切换到它",
        onDismissRequest = { showNew = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input("新配置名称", newName, { newName = it })
            state.error?.let { Hint(it) }
            DialogButtons("保存并切换", { if (state.saveProfile(newName)) showNew = false }, { showNew = false })
        }
    }
    ConfirmDialog(show = showDelete, title = "删除「${profiles.active().name}」？",
        summary = "配置和密钥将一并删除。",
        confirm = "删除", onDismiss = { showDelete = false },
        error = state.error,
        onConfirm = { if (state.deleteProfile()) showDelete = false })
    OverlayDialog(show = state.modelList != null, title = "选择模型", onDismissRequest = { state.modelList = null }) {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(state.modelList.orEmpty(), key = { it }) { model ->
                BasicComponent(title = model, onClick = { state.updateProfile(model = model); state.modelList = null })
            }
        }
        TextButton("取消", { state.modelList = null }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}
