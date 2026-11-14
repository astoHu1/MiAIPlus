package io.github.miaiplus

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.robolectric.RuntimeEnvironment
import java.io.File
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

    @Test fun homeEntryOpensThePromptEditorWithoutTheConfigurationFooter() {
        openPromptPage()
        compose.onNodeWithText("生效方式").assertExists()
        compose.onNodeWithText("关闭").assertExists()
        compose.onNodeWithText("提示词内容").assertExists()
        compose.onNodeWithText("0/4000").assertExists()
        compose.onNodeWithText("保存当前人设").performScrollTo().assertExists()
        compose.onNodeWithText("删除当前人设").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("删除「默认人设」？").assertDoesNotExist()
        compose.onNodeWithText("另存为人设").assertDoesNotExist()
        compose.onNodeWithText("还原").assertDoesNotExist()
        compose.onNodeWithText("保存配置").assertDoesNotExist()
        compose.onNodeWithText("至少保留一个人设").assertDoesNotExist()
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

    @Test fun saveCurrentPersonaPersistsTheEditorAndUpdatesTheHomeSummary() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openPromptPage()
        compose.onNodeWithText("生效方式").performClick()
        compose.onNodeWithText("追加人设").performClick()
        compose.onNode(hasSetTextAction() and hasText("默认人设")).performTextReplacement("翻译助手")
        compose.onNodeWithText("提示词内容").performTextReplacement("你是一位严谨的翻译。")
        compose.onNodeWithText("10/4000").assertExists()
        compose.onNodeWithText("保存当前人设").performScrollTo().performClick()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            assertEquals("翻译助手", PersonaProfiles(Config.prefs(owner)).active().name)
            assertEquals("你是一位严谨的翻译。", Config(owner).customPromptText)
            assertEquals("append", Config(owner).customPromptMode)
            assertFalse(state.promptDirty)
        }
        compose.onNodeWithTag("tab-Home").performClick()
        compose.onNodeWithText("追加人设 · 翻译助手", substring = true, useUnmergedTree = true)
            .performScrollTo().assertExists()
    }

    @Test fun personaMenuCreatesAndSwitchesProfilesWhileKeepingUnsavedEdits() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        openPromptPage()
        compose.onNodeWithText("提示词内容").performTextReplacement("默认的提示词")
        compose.onNodeWithText("保存当前人设").performScrollTo().performClick()
        compose.onNodeWithText("人设方案").performScrollTo().performClick()
        compose.onNodeWithText("新建人设").performClick()
        compose.onNodeWithText("新人设名称").performTextInput("翻译助手")
        compose.onNodeWithText("创建").performClick()
        compose.onNodeWithText("提示词内容").performTextReplacement("翻译的提示词")
        compose.onNodeWithText("保存当前人设").performScrollTo().performClick()
        compose.onNodeWithText("提示词内容").performTextReplacement("尚未保存的修改")
        compose.onNodeWithText("人设方案").performScrollTo().performClick()
        compose.onNodeWithText("默认人设").performClick()
        compose.onNodeWithText("切换前保存修改？").assertDoesNotExist()
        compose.onNodeWithText("默认的提示词").assertExists()
        compose.runOnIdle {
            assertEquals("默认的提示词", Config(state.getApplication<Application>()).customPromptText)
        }
        compose.onNodeWithText("人设方案").performClick()
        compose.onNodeWithText("翻译助手").performClick()
        compose.onNodeWithText("尚未保存的修改").assertExists()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            assertEquals("翻译的提示词", Config(owner).customPromptText)
            assertEquals(2, PersonaProfiles(Config.prefs(owner)).profiles.size)
        }
    }

    @Test fun savingAnEmptyPromptClearsTheAppliedText() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            state.updatePersona(text = "原提示词")
            check(state.savePersona())
            check(state.setPromptMode(PromptKeys.MODE_REPLACE))
        }
        openPromptPage()
        compose.onNodeWithText("提示词内容").performTextReplacement("")
        compose.onNodeWithText("保存当前人设").performScrollTo().performClick()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            assertEquals("", Config(owner).customPromptText)
            assertEquals("replace", Config(owner).customPromptMode)
            assertFalse(Config(owner).hasEffectivePrompt())
            assertFalse(state.promptDirty)
        }
    }

    @Test fun deleteCurrentPersonaSelectsTheRemainingProfile() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle {
            check(state.createPersona("第二个"))
            state.updatePersona(text = "第二个人设的内容")
            check(state.savePersona())
        }
        openPromptPage()
        compose.onNodeWithText("删除当前人设").performScrollTo().performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle {
            val owner = state.getApplication<Application>()
            val profiles = PersonaProfiles(Config.prefs(owner))
            assertEquals(1, profiles.profiles.size)
            assertEquals("默认人设", profiles.active().name)
            assertFalse(Config.prefs(owner).all.toString().contains("第二个人设的内容"))
        }
        compose.onNodeWithText("删除当前人设").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("删除「默认人设」？").assertDoesNotExist()
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

    @Test fun promptPageAndPersonaMenuFitDarkAndLargerText() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        val longName = "这是一套名称较长的日常翻译助手人设方案"
        compose.runOnIdle {
            check(state.createPersona(longName))
            state.updatePersona(text = "你是一位严谨的翻译，只输出译文。")
            check(state.savePersona())
        }
        openPromptPage()
        val project = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
        val directory = File(project, "work/prompt-ui-20261114").apply { mkdirs() }
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            for (variant in listOf("light", "dark", "large-text")) {
                RuntimeEnvironment.setQualifiers(if (variant == "dark") "+w393dp-night" else if (variant == "large-text") "+w320dp-notnight" else "+w393dp-notnight")
                RuntimeEnvironment.setFontScale(if (variant == "large-text") 1.5f else 1f)
                compose.activityRule.scenario.recreate()
                compose.onNodeWithText("生效方式").performScrollTo().assertIsDisplayed()
                capture("prompt-$variant")
                compose.onNodeWithText("人设方案").performScrollTo().performClick()
                for (label in listOf("默认人设", longName, "新建人设")) {
                    val option = compose.onAllNodesWithText(label).onLast()
                    option.assertIsDisplayed()
                    val bounds = option.getBoundsInRoot()
                    val root = compose.onRoot().getBoundsInRoot()
                    assertTrue(bounds.left >= root.left && bounds.right <= root.right)
                    assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
                }
                capture("prompt-personas-$variant")
                compose.onAllNodesWithText("默认人设").onLast().performClick()
                compose.onNodeWithText("保存当前人设").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("删除当前人设").performScrollTo().assertIsDisplayed()
                capture("prompt-actions-$variant")
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("+w393dp-notnight")
        }
    }

    private fun openPromptPage() {
        if (compose.onAllNodesWithTag("prompt-settings").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithText("系统提示词", useUnmergedTree = true).performScrollTo().performClick()
    }
}
