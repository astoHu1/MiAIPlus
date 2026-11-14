package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ModeValues = listOf(PromptKeys.MODE_OFF, PromptKeys.MODE_APPEND, PromptKeys.MODE_REPLACE)
private val ModeLabels = listOf("关闭", "追加人设", "替换系统提示词")

/** System prompt page. Edits stay in a draft until the user saves from the bar above the tabs. */
@Composable
internal fun PromptPage(state: SettingsState) {
    val draft = state.personaDraft ?: run { Hint("人设读取失败，请重新打开设置。"); return }
    val personas = state.personas ?: return
    var switchTo by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    // Cursor and IME state stay in the composition, never in the ViewModel or SavedStateHandle.
    // A discard, switch or save replaces the draft text; the field then restarts with the cursor at the end.
    var textValue by remember { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    val shownText = if (textValue.text == draft.text) textValue else TextFieldValue(draft.text, TextRange(draft.text.length))
    // The error sits above the list, not in it: a save failure must stay visible while the
    // editor is scrolled to the text field that caused it.
    Column(Modifier.fillMaxSize()) {
        state.error?.let { message -> ErrorGroup(message) }
        LazyColumn(
            modifier = Modifier.testTag("prompt-settings").weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "mode") {
                Group {
                    OverlayDropdownPreference(
                        title = "生效方式", items = ModeLabels,
                        selectedIndex = ModeValues.indexOf(draft.mode).coerceAtLeast(0),
                        onSelectedIndexChange = { state.updatePersona(mode = ModeValues[it]) },
                    )
                }
            }
            item(key = "persona") {
                Spacer(Modifier.height(12.dp))
                Group {
                    OverlayDropdownPreference(
                        title = "人设方案", items = personas.profiles.map { it.name },
                        selectedIndex = personas.profiles.indexOfFirst { it.id == personas.activeId }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val id = personas.profiles[index].id
                            if (id != personas.activeId) {
                                if (state.promptDirty) switchTo = id else state.switchPersona(id)
                            }
                        },
                    )
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                        Input("人设名称", draft.name, { state.updatePersona(name = it) })
                    }
                    ActionRow(title = "另存为人设", onClick = { newName = ""; showNew = true })
                    ActionRow(title = "删除当前人设", destructive = true, enabled = personas.profiles.size > 1,
                        summary = if (personas.profiles.size == 1) "至少保留一个人设" else null,
                        onClick = { showDelete = true })
                }
            }
            item(key = "text") {
                Spacer(Modifier.height(12.dp))
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = shownText, onValueChange = {
                            val next = if (it.text.length > Config.MAX_CUSTOM_PROMPT_CHARS)
                                it.copy(text = it.text.take(Config.MAX_CUSTOM_PROMPT_CHARS),
                                    selection = TextRange(minOf(it.selection.start, Config.MAX_CUSTOM_PROMPT_CHARS),
                                        minOf(it.selection.end, Config.MAX_CUSTOM_PROMPT_CHARS))) else it
                            textValue = next
                            state.updatePersona(text = next.text)
                        }, label = "提示词内容", modifier = Modifier.fillMaxWidth(), minLines = 6)
                        Text("${draft.text.length}/${Config.MAX_CUSTOM_PROMPT_CHARS}",
                            modifier = Modifier.align(Alignment.End), style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item(key = "apply") {
                Spacer(Modifier.height(12.dp))
                Group {
                    SwitchPreference(title = "文字对话走智能体", checked = draft.routeText,
                        summary = "关闭时，官方模型的普通文字对话不经过可注入提示词的通道",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.ROUTE_TEXT, it) })
                    SwitchPreference(title = "语音对话也应用", checked = draft.applyVoice,
                        summary = "需悬浮窗可用；家居控制等语音技能可能受影响",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.APPLY_VOICE, it) })
                    SwitchPreference(title = "独立对话也应用", checked = draft.applyStandalone,
                        summary = "仅作用于模块内的纯对话测试",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.APPLY_STANDALONE, it) })
                }
            }
        }
    }
    ConfirmDialog(show = switchTo != null, title = "切换前保存修改？", summary = null,
        confirm = "保存并切换", onDismiss = { switchTo = null }, error = state.error,
        onConfirm = { val id = switchTo!!; if (state.savePersona() && state.switchPersona(id)) switchTo = null },
        alternative = "放弃并切换", onAlternative = { if (state.switchPersona(switchTo!!)) switchTo = null })
    OverlayDialog(show = showNew, title = "另存为人设", summary = "以当前填写的内容新建，并切换到它",
        onDismissRequest = { showNew = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input("新人设名称", newName, { newName = it.take(PromptKeys.MAX_NAME_CHARS) })
            state.error?.let { Hint(it) }
            DialogButtons("保存并切换", { if (state.savePersona(newName)) showNew = false }, { showNew = false })
        }
    }
    ConfirmDialog(show = showDelete, title = "删除「${personas.active().name}」？", summary = "人设和提示词将一并删除。",
        confirm = "删除", onDismiss = { showDelete = false }, error = state.error,
        onConfirm = { if (state.deletePersona()) showDelete = false })
}
