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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

private enum class SettingsPage(val title: String) {
    Home("MiAIPlus"), Model("模型服务"), Control("控屏设置"), Status("运行状态"), Test("直接测试")
}

@Composable
private fun SettingsApp(state: SettingsState, openSettings: (Boolean) -> Unit, onScreenTask: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var leaveTo by remember { mutableStateOf<SettingsPage?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(lifecycle, page) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.refresh()
            // Live diagnostics are useful only while their page is visible.
            if (page == SettingsPage.Status) {
                while (true) { delay(2000); state.refresh() }
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.notice = null }
    }
    fun navigate(target: SettingsPage) {
        if (page == SettingsPage.Model && state.dirty) leaveTo = target
        else { state.error = null; page = target }
    }
    val parentPage = if (page == SettingsPage.Test) SettingsPage.Status else SettingsPage.Home
    BackHandler(page != SettingsPage.Home) { navigate(parentPage) }
    val scrollBehavior = key(page) { MiuixScrollBehavior() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = page.title,
                subtitle = if (page == SettingsPage.Home) "小爱的控屏与模型设置" else "",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (page != SettingsPage.Home) IconButton(onClick = { navigate(parentPage) }, modifier = Modifier.size(48.dp)) {
                        // Use the library's icon rather than a font glyph.
                        Icon(top.yukonga.miuix.kmp.icon.MiuixIcons.Basic.ArrowRight, "返回", Modifier.rotate(180f))
                    }
                },
            )
        },
        bottomBar = {
            if (page == SettingsPage.Model && state.dirty) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton("还原", { state.discard() }, Modifier.weight(1f))
                    PrimaryButton("保存配置", { state.saveProfile() }, Modifier.weight(2f))
                }
            }
        },
    ) { padding ->
        // Keep one page tree and avoid another lookahead/size transition around a
        // form whose collapsing app bar already changes its layout constraints.
        key(page) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(scroll).imePadding()
                        .padding(top = 8.dp, bottom = 32.dp),
                ) {
                    state.error?.let { message ->
                        Group { BasicComponent(title = "未能完成", summary = message) }
                        Spacer(Modifier.height(12.dp))
                    }
                    when (page) {
                        SettingsPage.Home -> HomePage(state, ::navigate)
                        SettingsPage.Model -> ModelPage(state)
                        SettingsPage.Control -> ControlPage(state, openSettings)
                        SettingsPage.Status -> StatusPage(state, openSettings) { navigate(SettingsPage.Test) }
                        SettingsPage.Test -> TestPage(state, onScreenTask)
                    }
                }
            }
        }
        ConfirmDialog(
            show = leaveTo != null, title = "保存当前配置？", summary = "地址、密钥或模型有未保存的修改。",
            confirm = "保存并返回", onDismiss = { leaveTo = null },
            error = state.error,
            onConfirm = { if (state.saveProfile()) { page = leaveTo!!; leaveTo = null } },
            alternative = "放弃修改", onAlternative = { state.discard(); page = leaveTo!!; leaveTo = null },
        )
    }
}

@Composable
private fun HomePage(state: SettingsState, navigate: (SettingsPage) -> Unit) {
    var confirmGlobal by remember { mutableStateOf(false) }
    Group {
        SwitchPreference(
            title = "启用控屏工具", summary = "让小爱完成手机上的操作",
            checked = state.config.enabled, onCheckedChange = { state.setOption("enabled", it) },
        )
    }
    Hint("更改启用状态后，重启小爱生效。")
    Section("模型") {
        ArrowPreference(title = "模型服务", summary = state.profiles?.active()?.let {
            if (it.endpoint.isBlank()) "尚未配置 · 使用小爱无需填写" else "${it.name} · ${it.model.ifBlank { "未选择模型" }}"
        } ?: "配置读取失败", onClick = { navigate(SettingsPage.Model) })
        OverlayDropdownPreference(
            title = "控屏规划", summary = "由哪个模型决定下一步操作",
            items = listOf("小爱当前模型", "独立外部模型"), selectedIndex = if (state.config.planner == "external") 1 else 0,
            onSelectedIndexChange = { state.setOption("planner", if (it == 1) "external" else "native") },
        )
        SwitchPreference(title = "外部模型对话", summary = "替换小爱的对话与工具规划模型",
            checked = state.config.globalModel, onCheckedChange = {
                if (it) confirmGlobal = true else state.setOption("globalModel", false)
            })
    }
    Hint(if (state.config.globalModel) "对话和工具上下文将发送至当前模型服务。更改后重启小爱。"
        else "默认使用小爱当前模型，无需额外 API。")
    Section("更多设置") {
        ArrowPreference(title = "控屏设置", summary = "执行方式、动作协议与步数", onClick = { navigate(SettingsPage.Control) })
        ArrowPreference(title = "运行状态", summary = "模块连接、权限与测试", onClick = { navigate(SettingsPage.Status) })
    }
    Hint("MiAIPlus  ${BuildConfig.VERSION_NAME}", centered = true)
    ConfirmDialog(show = confirmGlobal, title = "使用外部模型对话？",
        summary = "小爱的聊天历史、系统提示和工具上下文会发送至当前模型服务。语音仍由系统提供。",
        confirm = "开启", onDismiss = { confirmGlobal = false },
        onConfirm = { state.setOption("globalModel", true); confirmGlobal = false })
}

