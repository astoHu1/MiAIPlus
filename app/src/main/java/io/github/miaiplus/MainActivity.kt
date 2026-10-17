package io.github.miaiplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        val state = ViewModelProvider(this)[SettingsState::class.java]
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsApp(state, ::openSettings) { moveTaskToBack(true) }
            }
        }
    }

    private fun openSettings(application: Boolean) {
        val intent = if (application) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")) else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show() }
    }
}

private enum class SettingsPage(val title: String, val depth: Int = 0) {
    Home("MiAIPlus"), Model("模型 API"), Control("控屏"), Virtual("虚拟屏", 1), Status("运行状态", 1), Test("直接测试", 2);

    val tab: SettingsPage get() = when (this) { Virtual -> Control; Status, Test -> Home; else -> this }
    val parent: SettingsPage get() = when (this) { Test -> Status; Virtual -> Control; else -> Home }
}

@Composable
private fun SettingsApp(state: SettingsState, openSettings: (Boolean) -> Unit, onScreenTask: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var leaveTo by remember { mutableStateOf<SettingsPage?>(null) }
    val savedPages = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.refresh()
        }
    }
    LaunchedEffect(lifecycle, page == SettingsPage.Status) {
        if (page == SettingsPage.Status) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.refreshLight()
            while (true) { delay(3500); state.refreshLight() }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.notice = null }
    }
    fun navigate(target: SettingsPage) {
        if (target == page) return
        if (page == SettingsPage.Model && state.dirty) leaveTo = target
        else { state.error = null; page = target }
    }
    BackHandler(page != SettingsPage.Home) { navigate(page.parent) }
    val scrollBehavior = key(page) { MiuixScrollBehavior() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = page.title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (page.depth > 0) IconButton(onClick = { navigate(page.parent) }, modifier = Modifier.size(48.dp)) {
                        Icon(top.yukonga.miuix.kmp.icon.MiuixIcons.Basic.ArrowRight, "返回", Modifier.rotate(180f))
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (page == SettingsPage.Model && state.dirty) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton("还原", { state.discard() }, Modifier.weight(1f))
                        PrimaryButton("保存配置", { state.saveProfile() }, Modifier.weight(2f))
                    }
                }
                NavigationBar {
                    listOf(SettingsPage.Home, SettingsPage.Model, SettingsPage.Control).forEach { tab ->
                        NavigationBarItem(
                            selected = page.tab == tab, onClick = { navigate(tab) },
                            icon = when (tab) {
                                SettingsPage.Home -> SettingsIcons.Home
                                SettingsPage.Model -> SettingsIcons.Model
                                else -> SettingsIcons.Control
                            },
                            label = if (tab == SettingsPage.Home) "首页" else tab.title,
                            modifier = Modifier.testTag("tab-${tab.name}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        PageTransition(page, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { destination ->
            savedPages.SaveableStateProvider(destination) {
                val scroll = rememberScrollState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth()
                            .then(if (destination == SettingsPage.Model) Modifier else Modifier.verticalScroll(scroll)).imePadding()
                            .padding(top = 8.dp, bottom = 24.dp),
                    ) {
                        state.error?.takeIf { destination != SettingsPage.Model }?.let { message ->
                            Group { BasicComponent(title = "未能完成", summary = message) }
                            Spacer(Modifier.height(12.dp))
                        }
                        when (destination) {
                            SettingsPage.Home -> HomePage(state) { navigate(SettingsPage.Status) }
                            SettingsPage.Model -> ModelPage(state)
                            SettingsPage.Control -> ControlPage(state, openSettings) { navigate(SettingsPage.Virtual) }
                            SettingsPage.Virtual -> VirtualScreenPage(state)
                            SettingsPage.Status -> StatusPage(state, openSettings, { navigate(SettingsPage.Virtual) }) { navigate(SettingsPage.Test) }
                            SettingsPage.Test -> TestPage(state, onScreenTask)
                        }
                    }
                }
            }
        }
        ConfirmDialog(
            show = leaveTo != null, title = "保存当前配置？", summary = "还有未保存的修改。",
            confirm = "保存并离开", onDismiss = { leaveTo = null }, error = state.error,
            onConfirm = { if (state.saveProfile()) { page = leaveTo!!; leaveTo = null } },
            alternative = "放弃修改", onAlternative = { state.discard(); page = leaveTo!!; leaveTo = null },
        )
    }
}

