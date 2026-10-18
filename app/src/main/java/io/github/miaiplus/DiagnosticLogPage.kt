package io.github.miaiplus

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DiagnosticLogPage(settings: SettingsState) {
    val state = settings.logs
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var follow by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("全部") }
    var confirmClear by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        if (it != null) state.save(it)
    }
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
                }, "分享日志压缩包"))
            } catch (_: Exception) { Toast.makeText(context, "无法打开分享，请使用保存压缩包", Toast.LENGTH_SHORT).show() }
            state.shareUri = null
        }
    }
    val filtered = remember(state.entries, query, source) {
        state.entries.filter { (source == "全部" || it.source == if (source == "模块") "module" else "host") &&
            (query.isBlank() || it.raw.contains(query, ignoreCase = true)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                SwitchPreference(title = "Phone Use 详细日志", checked = settings.config.phoneUseDetailedLog,
                    summary = "记录前台、虚拟屏与小爱附图链路，默认关闭",
                    onCheckedChange = { settings.setOption(Config.PHONE_USE_LOG_KEY, it) })
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.entries.size} 条 · ${state.bytes / 1024} KB / 2048 KB", style = MiuixTheme.textStyles.footnote1)
                    Text("仅保留诊断元数据，不含截图、页面正文或输入内容。旧记录自动轮转，关闭后已有日志保留。",
                        style = MiuixTheme.textStyles.footnote2)
                    Text(state.health.ifBlank { "正在读取…" }, style = MiuixTheme.textStyles.footnote2)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(if (state.busy) "正在处理…" else "保存压缩包", {
                        try { save.launch(state.archiveName()) }
                        catch (_: Exception) { Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show() }
                    }, Modifier.weight(1f), enabled = !state.busy)
                    TextButton("分享", { state.share() }, Modifier.weight(1f), enabled = !state.busy)
                    TextButton("清空", { confirmClear = true }, Modifier.weight(1f), enabled = !state.busy)
                }
                TextField(value = query, onValueChange = { query = it }, label = "搜索事件 / 会话 / 元数据",
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "模块", "小爱").forEach { label ->
                        TextButton(if (source == label) "✓ $label" else label, { source = label }, Modifier.weight(1f))
                    }
                    TextButton("刷新", { state.refresh() }, Modifier.weight(1f), enabled = !state.busy)
                }
                Card { SwitchPreference(title = "自动刷新", summary = "最新记录在前，显示筛选后最近 500 条",
                    checked = follow, onCheckedChange = { follow = it }) }
                state.error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                Text("匹配 ${filtered.size} 条 · 点击记录展开详情", style = MiuixTheme.textStyles.footnote2)
            }
        }
        if (filtered.isEmpty()) item {
            Card { BasicComponent(title = if (query.isBlank() && source == "全部") "暂无日志" else "没有匹配记录",
                summary = "开启详细日志后复现问题，再保存或分享压缩包。无需连接电脑。") }
        }
        itemsIndexed(filtered.take(500)) { _, entry ->
            var expanded by remember(entry.raw) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.event, style = MiuixTheme.textStyles.body1)
                    val time = remember(entry.time) {
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(entry.time))
                    }
                    Text("$time · ${if (entry.source == "host") "小爱" else "模块"}", style = MiuixTheme.textStyles.footnote2)
                    if (entry.session.isNotEmpty()) Text("${entry.session} · 步骤 ${entry.step}", style = MiuixTheme.textStyles.footnote2)
                    if (expanded) SelectionContainer {
                        Text(remember(entry.raw) { JSONObject(entry.raw).toString(2) }, fontFamily = FontFamily.Monospace,
                            style = MiuixTheme.textStyles.footnote2)
                    }
                }
            }
        }
    }
    OverlayDialog(show = confirmClear, title = "清空详细日志？", summary = "删除 App 内保留的记录及分享缓存，已保存到其他位置的压缩包仍保留。开启中的日志会继续记录。",
        onDismissRequest = { confirmClear = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", { confirmClear = false }, Modifier.weight(1f))
            TextButton("清空日志", { confirmClear = false; state.clear() }, Modifier.weight(1f))
        }
    }
}
