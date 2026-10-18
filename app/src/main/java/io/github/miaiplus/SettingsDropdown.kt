package io.github.miaiplus

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Settings selectors retain Miuix positioning and selection without a dim layer. */
@Composable
internal fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    summary: String? = null,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val active = enabled && items.isNotEmpty()
    val color = if (active) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    LaunchedEffect(active) { if (!active) expanded = false }
    BasicComponent(
        modifier = Modifier.semantics(mergeDescendants = true) { if (!active) disabled() },
        title = title,
        summary = summary,
        enabled = active,
        role = Role.DropdownList,
        holdDownState = held,
        onClick = {
            expanded = !expanded
            if (expanded) { held = true; haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
        },
        endActions = {
            Text(items.getOrElse(selectedIndex) { "" }, color = color,
                fontSize = MiuixTheme.textStyles.body2.fontSize, textAlign = TextAlign.End,
                modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).weight(1f, fill = false))
            DropdownArrowEndAction(actionColor = color)
            OverlayListPopup(
                show = expanded && active,
                alignment = PopupPositionProvider.Align.End,
                enableWindowDim = false,
                onDismissRequest = { expanded = false },
                onDismissFinished = { held = false },
            ) {
                ListPopupColumn {
                    items.forEachIndexed { index, label ->
                        DropdownImpl(item = DropdownItem(text = label), optionSize = items.size,
                            isSelected = index == selectedIndex, index = index,
                            onSelectedIndexChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onSelectedIndexChange(it)
                                expanded = false
                            })
                    }
                }
            }
        },
    )
}
