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
        // AndroidViewModel's factory can retain its Application between Robolectric cases.
        // Every case here starts from the shipped defaults, including after a failed case.
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun homeEntryOpensThePromptPageAndSummarizesTheSavedPersona() {
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo()
        compose.onAllNodesWithText("已关闭").onFirst().assertExists()
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("prompt-settings").assertExists()
        compose.onNodeWithText("生效方式").assertExists()
        compose.onNodeWithText("关闭").assertExists()
        compose.onNodeWithText("提示词内容").assertExists()
        compose.onNodeWithText("0/4000").assertExists()
        compose.onNodeWithText("文字对话走智能体").assertExists()
        compose.onNodeWithText("语音对话也应用").assertExists()
        compose.onNodeWithText("独立对话也应用").assertExists()
        compose.onNodeWithText("另存为人设").assertExists()
        compose.onNodeWithText("删除当前人设").assertExists()
        compose.onNodeWithText("至少保留一个人设").assertExists()
    }

    @Test fun leavingADirtyDraftAsksAndDiscardRestoresTheSavedSummary() {
        openPromptPage()
        compose.onNode(hasSetTextAction() and hasText("默认人设")).performTextReplacement("草稿人设")
        compose.onNodeWithText("提示词内容").performTextReplacement("草稿内容")
        compose.onNodeWithTag("tab-Home").performClick()
        compose.onNodeWithText("保存当前人设？").assertExists()
        compose.onNodeWithText("保存并离开").assertExists()
        compose.onNodeWithText("放弃修改").assertExists()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo()
        compose.onAllNodesWithText("已关闭").onFirst().assertExists()
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("默认人设")).assertExists()
        compose.onNodeWithText("保存配置").assertDoesNotExist()
    }

    @Test fun savingOnLeavePersistsThePersonaAndUpdatesTheHomeSummary() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openPromptPage()
        compose.onNodeWithText("生效方式").performClick()
        compose.onNodeWithText("追加人设").performClick()
        compose.onNode(hasSetTextAction() and hasText("默认人设")).performTextReplacement("翻译助手")
        compose.onNodeWithText("提示词内容").performTextReplacement("你是一位严谨的翻译。")
        compose.onNodeWithText("10/4000").assertExists()
        compose.onNodeWithTag("tab-Home").performClick()
        compose.onNodeWithText("保存并离开").performClick()
        compose.onNodeWithTag("tab-Home").assertIsSelected()
        compose.onNodeWithText("追加人设 · 翻译助手", substring = true, useUnmergedTree = true)
            .performScrollTo().assertExists()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            val profiles = PersonaProfiles(Config.prefs(owner))
            assertEquals("翻译助手", profiles.active().name)
            assertEquals("你是一位严谨的翻译。", Config.prefs(owner).getString(Config.CUSTOM_PROMPT_TEXT_KEY, null))
            assertEquals("append", Config(owner).customPromptMode)
            assertTrue(Config(owner).hasEffectivePrompt())
        }
    }

    @Test fun switchingPersonasUpdatesTheFlatSnapshotAndRejectsABlankSave() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.updatePersona(text = "甲的提示词")
            check(state.savePersona("人设甲"))
            check(state.savePersona("人设乙"))
            state.updatePersona(text = "乙的提示词")
            check(state.savePersona())
        }
        openPromptPage()
        compose.onNodeWithText("人设方案").performClick()
        compose.onNodeWithText("人设甲").performClick()
        compose.onNodeWithText("甲的提示词").assertExists()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            assertEquals("甲的提示词", Config.prefs(owner).getString(Config.CUSTOM_PROMPT_TEXT_KEY, null))
            // The default persona, 人设甲 and 人设乙.
            assertEquals(3, PersonaProfiles(Config.prefs(owner)).profiles.size)
        }
        compose.onNodeWithText("提示词内容").performTextReplacement("")
        compose.onNodeWithText("生效方式").performClick()
        compose.onNodeWithText("替换系统提示词").performClick()
        compose.onNodeWithText("保存配置").performClick()
        compose.onNodeWithText("请填写提示词内容").assertExists()
        compose.runOnIdle {
            assertEquals("off", state.config.customPromptMode)
            assertEquals("甲的提示词", Config.prefs(state.getApplication<Application>())
                .getString(Config.CUSTOM_PROMPT_TEXT_KEY, null))
        }
    }

    @Test fun deletingIsRefusedForTheLastPersonaAndSelectsTheRemainingOneOtherwise() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            assertFalse(state.deletePersona())
            assertEquals("至少保留一个人设", state.error)
            state.updatePersona(text = "第二个人设的内容")
            check(state.savePersona("第二个"))
            check(state.deletePersona())
            val owner = state.getApplication<Application>()
            val profiles = PersonaProfiles(Config.prefs(owner))
            assertEquals(1, profiles.profiles.size)
            assertEquals("默认人设", profiles.active().name)
            assertFalse(Config.prefs(owner).all.toString().contains("第二个人设的内容"))
        }
    }

    @Test fun promptSwitchesSaveImmediatelyAndDiagnosticsKeepOnlyModeAndCharacterCount() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.updatePersona(text = "私有提示词")
            check(state.savePersona())
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
            assertFalse(state.promptDirty)
            val exported = DiagnosticEnvironment.snapshot(owner).toString()
            assertTrue(exported.contains("\"customPromptChars\":5"))
            assertFalse(exported.contains("私有提示词"))
            assertFalse(exported.contains("默认人设"))
        }
    }

    private fun openPromptPage() {
        if (compose.onAllNodesWithTag("prompt-settings").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo().performClick()
    }
}