@Composable
private fun ModelPage(state: SettingsState) {
    val draft = state.draft ?: run { Hint("配置读取失败。请保留应用数据，重新打开设置。"); return }
    val profiles = state.profiles ?: return
    var switchTo by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var revealKey by remember(draft.id) { mutableStateOf(false) }
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
    Hint("配置包含地址、密钥、模型和 API 协议，切换后用于新请求。")
    Section("连接信息") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Input("配置名称", draft.name, { state.updateProfile(name = it) })
            Input("服务地址", draft.endpoint, { state.updateProfile(endpoint = it) }, type = KeyboardType.Uri)
            Text("填写 Base URL，保留服务要求的路径，如 /v1。", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp))
            TextField(value = draft.apiKey, onValueChange = { state.updateProfile(key = it) }, label = "API 密钥（可留空）",
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation())
        }
        SwitchPreference(title = "显示密钥", checked = revealKey, onCheckedChange = { revealKey = it })
    }
    Section("模型") {
        Column(Modifier.padding(16.dp)) {
            Input("模型名称", draft.model, { state.updateProfile(model = it) })
        }
        ArrowPreference(title = if (state.fetching) "正在获取模型…" else "从服务选择模型",
            summary = "控屏需支持视觉和工具调用", enabled = !state.fetching && draft.endpoint.isNotBlank(),
            onClick = { state.fetchModels() })
        OverlayDropdownPreference(title = "API 协议", items = listOf("自动", "Responses", "Chat Completions"),
            selectedIndex = listOf("auto", "responses", "chat").indexOf(draft.api).coerceAtLeast(0),
            onSelectedIndexChange = { state.updateProfile(api = listOf("auto", "responses", "chat")[it]) })
    }
    Hint("自动模式为 GPT-6 系列选择 Responses。使用模型别名时，请按服务要求选择。")
    Section("管理配置") {
        ArrowPreference(title = "另存为新配置", summary = "保留当前填写的连接信息", onClick = { newName = ""; showNew = true })
        ArrowPreference(title = "删除当前配置", enabled = profiles.profiles.size > 1,
            summary = if (profiles.profiles.size == 1) "至少保留一份配置" else "同时删除此配置的密钥",
            onClick = { showDelete = true })
    }
    Hint("密钥仅保存在本机应用私有存储中。")
    ConfirmDialog(show = switchTo != null, title = "切换前保存修改？", summary = "当前配置有未保存的内容。",
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
        summary = "此配置及密钥将被删除，并切换至另一份配置。未保存的修改也会丢弃。",
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
private fun ControlPage(state: SettingsState, openSettings: (Boolean) -> Unit) {
    var editSteps by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf("") }
    Section("执行") {
        OverlayDropdownPreference(title = "动作协议", items = listOf("Phone Use", "AutoGLM"),
            summary = "按模型支持的格式选择", selectedIndex = if (state.config.protocol == "autoglm") 1 else 0,
            onSelectedIndexChange = { state.setOption("protocol", if (it == 1) "autoglm" else "phone_use") })
        ArrowPreference(title = "最大步数", summary = "每次任务最多执行 ${state.config.maxSteps} 步",
            onClick = { steps = state.config.maxSteps.toString(); editSteps = true })
        SwitchPreference(title = "后台屏幕执行", summary = "在独立屏幕上运行控屏任务",
            checked = state.config.backgroundDisplay, onCheckedChange = { state.setOption("backgroundDisplay", it) })
    }
    Hint("后台屏幕需要 root，且手机须保持解锁。不支持时会停止任务，不会转到主屏。")
    Section("权限") {
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "未连接 · 前往开启",
            onClick = { openSettings(false) })
        SwitchPreference(title = "任务前检查无障碍", summary = if (state.guardChanging) "正在应用…" else "连接失效时尝试用 root 恢复",
            checked = state.guardEnabled, enabled = !state.guardChanging, onCheckedChange = { state.setGuard(it) })
        ArrowPreference(title = "应用权限", summary = "后台弹出界面等系统权限", onClick = { openSettings(true) })
    }
    Hint("无障碍仅在任务开始前检查。其他权限提示仍需手动处理。")
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
private fun StatusPage(state: SettingsState, openSettings: (Boolean) -> Unit, openTest: () -> Unit) {
    Section("模块") {
        BasicComponent(title = "小爱连接", summary = state.hookReport)
        BasicComponent(title = "框架与热重载", summary = state.reloadStatus)
        ArrowPreference(title = "重载模块", summary = "安装更新后使用，任务执行中不可重载", onClick = { state.reload() })
    }
    Hint("在 LSPosed 中启用 MiAIPlus，作用域仅选择超级小爱，然后重启小爱。")
    Section("权限") {
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "未连接或已失效", onClick = { openSettings(false) })
        BasicComponent(title = "任务前检查", summary = state.guardStatus)
    }
    Section("诊断") {
        ArrowPreference(title = if (state.checkingBackground) "正在检查后台屏…" else "检查后台屏可用性",
            summary = "检查 root 与锁屏状态，不创建屏幕", enabled = !state.checkingBackground,
            onClick = { state.checkBackgroundDisplay() })
        state.backgroundCheck?.let { BasicComponent(title = it.title, summary = it.summary) }
        ArrowPreference(title = "直接测试", summary = "测试外部模型对话或控屏", onClick = openTest)
    }
}

@Composable
private fun TestPage(state: SettingsState, onScreenTask: () -> Unit) {
    Group {
        OverlayDropdownPreference(title = "测试类型", items = listOf("识屏控屏", "纯对话"), selectedIndex = state.testMode,
            enabled = state.job == null && !state.starting, onSelectedIndexChange = { state.testMode = it })
    }
    Hint(if (state.testMode == 0) "使用已保存的外部模型配置。使用小爱当前模型时，请直接向小爱说出任务。"
        else "使用已保存的模型服务，不读取屏幕。")
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
