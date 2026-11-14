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

/** Mode and switches save immediately; persona edits use the editor's save action. */
@Composable
internal fun PromptPage(state: SettingsState) {
    val draft = state.personaDraft ?: run { Hint("无法读取人设"); return }
    val personas = state.personas ?: return
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    // Keep cursor, selection and IME composition local to the displayed persona.
    var textValue by remember(draft.id) { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    val shownText = if (textValue.text == draft.text) textValue else TextFieldValue(draft.text, TextRange(draft.text.length))
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
                        onSelectedIndexChange = { state.setPromptMode(ModeValues[it]) },
                    )
                }
            }
            item(key = "persona") {
                Spacer(Modifier.height(12.dp))
                Group {
                    OverlayDropdownPreference(
                        title = "人设方案", items = personas.profiles.map { it.name } + "新建人设",
                        selectedIndex = personas.profiles.indexOfFirst { it.id == personas.activeId }.coerceAtLeast(0),
                        actionIndex = personas.profiles.size,
                        onSelectedIndexChange = { index ->
                            if (index == personas.profiles.size) {
                                newName = ""
                                state.error = null
                                showNew = true
                            } else {
                                val id = personas.profiles[index].id
                                if (id != personas.activeId) state.switchPersona(id)
                            }
                        },
                    )
                }
            }
            item(key = "text") {
                Spacer(Modifier.height(12.dp))
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input("人设名称", draft.name, { state.updatePersona(name = it) })
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
                    ActionRow(title = "保存当前人设", onClick = { state.savePersona() })
                    ActionRow(title = "删除当前人设", destructive = true, enabled = personas.profiles.size > 1,
                        onClick = { state.error = null; showDelete = true })
                }
            }
            item(key = "apply") {
                Spacer(Modifier.height(12.dp))
                Group {
                    SwitchPreference(title = "文字对话走智能体", checked = draft.routeText,
                        summary = "让普通文字对话使用人设",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.ROUTE_TEXT, it) })
                    SwitchPreference(title = "语音对话也应用", checked = draft.applyVoice,
                        summary = "可能影响家居控制等语音技能",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.APPLY_VOICE, it) })
                    SwitchPreference(title = "独立对话也应用", checked = draft.applyStandalone,
                        summary = "用于模块内的纯对话测试",
                        onCheckedChange = { state.setPromptSwitch(PromptKeys.APPLY_STANDALONE, it) })
                }
            }
        }
    }
    OverlayDialog(show = showNew, title = "新建人设", onDismissRequest = { showNew = false; state.error = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input("新人设名称", newName, { newName = it.take(PromptKeys.MAX_NAME_CHARS) })
            DialogError(state.error)
            DialogButtons("创建", { if (state.createPersona(newName)) showNew = false },
                { showNew = false; state.error = null })
        }
    }
    ConfirmDialog(show = showDelete, title = "删除「${personas.active().name}」？", summary = null,
        confirm = "删除", onDismiss = { showDelete = false; state.error = null }, error = state.error,
        onConfirm = { if (state.deletePersona()) showDelete = false })
}