/** Tabs replace their content immediately; only secondary navigation keeps an outgoing page. */
@Composable
private fun PageTransition(page: SettingsPage, modifier: Modifier, content: @Composable (SettingsPage) -> Unit) {
    val transition = updateTransition(page, label = "page")
    val tabSwitch = transition.targetState.tab != transition.currentState.tab
    val forward = transition.targetState.depth >= transition.currentState.depth
    val distance = with(LocalDensity.current) { 40.dp.toPx() }
    val destinations = if (tabSwitch) listOf(page)
        else listOf(transition.currentState, transition.targetState).distinct()
    Box(modifier.clipToBounds()) {
        destinations.forEach { destination ->
            key(destination) {
                val visibility = transition.animateFloat(transitionSpec = {
                    if (tabSwitch) snap()
                    else tween(240, easing = FastOutSlowInEasing)
                }, label = "page-${destination.name}") { if (it == destination) 1f else 0f }
                val incoming = destination == transition.targetState
                Box(Modifier.fillMaxSize().testTag("page-${destination.name}")
                    .graphicsLayer {
                        alpha = if (tabSwitch) 1f else visibility.value
                        translationX = (1f - alpha) * distance *
                            (if (incoming) { if (forward) 1f else -0.3f } else { if (forward) -0.3f else 1f })
                    }
                    .then(if (!incoming) Modifier.clearAndSetSemantics { hideFromAccessibility() }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        } else Modifier)) {
                    content(destination)
                }
            }
        }
    }
}

@Composable
private fun HomePage(state: SettingsState, openStatus: () -> Unit) {
    val reported = state.hookRecordedAt > 0L
    val ready = reported && state.hookReport.startsWith("ready:")
    val status = when { !reported -> "暂无注入报告"; ready -> "工作正常"; else -> "上次注入异常" }
    Card(
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (ready) Color(0xFF34C759) else MiuixTheme.colorScheme.surfaceContainer,
            contentColor = if (ready) Color.White else MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SettingsIcons.Module, null, Modifier.size(48.dp),
                tint = if (ready) Color.White else MiuixTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, style = MiuixTheme.textStyles.title3)
                Text(if (reported) android.text.format.DateUtils.formatDateTime(
                    androidx.compose.ui.platform.LocalContext.current, state.hookRecordedAt,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)
                    else "启用模块后重启小爱", style = MiuixTheme.textStyles.footnote2)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardTile("版本", BuildConfig.VERSION_NAME, Modifier.weight(1f))
        DashboardTile("无障碍", if (state.accessible) "已连接" else "未连接", Modifier.weight(1f))
    }
    Section("当前配置") {
        BasicComponent(title = "控屏工具", summary = if (state.config.enabled) "已开启" else "已关闭")
        BasicComponent(title = "规划模型", summary = if (state.config.planner == "external")
            state.profiles?.active()?.model?.ifBlank { "未选择" } ?: "未配置" else "小爱当前模型")
    }
    Spacer(Modifier.height(16.dp))
    Group { ArrowPreference(title = "运行状态", summary = "连接与诊断", onClick = openStatus) }
}

