package io.github.miaiplus

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearFixturePreferences() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun modeSelectionSavesImmediatelyAndLeavingKeepsTheEditorDraftWithoutAsking() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openPromptPage()
        compose.onNode(hasSetTextAction() and hasText("默认人设")).performTextReplacement("草稿人设")
        compose.onNodeWithText("提示词内容").performTextReplacement("草稿内容")
        compose.onNodeWithText("生效方式").performScrollTo().performClick()
        compose.onNodeWithText("追加人设").performClick()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            assertEquals("append", Config(owner).customPromptMode)
            assertEquals("默认人设", PersonaProfiles(Config.prefs(owner)).active().name)
            assertEquals("", Config(owner).customPromptText)
        }
        compose.onNodeWithText("保存配置").assertDoesNotExist()
        compose.onNodeWithText("还原").assertDoesNotExist()
        compose.onNodeWithTag("tab-Home").performClick()
        compose.onNodeWithText("保存当前人设？").assertDoesNotExist()
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("草稿人设")).assertExists()
        compose.onNodeWithText("草稿内容").assertExists()
    }

    @Test fun promptSwitchesSaveImmediatelyWithoutSavingEditsOrExportingPrivateText() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.updatePersona(text = "私有提示词")
            check(state.savePersona())
            state.updatePersona(text = "未保存的正文")
        }
        openPromptPage()
        compose.onNodeWithText("文字对话走智能体").performScrollTo().performClick()
        compose.onNodeWithText("语音对话也应用").performScrollTo().performClick()
        compose.onNodeWithText("独立对话也应用").performScrollTo().performClick()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            val config = Config(owner)
            assertTrue(config.promptRouteTextChat)
            assertTrue(config.promptApplyVoice)
            assertTrue(config.promptApplyStandaloneChat)
            assertEquals("私有提示词", config.customPromptText)
            assertEquals("未保存的正文", state.personaDraft!!.text)
            val exported = DiagnosticEnvironment.snapshot(owner).toString()
            assertTrue(exported.contains("\"customPromptChars\":5"))
            assertFalse(exported.contains("私有提示词"))
            assertFalse(exported.contains("未保存的正文"))
            assertFalse(exported.contains("默认人设"))
        }
    }

    private fun openPromptPage() {
        if (compose.onAllNodesWithTag("prompt-settings").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo().performClick()
    }
}
