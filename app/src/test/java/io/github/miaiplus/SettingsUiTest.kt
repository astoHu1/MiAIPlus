package io.github.miaiplus

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeDefaultsAndSettingsNavigation() {
        compose.onNodeWithText("启用控屏工具").assertExists()
        compose.onNodeWithText("小爱当前模型").assertExists()
        capture("settings-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        capture("settings-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("控屏设置").performScrollTo().performClick()
        compose.onNodeWithText("动作协议").assertExists()
        compose.onNodeWithText("每次任务最多执行 30 步").assertExists()
        capture("control-light")
        compose.onNodeWithText("最大步数").performClick()
        compose.onNodeWithText("步数").performTextReplacement("0")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("步数需在 1–80 之间").assertAny(hasText("步数需在 1–80 之间"))
    }

    @Test fun profileDraftRequiresExplicitSaveAndCanBeDiscarded() {
        compose.onNodeWithText("模型服务").performClick()
        capture("model-light")
        compose.onNodeWithText("配置名称").performTextReplacement("测试服务")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and hasText("测试服务")).assertExists()
        capture("model-editing-light")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("保存当前配置？").assertExists()
        capture("unsaved-profile-light")
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("模型服务").performClick()
        compose.onNode(hasSetTextAction() and hasText("默认配置")).assertExists()
        compose.onNodeWithText("保存配置").assertDoesNotExist()
    }

    @Test fun missingModuleReportsAnUnconnectedState() {
        compose.onNodeWithText("运行状态").performScrollTo().performClick()
        compose.onNodeWithText("暂无报告，请在 LSPosed 中启用模块后重启小爱").assertExists()
        capture("status-light")
        compose.onNodeWithText("直接测试").performScrollTo().performClick()
        compose.onNodeWithText("想在手机上做什么？").assertExists()
        capture("test-light")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("小爱连接").assertExists()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
