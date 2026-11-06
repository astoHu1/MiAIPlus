package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PermissionModes = listOf(Config.PHONE_USE_PERMISSION_MANUAL,
    Config.PHONE_USE_PERMISSION_AUTO, Config.PHONE_USE_PERMISSION_YOLO)

/** Phone Use configuration shared by the foreground and background tools. Saves immediately. */
@Composable
internal fun ControlPage(state: SettingsState, openSettings: (Boolean) -> Unit, nav: Navigation) {
    var editSteps by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf("") }
    Group {
        SwitchPreference(title = "启用控屏工具", checked = state.config.enabled,
            summary = "更改后重启小爱", onCheckedChange = { state.setOption("enabled", it) })
    }
    Section("执行") {
        OverlayDropdownPreference(title = "规划模型", items = listOf("小爱当前模型", "外部模型"),
            enabled = !state.config.globalModel,
            summary = if (state.config.globalModel) "跟随小爱对话的模型" else null,
            selectedIndex = if (state.config.planner == "external") 1 else 0,
            onSelectedIndexChange = { state.setOption("planner", if (it == 1) "external" else "native") })
        OverlayDropdownPreference(title = "动作协议", items = listOf("Phone Use", "AutoGLM"),
            selectedIndex = if (state.config.protocol == "autoglm") 1 else 0,
            onSelectedIndexChange = { state.setOption("protocol", if (it == 1) "autoglm" else "phone_use") })
        ArrowPreference(title = "最大步数", summary = "${state.config.maxSteps} 步",
            onClick = { steps = state.config.maxSteps.toString(); state.error = null; editSteps = true })
        ArrowPreference(title = "虚拟屏", summary = "画布与能力检测", onClick = { nav.open(Route.Virtual) })
    }
    Section("权限") {
        OverlayDropdownPreference(title = "操作确认", items = listOf("手动确认", "智能判断", "自动执行"),
            selectedIndex = PermissionModes.indexOf(state.config.phoneUsePermissionMode).coerceAtLeast(0),
            summary = when (state.config.phoneUsePermissionMode) {
                Config.PHONE_USE_PERMISSION_AUTO -> "由模型判断，必要时询问"
                Config.PHONE_USE_PERMISSION_YOLO -> "自动同意，仍保留安全拦截"
                else -> "敏感操作逐次询问"
            },
            onSelectedIndexChange = { state.setPhoneUsePermissionMode(PermissionModes[it]) })
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "未连接，前往开启",
            onClick = { openSettings(false) })
        SwitchPreference(title = "任务前检查无障碍", summary = if (state.guardChanging) "正在应用…" else "失效时用 root 恢复",
            checked = state.guardEnabled, enabled = !state.guardChanging, onCheckedChange = { state.setGuard(it) })
        ArrowPreference(title = "应用权限", summary = "允许后台弹出界面", onClick = { openSettings(true) })
    }
    Section("诊断") {
        SwitchPreference(title = "Phone Use 详细日志", checked = state.config.phoneUseDetailedLog,
            summary = "记录运行过程，方便排查问题",
            onCheckedChange = { state.setOption(Config.PHONE_USE_LOG_KEY, it) })
    }
    NumberDialog(show = editSteps, title = "最大步数", summary = "1–${Config.MAX_PHONE_USE_STEPS} 步",
        label = "步数", value = steps, onValueChange = { steps = it }, error = state.error,
        onSave = {
            val value = steps.toIntOrNull()
            if (value == null) state.error = "请输入 1–${Config.MAX_PHONE_USE_STEPS} 之间的整数"
            else if (state.setOption("maxSteps", value)) editSteps = false
        },
        onDismiss = { editSteps = false; state.error = null })
}

