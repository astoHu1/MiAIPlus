package io.github.miaiplus

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

/** Every page of the settings app. Tabs are stack roots; the rest can be pushed from any page. */
internal enum class Route(val title: String) {
    Home("MiAIPlus"), Model("模型 API"), Plugins("插件"),
    Control("控屏配置"), Virtual("虚拟屏"), Termux("Termux 配置"), Mcp("MCP 插件"),
    Prompt("系统提示词"),
    Status("运行状态"), Test("直接测试"), Logs("日志");

    val isTab: Boolean get() = this == Home || this == Model || this == Plugins
}

private val Tabs = listOf(Route.Home, Route.Model, Route.Plugins)

/** One stack entry on screen. Equal frames are the same page, so the transition can key on them. */
private data class Frame(val tab: Route, val route: Route, val depth: Int)

private val StackSaver = listSaver<List<Route>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map(Route::valueOf) },
)

@Composable
internal fun SettingsApp(state: SettingsState, openSettings: (Boolean) -> Unit, onScreenTask: () -> Unit) {
    // A single back stack whose root is the selected tab. Back always returns to the page the
    // user came from; tapping a tab returns to that tab's root.
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf(Route.Home)) }
    var pending by remember { mutableStateOf<List<Route>?>(null) }
    var mcpId by rememberSaveable { mutableStateOf<String?>(null) }
    var followLogs by rememberSaveable { mutableStateOf(true) }
    val page = stack.last()
    val savedPages = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.refresh() }
    }
    LaunchedEffect(lifecycle, page == Route.Status) {
        if (page == Route.Status) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.refreshLight()
            while (true) { delay(3500); state.refreshLight() }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.notice = null }
    }
    LaunchedEffect(state.plugins.notice) {
        state.plugins.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); state.plugins.notice = null }
    }

    fun go(next: List<Route>) {
        if (next == stack) return
        // Pages with an explicit-save draft ask before leaving.
        if (page == Route.Model && state.dirty) pending = next
        else { state.error = null; stack = next }
    }
    fun switchTab(tab: Route) = go(listOf(tab))
    fun push(route: Route) { if (route.isTab) switchTab(route) else if (page != route) go(stack + route) }
    fun back() = go(if (stack.size > 1) stack.dropLast(1) else listOf(Route.Home))
    fun openMcp(id: String) { mcpId = id; push(Route.Mcp) }

    BackHandler(stack.size > 1 || stack.first() != Route.Home) { back() }

    val nav = Navigation(::push, ::back, ::openMcp)
    val scrollBehavior = key(page) { MiuixScrollBehavior() }
    // Plugin labels may be 80 characters; the large title wraps without a line limit.
    val title = if (page == Route.Mcp) state.plugins.entries.firstOrNull { it.id() == mcpId }?.label()
        ?.let { if (it.length > 14) it.take(13) + "…" else it } ?: page.title else page.title
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (stack.size > 1) IconButton(onClick = ::back, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Basic.ArrowRight, "返回", Modifier.rotate(180f))
                    }
                },
                actions = {
                    if (page == Route.Logs) DiagnosticLogActions(state, followLogs) { followLogs = it }
                },
            )
        },
        bottomBar = {
            if (page != Route.Logs) Column(Modifier.imePadding()) {
                if (page == Route.Model && state.dirty) UnsavedBar({ state.discard() }, { state.saveProfile() })
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = stack.first() == tab, onClick = { switchTab(tab) },
                            icon = when (tab) {
                                Route.Home -> SettingsIcons.Home
                                Route.Model -> SettingsIcons.Model
                                else -> SettingsIcons.Plugins
                            },
                            label = if (tab == Route.Home) "首页" else tab.title,
                            modifier = Modifier.testTag("tab-${tab.name}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val frame = Frame(stack.first(), page, stack.size)
        PageTransition(frame, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { shown ->
            // Each MCP plugin page keeps its own scroll position.
            val owner = if (shown.route == Route.Mcp) "/$mcpId" else ""
            savedPages.SaveableStateProvider("${shown.tab.name}/${shown.route.name}$owner") {
                PageBody(shown.route, state, nav, mcpId, followLogs, openSettings, onScreenTask)
            }
        }
        ConfirmDialog(
            show = pending != null,
            title = "保存当前配置？", summary = null,
            confirm = "保存并离开", onDismiss = { pending = null }, error = state.error,
            onConfirm = {
                if (state.saveProfile()) { stack = pending!!; pending = null }
            },
            alternative = "放弃修改", onAlternative = {
                state.discard()
                stack = pending!!; pending = null
            },
        )
        // The MCP editor opens from the plugin list (add) and the plugin page (edit).
        McpPluginEditor(state.plugins, onCreated = ::openMcp)
    }
}

/** Navigation callbacks handed to pages so they never touch the back stack directly. */
internal class Navigation(val open: (Route) -> Unit, val back: () -> Unit, val openMcp: (String) -> Unit)

@Composable
private fun PageBody(route: Route, state: SettingsState, nav: Navigation, mcpId: String?, followLogs: Boolean,
                     openSettings: (Boolean) -> Unit, onScreenTask: () -> Unit) {
    // Lazy pages own their scrolling; everything else scrolls as one column.
    val ownScroll = route == Route.Model || route == Route.Prompt || route == Route.Logs
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth()
                .then(if (ownScroll) Modifier else Modifier.verticalScroll(scroll)).imePadding()
                .padding(top = 8.dp, bottom = if (route == Route.Model || route == Route.Prompt) 0.dp else 24.dp),
        ) {
            if (route != Route.Model && route != Route.Prompt) state.error?.let { ErrorGroup(it) }
            when (route) {
                Route.Home -> HomePage(state, nav)
                Route.Model -> ModelPage(state)
                Route.Prompt -> PromptPage(state)
                Route.Plugins -> PluginPage(state.plugins, state.config.enabled, state.config.planner == "native", nav)
                Route.Control -> ControlPage(state, openSettings, nav)
                Route.Virtual -> VirtualScreenPage(state)
                Route.Termux -> TermuxSettingsPage(state.plugins)
                Route.Mcp -> McpPluginPage(state.plugins, mcpId, nav)
                Route.Status -> StatusPage(state, openSettings, nav)
                Route.Test -> TestPage(state, onScreenTask)
                Route.Logs -> DiagnosticLogPage(state, followLogs)
            }
        }
    }
}