@Composable
private fun DashboardTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun ModelPage(state: SettingsState) {
    val draft = state.draft ?: run { Hint("配置读取失败。请保留应用数据，重新打开设置。"); return }
    val profiles = state.profiles ?: return
    var confirmGlobal by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var revealKey by remember(draft.id) { mutableStateOf(false) }
    LazyColumn(Modifier.testTag("model-settings")) {
        state.error?.let { message ->
            item(key = "error") {
                Group { BasicComponent(title = "未能完成", summary = message) }
                Spacer(Modifier.height(12.dp))
            }
        }
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
            Section("连接信息") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Input("配置名称", draft.name, { state.updateProfile(name = it) })
                    Input("服务地址", draft.endpoint, { state.updateProfile(endpoint = it) }, type = KeyboardType.Uri)
                    Text("Base URL，如 https://api.example.com/v1", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp))
                    TextField(value = draft.apiKey, onValueChange = { state.updateProfile(key = it) }, label = "API 密钥（可留空）",
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation())
                }
                SwitchPreference(title = "显示密钥", checked = revealKey, onCheckedChange = { revealKey = it })
            }
        }
        item(key = "model") {
            Section("模型") {
                Column(Modifier.padding(16.dp)) {
                    Input("模型名称", draft.model, { state.updateProfile(model = it) })
                }
                ArrowPreference(title = if (state.fetching) "正在获取…" else "从服务选择",
                    enabled = !state.fetching && draft.endpoint.isNotBlank(),
                    onClick = { state.fetchModels() })
                OverlayDropdownPreference(title = "API 协议", items = listOf("自动", "Responses", "Chat Completions"),
                    selectedIndex = listOf("auto", "responses", "chat").indexOf(draft.api).coerceAtLeast(0),
                    onSelectedIndexChange = { state.updateProfile(api = listOf("auto", "responses", "chat")[it]) })
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
                ArrowPreference(title = "删除当前配置", enabled = profiles.profiles.size > 1,
                    summary = if (profiles.profiles.size == 1) "至少保留一份配置" else null,
                    onClick = { showDelete = true })
            }
        }
    }
    ConfirmDialog(show = confirmGlobal, title = "使用外部模型对话？",
        summary = "聊天记录和工具上下文将发送至当前 API 服务。更改后重启小爱。",
        confirm = "开启", onDismiss = { confirmGlobal = false },
        onConfirm = { state.setOption("globalModel", true); confirmGlobal = false })
    ConfirmDialog(show = switchTo != null, title = "切换前保存修改？", summary = "还有未保存的修改。",
        confirm = "保存并切换", onDismiss = { switchTo = null },
        error = state.error,
        onConfirm = { val id = switchTo!!; if (state.saveProfile() && state.switchProfile(id)) switchTo = null },
        alternative = "放弃并切换", onAlternative = { if (state.switchProfile(switchTo!!)) switchTo = null })
    OverlayDialog(show = showNew, title = "另存为新配置", onDismissRequest = { showNew = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input("新配置名称", newName, { newName = it })
            state.error?.let { Hint(it) }
            DialogButtons("保存并切换", { if (state.saveProfile(newName)) showNew = false }, { showNew = false })
        }
    }
    ConfirmDialog(show = showDelete, title = "删除「${profiles.active().name}」？",
        summary = "删除配置和密钥，未保存的修改也会丢弃。",
        confirm = "删除", onDismiss = { showDelete = false },
        error = state.error,
        onConfirm = { if (state.deleteProfile()) showDelete = false })
    OverlayDialog(show = state.modelList != null, title = "选择模型", onDismissRequest = { state.modelList = null }) {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(state.modelList.orEmpty(), key = { it }) { model ->
                ArrowPreference(title = model, onClick = { state.updateProfile(model = model); state.modelList = null })
            }
        }
        TextButton("取消", { state.modelList = null }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable
private fun ControlPage(state: SettingsState, openSettings: (Boolean) -> Unit, openVirtual: () -> Unit) {
    var editSteps by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf("") }
    Group {
        SwitchPreference(title = "启用控屏工具", checked = state.config.enabled,
            summary = "更改后重启小爱", onCheckedChange = { state.setOption("enabled", it) })
    }
    Section("执行") {
        OverlayDropdownPreference(title = "规划模型", items = listOf("小爱当前模型", "外部模型"),
            selectedIndex = if (state.config.planner == "external") 1 else 0,
            onSelectedIndexChange = { state.setOption("planner", if (it == 1) "external" else "native") })
        OverlayDropdownPreference(title = "动作协议", items = listOf("Phone Use", "AutoGLM"),
            selectedIndex = if (state.config.protocol == "autoglm") 1 else 0,
            onSelectedIndexChange = { state.setOption("protocol", if (it == 1) "autoglm" else "phone_use") })
        ArrowPreference(title = "最大步数", summary = "${state.config.maxSteps} 步",
            onClick = { steps = state.config.maxSteps.toString(); editSteps = true })
        ArrowPreference(title = "虚拟屏", summary = "能力检测 · 兼容入口默认屏幕",
            onClick = openVirtual)
    }
    Section("权限") {
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "前往开启",
            onClick = { openSettings(false) })
        SwitchPreference(title = "任务前检查无障碍", summary = if (state.guardChanging) "正在应用…" else "失效时用 root 恢复",
            checked = state.guardEnabled, enabled = !state.guardChanging, onCheckedChange = { state.setGuard(it) })
        ArrowPreference(title = "应用权限", summary = "后台弹出界面", onClick = { openSettings(true) })
    }
    OverlayDialog(show = editSteps, title = "最大步数", summary = "设置 1–80 步，默认 30 步。", onDismissRequest = { editSteps = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Input("步数", steps, { steps = it }, type = KeyboardType.Number)
            state.error?.let { Hint(it) }
            DialogButtons("保存", {
                val value = steps.toIntOrNull()
                if (value == null) state.error = "请输入 1–80 之间的整数"
                else if (state.setOption("maxSteps", value)) editSteps = false
            }, { editSteps = false; state.error = null })
        }
    }
}

