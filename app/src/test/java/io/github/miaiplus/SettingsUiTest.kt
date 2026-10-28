package io.github.miaiplus

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import org.robolectric.Shadows.shadowOf
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
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

    @Test fun homeLogsShowsFiltersDetailsAndClearConfirmation() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val store = DiagnosticLogStore.get(state.getApplication<Application>())
        store.clear()
        store.append("""{"event":"tree_empty","source":"module","time":1792272000123,"session":"7d62b401","step":3,"reason":"no_windows","displayId":2}""")
        store.append("""{"event":"image_attached","source":"host","time":1792272000250,"session":"7d62b401","step":3,"images":1}""")
        compose.onNodeWithText("日志", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); state.logs.entries.size == 2 }
        capture("home-detailed-logs")
        compose.onNodeWithTag("tab-Home").assertDoesNotExist()
        compose.onNodeWithContentDescription("分享").assertExists()
        compose.onNodeWithContentDescription("保存压缩包").assertExists()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("详细日志", substring = false).performClick()
        compose.runOnIdle { assertEquals(true, state.config.phoneUseDetailedLog) }
        compose.onNodeWithText("正在记录").assertExists()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("详细日志", substring = false).performClick()
        compose.runOnIdle { assertEquals(false, state.config.phoneUseDetailedLog) }
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.waitForIdle(); state.logs.entries.size == 2 }
        capture("home-detailed-logs-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("tree_empty").performScrollTo().performClick()
        compose.onNodeWithText("no_windows", substring = true).assertExists()
        capture("home-detailed-logs-details")
        compose.onAllNodesWithText("模块", substring = false).onFirst().performClick()
        compose.onNodeWithText("image_attached").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("清空", substring = false).performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.runOnIdle { assertEquals(2, store.snapshot().lines().size) }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("清空", substring = false).performClick()
        compose.onNodeWithText("清空日志", substring = false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); state.logs.entries.isEmpty() && !state.logs.busy }
        assertEquals(0, store.snapshot().lines().size)
    }

    @Test fun detailedLogSwitchPersistsAndCanBeDisabledWithoutChangingExecutionSettings() {
        var state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val background = state.config.backgroundDisplay
        val permission = state.config.phoneUsePermissionMode
        openControlPage()
        compose.onNodeWithText("Phone Use 详细日志").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, state.config.phoneUseDetailedLog) }
        compose.activityRule.scenario.recreate()
        state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openControlPage()
        compose.onNodeWithText("Phone Use 详细日志").performScrollTo()
        compose.runOnIdle {
            assertEquals(true, state.config.phoneUseDetailedLog)
            assertEquals(background, state.config.backgroundDisplay)
            assertEquals(permission, state.config.phoneUsePermissionMode)
        }
        capture("control-phone-use-log")
        compose.onNodeWithText("Phone Use 详细日志").performClick()
        compose.runOnIdle {
            assertEquals(false, state.config.phoneUseDetailedLog)
            assertEquals(false, io.github.miaiplus.Config(state.getApplication<Application>()).phoneUseDetailedLog)
        }
    }

    @Test fun permissionPresetsSaveAllModesSurviveRecreationAndRejectInvalidValues() {
        var state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openControlPage()
        compose.onNodeWithText("操作确认").performScrollTo()
        compose.onNodeWithText("手动确认").assertExists()
        compose.runOnIdle { assertEquals("manual", state.config.phoneUsePermissionMode) }
        for ((label, mode) in listOf("智能判断" to "auto", "自动执行" to "yolo", "手动确认" to "manual")) {
            compose.onNodeWithText("操作确认").performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle {
                assertEquals(mode, state.config.phoneUsePermissionMode)
                // Read the same Application-owned store used by SettingsState. AndroidViewModel's
                // cached factory can retain an Application from another Robolectric sandbox.
                val owner = state.getApplication<Application>()
                assertEquals(mode, io.github.miaiplus.Config.prefs(owner)
                    .getString(io.github.miaiplus.Config.PHONE_USE_PERMISSION_MODE_KEY, null))
                assertEquals(mode, io.github.miaiplus.Config(owner).phoneUsePermissionMode)
            }
        }
        compose.runOnIdle {
            assertEquals(false, state.setPhoneUsePermissionMode("unknown"))
            assertEquals(false, state.setOption(io.github.miaiplus.Config.PHONE_USE_PERMISSION_MODE_KEY, true))
            assertEquals("manual", io.github.miaiplus.Config(state.getApplication<Application>()).phoneUsePermissionMode)
            check(state.setPhoneUsePermissionMode("auto"))
        }
        compose.activityRule.scenario.recreate()
        state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openControlPage()
        compose.onNodeWithText("操作确认").performScrollTo()
        compose.onNodeWithText("智能判断").assertExists()
        compose.onNodeWithText("由模型判断，必要时询问").assertExists()
        compose.runOnIdle {
            assertEquals("auto", state.config.phoneUsePermissionMode)
            assertEquals("auto", io.github.miaiplus.Config(state.getApplication<Application>()).phoneUsePermissionMode)
        }
        capture("control-phone-use-permission")
    }

    @Test fun globalExternalModelForcesAndLocksNativePlanner() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.updateProfile(endpoint = "https://example.com/v1", model = "fixture-model")
            check(state.saveProfile())
            check(state.setOption("planner", "external"))
            check(state.setOption("globalModel", true))
            assertEquals("native", state.config.planner)
            assertEquals("native", io.github.miaiplus.Config.prefs(state.getApplication<Application>()).getString("planner", null))
            assertEquals(false, state.setOption("planner", "external"))
        }
        openControlPage()
        compose.onNodeWithText("规划模型").assertIsNotEnabled()
        compose.onNodeWithText("小爱当前模型").assertExists()
        capture("control-global-model")
        compose.runOnIdle {
            check(state.setOption("globalModel", false))
            assertEquals("native", state.config.planner)
        }
        compose.onNodeWithText("规划模型").assertIsEnabled().performClick()
        compose.onNodeWithText("外部模型").performClick()
        compose.runOnIdle { assertEquals("external", state.config.planner) }
    }

    @Test fun protocolSelectorKeepsBackgroundBrightnessAndSavesSelectionAsDraft() {
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNodeWithText("API 协议").performScrollTo()
        compose.waitForIdle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.onNodeWithText("API 协议").performClick()
        compose.onNodeWithText("Chat Completions").assertExists()
        capture("protocol-popup-no-dim")
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertEquals(before.getPixel(2, before.height / 2), after.getPixel(2, after.height / 2))
        } finally { before.recycle(); after.recycle() }
        compose.onNodeWithText("Chat Completions").performClick()
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            assertEquals("chat", state.draft?.api)
            assertEquals("auto", state.profiles?.active()?.api)
        }
    }

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
        compose.onNodeWithTag("tab-Plugins").assertIsNotSelected()
        compose.onNodeWithText("启用控屏工具").assertDoesNotExist()
        compose.onNodeWithText("API 协议").assertDoesNotExist()
        val tabs = listOf("Home", "Model", "Plugins").map { compose.onNodeWithTag("tab-$it").getBoundsInRoot().left }
        assert(tabs.zipWithNext().all { (left, right) -> left < right })
        capture("settings-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        capture("settings-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()
        openControlPage()
        compose.onNodeWithText("启用控屏工具").assertExists()
        compose.onNodeWithText("小爱当前模型").assertExists()
        compose.onNodeWithText("动作协议").assertExists()
        compose.onNodeWithText("30 步").assertExists()
        capture("control-light")
        compose.onNodeWithText("最大步数").performClick()
        compose.onNodeWithText("步数").performTextReplacement("0")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("步数需在 1–200 之间").assertAny(hasText("步数需在 1–200 之间"))
        compose.onNodeWithText("步数").performTextReplacement("201")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("步数需在 1–200 之间").assertAny(hasText("步数需在 1–200 之间"))
        compose.onNodeWithText("步数").performTextReplacement("200")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("200 步").assertExists()
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        assertEquals(200, state.config.maxSteps)
    }

    @Test fun pluginsTabOwnsControlAndVirtualPagesAndReturnsThroughParents() {
        compose.onNodeWithTag("tab-Control").assertDoesNotExist()
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithTag("page-Plugins").assertExists()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithTag("add-mcp-plugin").assertExists()
        capture("plugins-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        capture("plugins-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithText("控屏配置").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("page-Control").assertExists()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("page-Virtual").assertExists()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("page-Control").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("page-Plugins").assertExists()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("page-Home").assertExists()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNode(hasText("插件") and hasAnyAncestor(hasTestTag("page-Home")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("page-Plugins").assertExists()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        openControlPage()
        compose.onNodeWithTag("tab-Plugins").performTouchInput { click() }
        compose.onNodeWithTag("page-Plugins").assertExists()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithTag("tab-Plugins").performTouchInput { click() }
        compose.onNodeWithTag("page-Plugins").assertExists()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    @Test fun conversationTimeoutSavesMinutesRejectsInvalidValuesAndCanBeDisabled() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithText("闲置 15 分钟后").assertExists()
        compose.onNodeWithText("自动新建对话").performScrollTo().performClick()
        compose.onNodeWithText("时长（分钟）").performTextReplacement("1441")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(15, state.config.conversationTimeoutMinutes) }
        compose.onAllNodesWithText("时长需在 0–1440 分钟之间，0 表示关闭").assertAny(
            hasText("时长需在 0–1440 分钟之间，0 表示关闭"))
        compose.onNodeWithText("时长（分钟）").performTextReplacement("1440")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("闲置 1440 分钟后").assertExists()
        compose.activityRule.scenario.recreate()
        val recreatedState = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.onNodeWithText("闲置 1440 分钟后").assertExists()
        compose.runOnIdle {
            assertEquals(1440, io.github.miaiplus.Config(
                recreatedState.getApplication<Application>()).conversationTimeoutMinutes)
        }
        compose.onNodeWithText("自动新建对话").performScrollTo().performClick()
        compose.onNodeWithText("时长（分钟）").performTextReplacement("0")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(0, recreatedState.config.conversationTimeoutMinutes)
            assertEquals(0, io.github.miaiplus.Config(
                recreatedState.getApplication<Application>()).conversationTimeoutMinutes)
            assertEquals(false, recreatedState.setConversationTimeoutMinutes(-1))
            assertEquals(0, recreatedState.config.conversationTimeoutMinutes)
            assertEquals(true, recreatedState.setConversationTimeoutMinutes(1))
            assertEquals(1, recreatedState.config.conversationTimeoutMinutes)
        }
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
        compose.onNodeWithText("运行状态").performScrollTo()
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

    @Test fun tabsAnimateInBothDirectionsAndSettleWithoutRetainingThePreviousPage() {
        for (dark in listOf(false, true)) {
            if (dark) {
                RuntimeEnvironment.setQualifiers("+night")
                compose.activityRule.scenario.recreate()
            }
            val origin = compose.onNodeWithTag("page-Home").getUnclippedBoundsInRoot().left.value
            compose.mainClock.autoAdvance = false
            try {
                for ((tab, direction) in listOf("Model" to 1, "Plugins" to 1, "Model" to -1, "Home" to -1)) {
                    compose.onNodeWithTag("tab-$tab").performClick()
                    compose.mainClock.advanceTimeBy(80)
                    val page = compose.onNodeWithTag("page-$tab")
                    val offset = page.getUnclippedBoundsInRoot().left.value - origin
                    check(offset * direction > 0.5f) { "$tab must still be sliding in, offset=$offset" }
                    compose.onAllNodes(hasTestTag("page-Home") or hasTestTag("page-Model") or hasTestTag("page-Plugins"))
                        .assertCountEquals(1)
                    if (tab == "Plugins") capture("tab-enter-${if (dark) "dark" else "light"}")
                    compose.mainClock.advanceTimeBy(300)
                    assertEquals(origin, page.getUnclippedBoundsInRoot().left.value, 0.01f)
                    compose.onNodeWithTag("tab-$tab").assertIsSelected()
                    compose.onNodeWithTag("tab-$tab").performClick()
                    compose.mainClock.advanceTimeBy(80)
                    assertEquals(origin, page.getUnclippedBoundsInRoot().left.value, 0.01f)
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test fun switchingTabsDuringAnimationReleasesThePreviousPagesAndSettlesOnTheLatestTab() {
        compose.onNodeWithText("运行状态").performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("运行状态").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("page-Home").assertExists()
            compose.onNodeWithTag("page-Status").assertExists()
            compose.onNodeWithTag("tab-Model").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("page-Model").assertExists()
            compose.onNodeWithTag("page-Home").assertDoesNotExist()
            compose.onNodeWithTag("page-Status").assertDoesNotExist()
            compose.onNodeWithTag("tab-Plugins").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("tab-Plugins").assertIsSelected()
            compose.onNodeWithTag("page-Plugins").assertExists()
            compose.onNodeWithTag("page-Model").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(64)
            check(compose.onNodeWithTag("page-Plugins").getUnclippedBoundsInRoot().left.value > 0.5f)
            compose.onNodeWithTag("tab-Home").performClick()
            compose.mainClock.advanceTimeBy(80)
            check(compose.onNodeWithTag("page-Home").getUnclippedBoundsInRoot().left.value < -0.5f)
            compose.onNodeWithTag("page-Plugins").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(400)
            assertEquals(0f, compose.onNodeWithTag("page-Home").getUnclippedBoundsInRoot().left.value, 0.01f)
            compose.onNodeWithTag("tab-Home").assertIsSelected()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun editingAnAlreadyDirtyDraftDoesNotInvalidateDirtyObservers() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val observer = SnapshotStateObserver { it() }
        val scope = Any()
        var invalidations = 0
        val onChanged: (Any) -> Unit = { invalidations++ }
        observer.start()
        try {
            compose.runOnIdle {
                observer.observeReads(scope, onChanged) { assertEquals(false, state.dirty) }
                state.updateProfile(name = "草稿")
                Snapshot.sendApplyNotifications()
                assertEquals(1, invalidations)
                observer.observeReads(scope, onChanged) { assertEquals(true, state.dirty) }
                state.updateProfile(name = "继续编辑草稿")
                Snapshot.sendApplyNotifications()
                assertEquals(1, invalidations)
                state.discard()
                Snapshot.sendApplyNotifications()
                assertEquals(2, invalidations)
                assertEquals(false, state.dirty)
            }
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun savingBeforeSwitchingTabsUsesTheRequestedDestination() {
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNodeWithText("配置名称").performTextReplacement("我的 API")
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            assertEquals("我的 API", state.draft?.name)
        }
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithText("保存并离开").performClick()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        compose.runOnIdle {
            assertEquals("我的 API", EndpointProfiles(io.github.miaiplus.Config.prefs(state.getApplication<Application>())).active().name)
        }
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNode(hasSetTextAction() and hasText("我的 API")).assertExists()
        compose.onNodeWithText("保存配置").assertDoesNotExist()
    }

    @Test fun modelSectionsKeepTheirScrollPositionAcrossTabsAndRecreation() {
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("另存为新配置"))
        compose.onNodeWithText("另存为新配置").assertIsDisplayed()
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onNodeWithTag("tab-Model").performClick()
        compose.onNodeWithText("另存为新配置").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("另存为新配置").assertIsDisplayed()
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("配置名称"))
        compose.onNode(hasSetTextAction() and hasText("默认配置")).assertExists()
    }

    @Test fun modelListReachesTheBottomBarAndKeepsItsLastSettingAccessible() {
        compose.onNodeWithTag("tab-Model").performClick()
        fun assertListReachesContentBottom() {
            val content = compose.onNodeWithTag("page-Model").getBoundsInRoot()
            val list = compose.onNodeWithTag("model-settings").getBoundsInRoot()
            assertEquals(content.bottom.value, list.bottom.value, 1f)
        }
        assertListReachesContentBottom()
        capture("model-bottom-bar-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        assertListReachesContentBottom()
        capture("model-bottom-bar-dark")
        compose.onNodeWithText("配置名称").performTextReplacement("布局测试")
        compose.onNodeWithText("保存配置").assertIsDisplayed()
        assertListReachesContentBottom()
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("删除当前配置"))
        compose.onNodeWithText("删除当前配置").assertIsDisplayed()
        val lastSetting = compose.onNodeWithText("删除当前配置").getBoundsInRoot()
        val save = compose.onNodeWithText("保存配置").getBoundsInRoot()
        check(lastSetting.bottom + 8.dp <= save.top)
        capture("model-bottom-bar-dirty")
    }

    @Test fun dashboardLabelsSavedReportsWithoutClaimingALiveConnection() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            io.github.miaiplus.Config.prefs(state.getApplication<Application>()).edit()
                .putString("hook_report", "ready: fixture")
                .putLong("hook_at", 1_700_000_000_000L).commit()
            state.refresh()
        }
        compose.onNodeWithText("工作正常").assertExists()
        capture("home-ready-light")
        compose.runOnIdle {
            io.github.miaiplus.Config.prefs(state.getApplication<Application>()).edit()
                .putString("hook_report", "unsupported: fixture").commit()
            state.refresh()
        }
        compose.onNodeWithText("上次注入异常").assertExists()
    }

    @Test fun virtualCanvasSavesCustomGeometryAndFillsCurrentDeviceValues() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val device = state.currentDisplayGeometry()
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithText("自定义分辨率").performClick()
        compose.onNodeWithText("宽度（像素）").performTextReplacement("1440")
        compose.onNodeWithText("高度（像素）").performTextReplacement("3200")
        compose.onNodeWithText("密度（dpi）").performTextReplacement("560")
        capture("virtual-canvas-light")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("1440×3200 @ 560 dpi").assertExists()
        compose.onNodeWithText("自定义分辨率").performClick()
        compose.onNodeWithText("填入本机分辨率").performClick()
        compose.onNodeWithText("宽度（像素）").assertTextContains(device.width.toString())
        compose.onNodeWithText("高度（像素）").assertTextContains(device.height.toString())
        compose.onNodeWithText("密度（dpi）").assertTextContains(device.density.toString())
        // Filling is a draft operation; the user still explicitly saves it.
        compose.runOnIdle { assertEquals(1440, state.config.displayWidth) }
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(device.width, state.config.displayWidth)
            assertEquals(device.height, state.config.displayHeight)
            assertEquals(device.density, state.config.displayDensity)
        }
    }

    @Test fun backgroundCheckIsExplicitAndSurvivesStatusRefresh() {
        val calls = AtomicInteger()
        val provider = BackgroundStatusProvider(calls).apply {
            attachInfo(compose.activity, ProviderInfo().apply { authority = BridgeProvider.URI.authority })
        }
        ShadowContentResolver.registerProviderInternal(BridgeProvider.URI.authority, provider)
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithTag("tab-Plugins").assertIsSelected()
        assertEquals(0, calls.get())
        compose.onNodeWithText("检查前置条件").performScrollTo().performClick()
        compose.waitUntil { calls.get() == 1 }
        compose.onNodeWithText("前置条件已满足").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("前置条件已满足").assertExists()
        assertEquals(1, calls.get())
        capture("virtual-screen-light")
        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()
        capture("virtual-screen-dark")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("启用控屏工具").assertExists()
    }

    @Test fun virtualProbeRequiresConfirmationAndKeepsIndividualVerdicts() {
        val calls = AtomicInteger()
        val provider = BackgroundStatusProvider(calls).apply {
            attachInfo(compose.activity, ProviderInfo().apply { authority = BridgeProvider.URI.authority })
        }
        ShadowContentResolver.registerProviderInternal(BridgeProvider.URI.authority, provider)
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithText("开始检测").performClick()
        assertEquals(0, calls.get())
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        assertEquals(0, calls.get())
        compose.onNodeWithText("开始检测").performClick()
        compose.onNodeWithText("开始", useUnmergedTree = true).performClick()
        compose.waitUntil { calls.get() == 1 }
        compose.onNodeWithText("1 项通过 · 1 项未通过 · 6 项未验证").performScrollTo().assertExists()
        capture("virtual-result-light")
        compose.onNodeWithText("主屏焦点").performScrollTo().performClick()
        compose.onNodeWithText("fixture focus changed", substring = true).assertExists()
        compose.onNodeWithText("关闭", useUnmergedTree = true).performClick()
        compose.activityRule.scenario.recreate()
        assertEquals(1, calls.get())
    }

    @Test fun virtualAppPickerSearchesNamesAndPackagesAndReturnsTheSelection() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(state.getApplication<Application>().packageManager).addResolveInfoForIntent(intent,
            ResolveInfo().apply {
                nonLocalizedLabel = "虚拟屏测试"
                activityInfo = ActivityInfo().apply {
                    packageName = "org.example.virtualfixture"; name = "org.example.virtualfixture.Main"
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = "org.example.virtualfixture" }
                }
            })
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithText("测试应用").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithText("搜索").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(null, state.virtualScreen.error) }
        compose.onNodeWithText("搜索").performTextReplacement("no-match")
        compose.onNodeWithText("虚拟屏测试").assertDoesNotExist()
        compose.onNodeWithText("没有匹配的应用").assertExists()
        compose.onNodeWithText("搜索").performTextReplacement("virtualfixture")
        compose.onNodeWithText("虚拟屏测试").performClick()
        compose.runOnIdle {
            assertEquals("org.example.virtualfixture/org.example.virtualfixture.Main", state.virtualScreen.selectedApp?.component)
        }
    }

    @Test fun virtualProbeRefusesToRunDuringAnActiveTask() {
        val calls = AtomicInteger()
        val provider = BackgroundStatusProvider(calls, active = true).apply {
            attachInfo(compose.activity, ProviderInfo().apply { authority = BridgeProvider.URI.authority })
        }
        ShadowContentResolver.registerProviderInternal(BridgeProvider.URI.authority, provider)
        openControlPage()
        compose.onNodeWithText("虚拟屏").performClick()
        compose.onNodeWithText("开始检测").performClick()
        compose.onNodeWithText("开始", useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithText("请先结束当前控屏任务").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请先结束当前控屏任务").assertExists()
        assertEquals(0, calls.get())
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

    @Test fun backgroundDirectTestKeepsTheSettingsOnThePhysicalScreen() {
        checkDirectTestHandoff(background = true, screen = true, expectedHandoffs = 0)
    }

    @Test fun physicalDirectTestYieldsSettingsWindowToTargetApp() {
        checkDirectTestHandoff(background = false, screen = true, expectedHandoffs = 1)
    }

    @Test fun chatDirectTestKeepsTheSettingsVisible() {
        checkDirectTestHandoff(background = false, screen = false, expectedHandoffs = 0)
    }

    private fun checkDirectTestHandoff(background: Boolean, screen: Boolean, expectedHandoffs: Int) {
        val starts = AtomicInteger()
        var receivedMode: String? = null
        val provider = object : BackgroundStatusProvider(AtomicInteger()) {
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                if (method == "start") {
                    receivedMode = extras?.getString("mode")
                    starts.incrementAndGet()
                    return Bundle().apply { putString("id", "fixture-job") }
                }
                if (method == "poll") return Bundle().apply {
                    putString("status", "done"); putString("result", "fixture complete")
                }
                return super.call(method, arg, extras)
            }
        }.apply { attachInfo(compose.activity, ProviderInfo().apply { authority = BridgeProvider.URI.authority }) }
        ShadowContentResolver.registerProviderInternal(BridgeProvider.URI.authority, provider)
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val handoffs = AtomicInteger()
        compose.runOnIdle {
            state.updateProfile(endpoint = "https://example.com/v1", model = "fixture-model")
            check(state.saveProfile())
            check(state.setOption("enabled", true))
            check(state.setOption("planner", "external"))
            check(state.setOption("backgroundDisplay", background))
            state.query = "fixture task"
            state.testMode = if (screen) 0 else 1
            state.startTest { handoffs.incrementAndGet() }
        }
        compose.waitUntil(timeoutMillis = 5000) {
            compose.waitForIdle()
            starts.get() == 1 && !state.starting && state.job == null
        }
        compose.runOnIdle {
            val label = "background=$background, screen=$screen"
            assertEquals(label, if (screen) "screen" else "chat", receivedMode)
            assertEquals(label, expectedHandoffs, handoffs.get())
            assertEquals(label, "fixture complete", state.result)
        }
    }

    private fun openControlPage() {
        if (compose.onAllNodesWithTag("page-Control").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag("tab-Plugins").performClick()
        compose.onAllNodesWithText("控屏配置").onFirst().performScrollTo().performClick()
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
private open class BackgroundStatusProvider(private val calls: AtomicInteger, private val active: Boolean = false) : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "screen_state") return Bundle().apply { putBoolean("active", active) }
        if (method == "accessibility_health") return Bundle().apply { putBoolean("busy", active) }
        check(method == "background_display_status" || method == "background_display_probe") { "Unexpected IPC: $method" }
        calls.incrementAndGet()
        return Bundle().apply {
            putBoolean("root", true); putBoolean("deviceLocked", false)
            if (method == "background_display_probe") putString("items", """[
                {"item":"createOwnDisplay","status":"supported"},
                {"item":"focusIsolation","status":"unsupported","reason":"fixture focus changed"}
            ]""")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