/** Background display: default-screen preference, canvas, and explicit, never automatic, checks. */
@Composable
internal fun VirtualScreenPage(state: SettingsState) {
    val screen = state.virtualScreen
    var confirmProbe by remember { mutableStateOf(false) }
    var editCanvas by remember { mutableStateOf(false) }
    var canvasSession by remember { mutableIntStateOf(0) }
    var appQuery by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DisplayProbeItem?>(null) }
    Group {
        SwitchPreference(title = "兼容入口默认使用虚拟屏", checked = state.config.backgroundDisplay,
            summary = "用于旧版控屏入口",
            enabled = !screen.busy, onCheckedChange = { state.setOption("backgroundDisplay", it) })
    }
    Section("画布") {
        ArrowPreference(title = "自定义分辨率",
            summary = "${state.config.displayWidth}×${state.config.displayHeight} @ ${state.config.displayDensity} dpi",
            onClick = { canvasSession++; editCanvas = true })
    }
    Section("前置条件") {
        ActionRow(title = if (screen.checking) "正在检查…" else "检查前置条件",
            summary = "检查 root 与锁屏", enabled = !screen.busy, onClick = { screen.check() })
        screen.availability?.let { BasicComponent(title = it.title, summary = it.summary) }
    }
    Section("能力检测") {
        ArrowPreference(title = if (screen.loadingApps) "正在读取…" else "测试应用",
            summary = screen.selectedApp?.name ?: "不启动应用", enabled = !screen.busy && !screen.loadingApps,
            onClick = { appQuery = ""; screen.loadApps() })
        // Opens a confirmation first, so it is an arrow row rather than an immediate action.
        ArrowPreference(title = if (screen.probing) "正在检测…" else "开始检测",
            summary = "在虚拟屏测试显示和输入", enabled = !screen.busy, onClick = { confirmProbe = true })
    }
    screen.error?.let { Spacer(Modifier.height(16.dp)); ErrorGroup(it) }
    screen.report?.let { report ->
        Section("检测结果") {
            BasicComponent(title = report.summary, summary = report.appName)
            report.items.forEach { item ->
                ArrowPreference(title = item.title, summary = item.verdict, onClick = { detail = item },
                    summaryColor = BasicComponentDefaults.summaryColor(color = when (item.status) {
                        "unsupported" -> MiuixTheme.colorScheme.error
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }))
            }
        }
    }
    ConfirmDialog(show = confirmProbe, title = "检测虚拟屏？",
        summary = if (screen.selectedApp == null) "将创建虚拟屏并测试输入。检测期间请勿操作手机。"
            else "将在虚拟屏打开「${screen.selectedApp!!.name}」并测试点击和按键。检测期间请勿操作手机。",
        confirm = "开始", onDismiss = { confirmProbe = false },
        onConfirm = { confirmProbe = false; screen.probe() })
    CanvasDialog(state, editCanvas, canvasSession) { editCanvas = false }
    AppPickerDialog(screen, appQuery) { appQuery = it }
    OverlayDialog(show = detail != null, title = detail?.title ?: "检测详情", onDismissRequest = { detail = null }) {
        Column {
            SelectionContainer {
                Text(detail?.detail.orEmpty(), style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            }
            TextButton("关闭", { detail = null }, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun CanvasDialog(state: SettingsState, show: Boolean, session: Int, onDismiss: () -> Unit) {
    // Each opening starts from the saved canvas; filling from the device is still a draft.
    var width by remember(session) { mutableStateOf(state.config.displayWidth.toString()) }
    var height by remember(session) { mutableStateOf(state.config.displayHeight.toString()) }
    var density by remember(session) { mutableStateOf(state.config.displayDensity.toString()) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    OverlayDialog(show = show, title = "虚拟屏画布分辨率", summary = "下次任务生效",
        onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Input("宽度（像素）", width, { width = it }, type = KeyboardType.Number)
            Input("高度（像素）", height, { height = it }, type = KeyboardType.Number)
            Input("密度（dpi）", density, { density = it }, type = KeyboardType.Number)
            TextButton("填入本机分辨率", {
                try {
                    val geometry = state.currentDisplayGeometry()
                    width = geometry.width.toString()
                    height = geometry.height.toString()
                    density = geometry.density.toString()
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "无法读取本机屏幕分辨率"
                }
            }, Modifier.fillMaxWidth())
            error?.let { Hint(it) }
            DialogButtons("保存", {
                val w = width.toIntOrNull()
                val h = height.toIntOrNull()
                val d = density.toIntOrNull()
                if (w == null || h == null || d == null) error = "请输入有效的宽度、高度和密度"
                else if (state.setDisplayGeometry(w, h, d)) onDismiss()
                else error = state.error ?: "画布参数无效"
            }, onDismiss)
        }
    }
}

@Composable
private fun AppPickerDialog(screen: VirtualScreenState, query: String, onQueryChange: (String) -> Unit) {
    OverlayDialog(show = screen.apps != null, title = "测试应用", onDismissRequest = { screen.dismissApps() }) {
        Column {
            Input("搜索", query, onQueryChange)
            Spacer(Modifier.height(12.dp))
            val matches = remember(screen.apps, query) {
                val q = query.trim()
                if (q.isEmpty()) screen.apps.orEmpty()
                else screen.apps.orEmpty().filter { app -> app.name.contains(q, ignoreCase = true)
                    || app.component.substringBefore('/').contains(q, ignoreCase = true) }
            }
            LazyColumn(Modifier.heightIn(max = 320.dp).weight(1f, fill = false)) {
                item { BasicComponent(title = "不启动应用", onClick = { screen.selectApp(null) }) }
                items(matches, key = { it.component }) { app ->
                    BasicComponent(title = app.name, summary = app.component.substringBefore('/'),
                        onClick = { screen.selectApp(app) })
                }
                if (matches.isEmpty()) item { Hint("没有匹配的应用", centered = true) }
            }
            TextButton("取消", { screen.dismissApps() }, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}