@Composable
private fun StatusPage(state: SettingsState, openSettings: (Boolean) -> Unit, openVirtual: () -> Unit, openTest: () -> Unit) {
    Section("模块") {
        BasicComponent(title = "小爱连接", summary = state.hookReport)
        BasicComponent(title = "框架与热重载", summary = state.reloadStatus)
        ArrowPreference(title = "重载模块", summary = "任务结束后可用", onClick = { state.reload() })
    }
    Section("权限") {
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "失效或未连接", onClick = { openSettings(false) })
        BasicComponent(title = "任务前检查", summary = state.guardStatus)
    }
    Section("诊断") {
        ArrowPreference(title = "虚拟屏", summary = "检查与能力检测", onClick = openVirtual)
        ArrowPreference(title = "直接测试", onClick = openTest)
    }
}

@Composable
private fun VirtualScreenPage(state: SettingsState) {
    val screen = state.virtualScreen
    var confirmProbe by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DisplayProbeItem?>(null) }
    Group {
        SwitchPreference(title = "兼容入口默认使用虚拟屏", checked = state.config.backgroundDisplay,
            summary = "旧控屏入口 · 不可用时停止而非转主屏",
            enabled = !screen.busy, onCheckedChange = { state.setOption("backgroundDisplay", it) })
    }
    Section("检查") {
        ArrowPreference(title = if (screen.checking) "正在检查…" else "检查前置条件",
            summary = "检查 root 与锁屏", enabled = !screen.busy,
            onClick = { screen.check() })
        screen.availability?.let { BasicComponent(title = it.title, summary = it.summary) }
    }
    Section("能力检测") {
        ArrowPreference(title = if (screen.loadingApps) "正在读取…" else "测试应用",
            summary = screen.selectedApp?.name ?: "不启动应用", enabled = !screen.busy && !screen.loadingApps,
            onClick = { appQuery = ""; screen.loadApps() })
        ArrowPreference(title = if (screen.probing) "正在检测…" else "开始检测",
            summary = "创建虚拟屏 · 主屏保持不动", enabled = !screen.busy, onClick = { confirmProbe = true })
    }
    screen.error?.let { Group { BasicComponent(title = "未能完成", summary = it) } }
    screen.report?.let { report ->
        Section("检测结果") {
            BasicComponent(title = report.summary, summary = report.appName)
            report.items.forEach { item ->
                ArrowPreference(title = item.title, summary = item.verdict, onClick = { detail = item })
            }
        }
    }
    ConfirmDialog(show = confirmProbe, title = "检测虚拟屏？",
        summary = if (screen.selectedApp == null) "会短暂创建虚拟屏并发送测试输入，不启动应用。期间请勿操作主屏。"
            else "会在虚拟屏打开「${screen.selectedApp!!.name}」，发送点击和按键，结束后关闭。请选择无个人数据的测试应用，期间请勿操作主屏。",
        confirm = "开始", onDismiss = { confirmProbe = false },
        onConfirm = { confirmProbe = false; screen.probe() })
    OverlayDialog(show = screen.apps != null, title = "测试应用", onDismissRequest = { screen.dismissApps() }) {
        Column {
            Input("搜索", appQuery, { appQuery = it })
            Spacer(Modifier.height(12.dp))
            val matches = remember(screen.apps, appQuery) {
                val q = appQuery.trim()
                if (q.isEmpty()) screen.apps.orEmpty()
                else screen.apps.orEmpty().filter { app -> app.name.contains(q, ignoreCase = true)
                    || app.component.substringBefore('/').contains(q, ignoreCase = true) }
            }
            LazyColumn(Modifier.heightIn(max = 320.dp).weight(1f, fill = false)) {
                item { ArrowPreference(title = "不启动应用", onClick = { screen.selectApp(null) }) }
                items(matches, key = { it.component }) { app ->
                    ArrowPreference(title = app.name, summary = app.component.substringBefore('/'),
                        onClick = { screen.selectApp(app) })
                }
                if (matches.isEmpty()) item { Hint("没有匹配的应用", centered = true) }
            }
            TextButton("取消", { screen.dismissApps() }, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
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
private fun TestPage(state: SettingsState, onScreenTask: () -> Unit) {
    Group {
        OverlayDropdownPreference(title = "测试类型", items = listOf("识屏控屏", "纯对话"), selectedIndex = state.testMode,
            enabled = state.job == null && !state.starting, onSelectedIndexChange = { state.testMode = it })
    }
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(value = state.query, onValueChange = { state.query = it },
                label = if (state.testMode == 0) "想在手机上做什么？" else "输入问题",
                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = state.job == null && !state.starting)
            if (state.job == null) PrimaryButton(if (state.starting) "正在启动…" else "开始测试", { state.startTest(onScreenTask) },
                Modifier.fillMaxWidth(), enabled = state.query.isNotBlank() && !state.starting)
            else TextButton("停止任务", { state.stopTest() }, Modifier.fillMaxWidth())
        }
    }
    if (state.result.isNotBlank()) Section(if (state.job == null) "结果" else "执行中") {
        SelectionContainer { Text(state.result, modifier = Modifier.padding(20.dp)) }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), content = content)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    SmallTitle(title)
    Group(content)
}

@Composable
private fun Hint(text: String, centered: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
}

@Composable
private fun Input(label: String, value: String, change: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    TextField(value = value, onValueChange = change, label = label, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false))
}

@Composable
private fun PrimaryButton(text: String, click: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(text, click, modifier, enabled = enabled, colors = ButtonDefaults.textButtonColors(
        color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
}

@Composable
private fun DialogButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton("取消", onDismiss, Modifier.weight(1f))
        PrimaryButton(confirm, onConfirm, Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmDialog(show: Boolean, title: String, summary: String, confirm: String,
                          onDismiss: () -> Unit, onConfirm: () -> Unit,
                          error: String? = null,
                          alternative: String? = null, onAlternative: () -> Unit = {}) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            alternative?.let { TextButton(it, onAlternative, Modifier.fillMaxWidth()) }
            DialogButtons(confirm, onConfirm, onDismiss)
        }
    }
}
