package io.github.miaiplus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** Live diagnostics. Polled lightly only while this page is visible. */
@Composable
internal fun StatusPage(state: SettingsState, openSettings: (Boolean) -> Unit, nav: Navigation) {
    Section("模块") {
        BasicComponent(title = "小爱连接", summary = state.hookReport)
        BasicComponent(title = "框架与热重载", summary = state.reloadStatus)
        ActionRow(title = "重载模块", summary = "任务结束后可用", onClick = { state.reload() })
    }
    Section("权限") {
        ArrowPreference(title = "无障碍服务", summary = if (state.accessible) "已连接" else "失效或未连接",
            onClick = { openSettings(false) })
        BasicComponent(title = "任务前检查", summary = state.guardStatus)
    }
    Section("测试") {
        ArrowPreference(title = "直接测试", summary = "用已保存的外部模型执行一次", onClick = { nav.open(Route.Test) })
        ArrowPreference(title = "虚拟屏", summary = "画布与能力检测", onClick = { nav.open(Route.Virtual) })
    }
}

/** One-off task through the saved external model. Physical-screen tasks yield the settings window. */
@Composable
internal fun TestPage(state: SettingsState, onScreenTask: () -> Unit) {
    val idle = state.job == null && !state.starting
    Group {
        OverlayDropdownPreference(title = "测试类型", items = listOf("识屏控屏", "纯对话"), selectedIndex = state.testMode,
            enabled = idle, onSelectedIndexChange = { state.testMode = it })
    }
    Spacer(Modifier.height(12.dp))
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(value = state.query, onValueChange = { state.query = it },
                label = if (state.testMode == 0) "想在手机上做什么？" else "输入问题",
                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = idle)
            if (state.job == null) PrimaryButton(if (state.starting) "正在启动…" else "开始测试", { state.startTest(onScreenTask) },
                Modifier.fillMaxWidth(), enabled = state.query.isNotBlank() && !state.starting)
            else TextButton("停止任务", { state.stopTest() }, Modifier.fillMaxWidth())
        }
    }
    if (state.result.isNotBlank()) Section(if (state.job == null) "结果" else "执行中") {
        SelectionContainer { Text(state.result, modifier = Modifier.padding(20.dp)) }
    }
}
