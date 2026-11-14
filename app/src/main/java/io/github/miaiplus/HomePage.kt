package io.github.miaiplus

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomePage(state: SettingsState, nav: Navigation) {
    var editTimeout by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf("") }
    StatusHero(state)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardTile("版本", BuildConfig.VERSION_NAME, Modifier.weight(1f))
        DashboardTile("无障碍", if (state.accessible) "已连接" else "未连接", Modifier.weight(1f))
    }
    Section("功能") {
        ArrowPreference(title = "插件", summary = state.plugins.summary, onClick = { nav.open(Route.Plugins) })
        ArrowPreference(title = "控屏", summary = controlSummary(state), onClick = { nav.open(Route.Control) })
    }
    Section("对话") {
        val speechPolicies = listOf(SpeechReplyPolicy.TEXT_ONLY, SpeechReplyPolicy.FOLLOW_INPUT, SpeechReplyPolicy.HOST_DEFAULT)
        OverlayDropdownPreference(title = "语音回复", items = listOf("仅文字", "跟随输入", "小爱默认"),
            selectedIndex = speechPolicies.indexOf(state.config.speechReplyPolicy).coerceAtLeast(0),
            summary = when (state.config.speechReplyPolicy) {
                SpeechReplyPolicy.TEXT_ONLY -> "所有回复均不自动朗读"
                SpeechReplyPolicy.FOLLOW_INPUT -> "语音输入时朗读，文字与手势识屏保持静音"
                else -> "不更改小爱的回复策略"
            },
            onSelectedIndexChange = { state.setOption(SpeechReplyPolicy.KEY, speechPolicies[it]) })
        ArrowPreference(title = "自动新建对话", summary = if (state.config.conversationTimeoutMinutes == 0)
            "已关闭" else "闲置 ${state.config.conversationTimeoutMinutes} 分钟后",
            onClick = {
                timeout = state.config.conversationTimeoutMinutes.toString()
                state.error = null
                editTimeout = true
            })
        ArrowPreference(title = "系统提示词", summary = promptSummary(state), onClick = { nav.open(Route.Prompt) })
    }
    Section("诊断") {
        ArrowPreference(title = "运行状态", summary = "连接、权限与直接测试", onClick = { nav.open(Route.Status) })
        ArrowPreference(title = "日志", onClick = { nav.open(Route.Logs) })
    }
    NumberDialog(show = editTimeout, title = "自动新建对话",
        summary = "闲置超时后，下次打开小爱会新建对话。填 0 关闭。",
        label = "时长（分钟）", value = timeout, onValueChange = { timeout = it }, error = state.error,
        onSave = {
            val value = timeout.toIntOrNull()
            if (value == null) state.error = "请输入 0–${Config.MAX_CONVERSATION_TIMEOUT_MINUTES} 之间的整数"
            else if (state.setConversationTimeoutMinutes(value)) editTimeout = false
        },
        onDismiss = { editTimeout = false; state.error = null })
}

/** Reads the saved snapshot, never the in-progress draft. */
private fun promptSummary(state: SettingsState): String {
    val saved = state.personaSaved ?: return "已关闭"
    val name = saved.name.ifBlank { "默认人设" }
    return when (saved.mode) {
        PromptKeys.MODE_APPEND -> "追加人设 · $name"
        PromptKeys.MODE_REPLACE -> "替换提示词 · $name"
        else -> "已关闭"
    }
}

private fun controlSummary(state: SettingsState): String {
    if (!state.config.enabled) return "已关闭"
    val planner = if (state.config.planner == "external")
        state.profiles?.active()?.model?.ifBlank { "外部模型未选择" } ?: "外部模型未配置" else "小爱当前模型"
    return "已开启 · $planner"
}

/** Saved injection report. Labelled as the last report, never as a live connection. */
@Composable
private fun StatusHero(state: SettingsState) {
    val reported = state.hookRecordedAt > 0L
    val ready = reported && state.hookReport.startsWith("ready:")
    val title = when { !reported -> "暂无注入报告"; ready -> "工作正常"; else -> "上次注入异常" }
    val detail = if (reported) "上次报告 " + DateUtils.formatDateTime(LocalContext.current, state.hookRecordedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) else "启用模块后重启小爱"
    val iconTint = when {
        ready -> Color.White
        reported -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (ready) ReadyGreen else MiuixTheme.colorScheme.surfaceContainer,
            contentColor = if (ready) Color.White else MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SettingsIcons.Module, null, Modifier.size(48.dp), tint = iconTint)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MiuixTheme.textStyles.title3)
                Text(detail, style = MiuixTheme.textStyles.footnote2)
            }
        }
    }
}

private val ReadyGreen = Color(0xFF34C759)

@Composable
private fun DashboardTile(label: String, value: String, modifier: Modifier) {
    Card(modifier.fillMaxHeight()) {
        BasicComponent(title = label, summary = value)
    }
}