@Composable
private fun UnsavedBar(discard: () -> Unit, save: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton("还原", discard, Modifier.weight(1f))
        PrimaryButton("保存配置", save, Modifier.weight(2f))
    }
}

/**
 * Tab switches fade and slide in only the new page; pushes and pops keep the outgoing page
 * for a short draw-layer slide. Only alpha and translation animate, never layout.
 */
@Composable
private fun PageTransition(frame: Frame, modifier: Modifier, content: @Composable (Frame) -> Unit) {
    val transition = updateTransition(frame, label = "page")
    val tabSwitch = transition.targetState.tab != transition.currentState.tab
    val tabEntry = remember(frame) { Animatable(if (tabSwitch) 0f else 1f) }
    val tabDirection = remember(frame) {
        if (Tabs.indexOf(frame.tab) > Tabs.indexOf(transition.currentState.tab)) 1f else -1f
    }
    LaunchedEffect(tabEntry) {
        if (tabEntry.value < 1f) tabEntry.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    val forward = transition.targetState.depth >= transition.currentState.depth
    val distance = with(LocalDensity.current) { 40.dp.toPx() }
    val tabDistance = with(LocalDensity.current) { 24.dp.toPx() }
    val frames = if (tabSwitch) listOf(frame)
        else listOf(transition.currentState, transition.targetState).distinct()
    Box(modifier.clipToBounds()) {
        frames.forEach { shown ->
            key(shown) {
                val visibility = transition.animateFloat(transitionSpec = {
                    if (tabSwitch) snap() else tween(240, easing = FastOutSlowInEasing)
                }, label = "page-${shown.route.name}") { if (it == shown) 1f else 0f }
                val incoming = shown == transition.targetState
                Box(Modifier.fillMaxSize()
                    .graphicsLayer {
                        val secondaryVisibility = if (tabSwitch) 1f else visibility.value
                        val entry = if (incoming) tabEntry.value else 1f
                        alpha = secondaryVisibility * entry
                        translationX = (1f - secondaryVisibility) * distance *
                            (if (incoming) { if (forward) 1f else -0.3f } else { if (forward) -0.3f else 1f })
                        if (incoming) translationX += (1f - entry) * tabDistance * tabDirection
                    }
                    .testTag("page-${shown.route.name}")
                    .then(if (!incoming) Modifier.clearAndSetSemantics { hideFromAccessibility() }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        } else Modifier)) {
                    content(shown)
                }
            }
        }
    }
}
