package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * Shared settings building blocks. Row semantics are deliberate:
 * ArrowPreference opens something (page, dialog, system settings), ActionRow runs immediately,
 * and BasicComponent without onClick is read-only status.
 */

@Composable
internal fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.padding(horizontal = 12.dp).fillMaxWidth(), content = content)
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    SmallTitle(title)
    Group(content = content)
}

/** Failure surfaced in the page flow; the message is already user-facing text. */
@Composable
internal fun ErrorGroup(message: String) {
    Group {
        BasicComponent(title = "未能完成", summary = message,
            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error))
    }
    Spacer(Modifier.height(12.dp))
}

/** Runs an operation in place. No arrow, because nothing opens. */
@Composable
internal fun ActionRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                       summary: String? = null, enabled: Boolean = true, destructive: Boolean = false) {
    BasicComponent(title = title, summary = summary, enabled = enabled, onClick = onClick, modifier = modifier,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary))
}

@Composable
internal fun Hint(text: String, centered: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}

/** Example or constraint shown directly under a form field. */
@Composable
internal fun FieldNote(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
internal fun Input(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier,
                   type: KeyboardType = KeyboardType.Text) {
    TextField(value = value, onValueChange = change, label = label, singleLine = true,
        modifier = modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false))
}

@Composable
private fun RevealToggle(revealed: Boolean, subject: String, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.padding(end = 4.dp).size(48.dp)) {
        Icon(if (revealed) SettingsIcons.EyeOff else SettingsIcons.Eye,
            contentDescription = if (revealed) "隐藏$subject" else "显示$subject", modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Masked credential field; the eye toggle keeps focus and selection in the field. */
@Composable
internal fun SecretField(label: String, subject: String, value: String, change: (String) -> Unit,
                         revealed: Boolean, onRevealChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    TextField(value = value, onValueChange = change, label = label, singleLine = true, modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { RevealToggle(revealed, subject) { onRevealChange(!revealed) } })
}

@Composable
internal fun SecretField(label: String, subject: String, value: TextFieldValue, change: (TextFieldValue) -> Unit,
                         revealed: Boolean, onRevealChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    TextField(value = value, onValueChange = change, label = label, singleLine = true, modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { RevealToggle(revealed, subject) { onRevealChange(!revealed) } })
}

@Composable
internal fun PrimaryButton(text: String, click: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(text, click, modifier, enabled = enabled, colors = ButtonDefaults.textButtonColors(
        color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
}

@Composable
internal fun DialogButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton("取消", onDismiss, Modifier.weight(1f))
        PrimaryButton(confirm, onConfirm, Modifier.weight(1f))
    }
}

@Composable
internal fun DialogError(message: String?) {
    message?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
}

@Composable
internal fun ConfirmDialog(show: Boolean, title: String, summary: String?, confirm: String,
                           onDismiss: () -> Unit, onConfirm: () -> Unit,
                           error: String? = null,
                           alternative: String? = null, onAlternative: () -> Unit = {}) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogError(error)
            alternative?.let { TextButton(it, onAlternative, Modifier.fillMaxWidth()) }
            DialogButtons(confirm, onConfirm, onDismiss)
        }
    }
}

/** Single numeric value editor used by step and timeout settings. */
@Composable
internal fun NumberDialog(show: Boolean, title: String, summary: String?, label: String, value: String,
                          onValueChange: (String) -> Unit, error: String?, onSave: () -> Unit, onDismiss: () -> Unit) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input(label, value, onValueChange, type = KeyboardType.Number)
            error?.let { Hint(it) }
            DialogButtons("保存", onSave, onDismiss)
        }
    }
}
