package io.github.miaiplus

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Toolbar actions stay out of the log list, as in the on-device LSPosed viewer. */
@Composable
internal fun DiagnosticLogActions(settings: SettingsState, follow: Boolean, onFollowChange: (Boolean) -> Unit) {
    val state = settings.logs
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        if (it != null) state.save(it)
    }
    IconButton(onClick = { state.share() }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
        Icon(SettingsIcons.Share, "分享", Modifier.size(24.dp))
    }
    IconButton(onClick = {
        try { save.launch(state.archiveName()) }
        catch (_: Exception) { Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show() }
    }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
        Icon(SettingsIcons.Save, "保存压缩包", Modifier.size(24.dp))
    }
    Box {
        IconButton(onClick = { menu = !menu }, modifier = Modifier.size(48.dp)) {
            Icon(SettingsIcons.More, "更多", Modifier.size(24.dp))
        }
        OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, enableWindowDim = false,
            onDismissRequest = { menu = false }) {
            ListPopupColumn {
                val labels = listOf("详细日志", "自动刷新", "刷新", "清空")
                labels.forEachIndexed { index, label ->
                    val selected = when (index) { 0 -> settings.config.phoneUseDetailedLog; 1 -> follow; else -> false }
                    DropdownImpl(item = DropdownItem(text = label, enabled = !state.busy),
                        optionSize = labels.size, isSelected = selected, index = index,
                        onSelectedIndexChange = {
                            menu = false
                            when (it) {
                                0 -> settings.setOption(Config.PHONE_USE_LOG_KEY, !settings.config.phoneUseDetailedLog)
                                1 -> onFollowChange(!follow)
                                2 -> state.refresh()
                                3 -> confirmClear = true
                            }
                        })
                }
            }
        }
    }
    OverlayDialog(show = confirmClear, title = "清空日志？",
        onDismissRequest = { confirmClear = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", { confirmClear = false }, Modifier.weight(1f))
            TextButton("清空日志", { confirmClear = false; state.clear() }, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun DiagnosticLogPage(settings: SettingsState, follow: Boolean) {
    val state = settings.logs
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle, follow) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.refresh()
            if (follow) while (true) { delay(2000); state.refresh() }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.notice = null }
    }
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("MiAIPlus logs", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享日志"))
            } catch (_: Exception) { Toast.makeText(context, "无法打开分享，请使用保存压缩包", Toast.LENGTH_SHORT).show() }
            state.shareUri = null
        }
    }
    val filtered = remember(state.entries, query, source) {
        state.entries.filter { (source == 0 || it.source == if (source == 1) "module" else "host") &&
            (query.isBlank() || it.raw.contains(query, ignoreCase = true)) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(value = query, onValueChange = { query = it }, label = "搜索",
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(SettingsIcons.Search, null, Modifier.padding(start = 12.dp, end = 8.dp).size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        TabRow(listOf("全部", "模块", "小爱"), source, { source = it })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} 条 · ${state.bytes / 1024} KB", style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(if (settings.config.phoneUseDetailedLog) "正在记录" else "记录已关闭",
                style = MiuixTheme.textStyles.footnote2,
                color = if (settings.config.phoneUseDetailedLog) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        state.health.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank() && source == 0) "暂无日志" else "没有匹配记录",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(filtered.take(500)) { _, entry ->
                var expanded by remember(entry.raw) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                    .clickable(role = Role.Button) { expanded = !expanded }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(18.dp).background(MiuixTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(if (entry.source == "host") "H" else "M", style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onPrimary)
                            }
                            Text(if (entry.source == "host") "小爱" else "模块", Modifier.weight(1f),
                                style = MiuixTheme.textStyles.footnote1)
                            val time = remember(entry.time) {
                                java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(entry.time))
                            }
                            Text(time, style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (entry.session.isNotEmpty()) Text("${entry.session} · 步骤 ${entry.step}",
                            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(entry.event, style = MiuixTheme.textStyles.body2, fontFamily = FontFamily.Monospace)
                        if (expanded) SelectionContainer {
                            Text(remember(entry.raw) { JSONObject(entry.raw).toString(2) }, fontFamily = FontFamily.Monospace,
                                style = MiuixTheme.textStyles.footnote2)
                        }
                    }
                }
            }
        }
    }
}
