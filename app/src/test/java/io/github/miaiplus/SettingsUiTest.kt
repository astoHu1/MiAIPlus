package io.github.miaiplus

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearFixturePreferences() {
        // AndroidViewModel's factory can retain its Application between Robolectric cases.
        // Clean the same preferences owner used by the UI, including after a failed test.
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        io.github.miaiplus.Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun nativeDefaultsAndSettingsNavigation() {
        compose.onNodeWithText("暂无注入报告").assertExists()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNodeWithTag("tab-Model").assertIsNotSelected()
        compose.onNodeWithTag("tab-Control").assertIsNotSelected()
        compose.onNodeWithText("启用控屏工具").assertDoesNotExist()
        compose.onNodeWithText("API 协议").assertDoesNotExist()
        val tabs = listOf("Home", "Model", "Control").map { compose.onNodeWithTag("tab-$it").getBoundsInRoot().left }
        assert(tabs.zipWithNext().all { (left, right) -> left < right })
        capture("settings-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        capture("settings-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-Control").performClick()
        compose.onNodeWithText("启用控屏工具").assertExists()
        compose.onNodeWithText("小爱当前模型").assertExists()
        compose.onNodeWithText("动作协议").assertExists()
        compose.onNodeWithText("30 步").assertExists()
        capture("control-light")
        compose.onNodeWithText("最大步数").performClick()
        compose.onNodeWithText("步数").performTextReplacement("0")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("步数需在 1–80 之间").assertAny(hasText("步数需在 1–80 之间"))
    }

    @Test fun profileDraftRequiresExplicitSaveAndCanBeDiscarded() {
        compose.onNodeWithTag("tab-Model").performClick()
        capture("model-light")
        compose.onNodeWithText("配置名称").performTextReplacement("测试服务")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and hasText("测试服务")).assertExists()
        capture("model-editing-light")
        compose.onNodeWithTag("tab-Home").performClick()
        compose.onNodeWithText("保存当前配置？").assertExists()
        compose.onNodeWithTag("tab-Model").assertIsSelected()
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("tab-Model").assertIsSelected()
        compose.onNodeWithTag("tab-Home").performClick()
        capture("unsaved-profile-light")
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNodeWithTag("tab-Model").performClick()
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

    @Test fun secondaryPagesAnimateBothWaysAndReturnToTheirParent() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("运行状态").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("page-Home").assertExists()
        compose.onNodeWithTag("page-Status").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("page-Home").assertDoesNotExist()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("直接测试").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("直接测试").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("page-Test").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("page-Test").assertExists()
        compose.onNodeWithTag("page-Status").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("page-Test").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("page-Home").assertExists()
        compose.onNodeWithTag("page-Status").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun savingBeforeSwitchingTabsUsesTheRequestedDestination() {
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNodeWithText("配置名称").performTextReplacement("我的 API")
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            assertEquals("我的 API", state.draft?.name)
        }
        compose.onNodeWithTag("tab-Control").performClick()
        compose.onNodeWithText("保存并离开").performClick()
        compose.onNodeWithTag("tab-Control").assertIsSelected()
        compose.runOnIdle {
            assertEquals("我的 API", EndpointProfiles(io.github.miaiplus.Config.prefs(state.getApplication<Application>())).active().name)
        }
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNode(hasSetTextAction() and hasText("我的 API")).assertExists()
        compose.onNodeWithText("保存配置").assertDoesNotExist()
    }

    @Test fun dashboardLabelsSavedReportsWithoutClaimingALiveConnection() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            io.github.miaiplus.Config.prefs(state.getApplication<Application>()).edit()
                .putString("hook_report", "ready: fixture")
                .putLong("hook_at", 1_700_000_000_000L).commit()
            state.refresh()
        }
        compose.onNodeWithText("上次注入成功").assertExists()
        capture("home-ready-light")
        compose.runOnIdle {
            io.github.miaiplus.Config.prefs(state.getApplication<Application>()).edit()
                .putString("hook_report", "unsupported: fixture").commit()
            state.refresh()
        }
        compose.onNodeWithText("上次注入异常").assertExists()
    }

    @Test fun backgroundCheckIsExplicitAndSurvivesStatusRefresh() {
        val calls = AtomicInteger()
        val provider = BackgroundStatusProvider(calls).apply {
            attachInfo(compose.activity, ProviderInfo().apply { authority = "io.github.miaiplus.bridge" })
        }
        ShadowContentResolver.registerProviderInternal("io.github.miaiplus.bridge", provider)
        compose.onNodeWithText("运行状态").performScrollTo().performClick()
        assertEquals(0, calls.get())
        compose.onNodeWithText("检查后台屏可用性").performScrollTo().performClick()
        compose.waitUntil { calls.get() == 1 }
        compose.onNodeWithText("前置条件已满足").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("前置条件已满足").assertExists()
        assertEquals(1, calls.get())
        capture("background-check-light")
    }

    @Test fun largeModelListOnlyComposesVisibleItemsAndKeepsSelectionAsDraft() {
        compose.onNodeWithTag("tab-Model").performClick()
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle { state.modelList = List(1000) { "model-$it" } }
        compose.onNodeWithText("model-0").assertExists()
        compose.onNodeWithText("model-999").assertDoesNotExist()
        compose.onNodeWithText("model-0").performClick()
        compose.runOnIdle {
            assertEquals("model-0", state.draft?.model)
            assertEquals("", Config(compose.activity).model)
            assertEquals(null, state.modelList)
        }
    }

    @Test fun backgroundCheckDistinguishesFailuresFromReadyPrerequisites() {
        assertEquals("检查失败", BackgroundCheck.from(null).title)
        assertEquals("未取得 root 授权", BackgroundCheck.from(Bundle()).title)
        assertEquals("手机已锁定", BackgroundCheck.from(Bundle().apply {
            putBoolean("root", true); putBoolean("deviceLocked", true)
        }).title)
        assertEquals("前置条件已满足", BackgroundCheck.from(Bundle().apply { putBoolean("root", true) }).title)
        assertEquals("BACKEND_DIED：后台显示无响应", BackgroundCheck.from(Bundle().apply {
            putString("error", "BACKEND_DIED：后台显示无响应")
        }).summary)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

/** IPC fixture: no root process, display creation, or real device interaction. */
private class BackgroundStatusProvider(private val calls: AtomicInteger) : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(method == "background_display_status") { "Unexpected IPC: $method" }
        calls.incrementAndGet()
        return Bundle().apply { putBoolean("root", true); putBoolean("deviceLocked", false) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
