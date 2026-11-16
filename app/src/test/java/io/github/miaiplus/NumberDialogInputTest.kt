package io.github.miaiplus

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberDialogInputTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearPreferences() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        Config.prefs(state.getApplication<Application>()).edit().clear().commit()
    }

    @Test fun maxStepsKeepsIncrementalInputAndSelectionAndResetsCancelledDraft() {
        val state = ViewModelProvider(compose.activity)[SettingsState::class.java]
        compose.runOnIdle { assertTrue(state.setOption("maxSteps", 30)) }
        compose.onNode(hasText("控屏") and hasAnyAncestor(hasTestTag("page-Home")))
            .performScrollTo().performClick()
        compose.onNodeWithText("最大步数").performClick()
        val field = compose.onNodeWithText("步数")
        field.performTextClearance()
        field.performTextInput("2")
        field.assertEditing("2", TextRange(1))
        field.performTextInput("0")
        field.assertEditing("20", TextRange(2))

        field.performTextInputSelection(TextRange(0))
        field.performTextInput("1")
        field.assertEditing("120", TextRange(1))
        field.performTextInputSelection(TextRange(0, 2))
        field.performTextInput("9")
        field.assertEditing("90", TextRange(1))

        compose.onNodeWithText("取消", substring = false).performClick()
        compose.runOnIdle { assertEquals(30, state.config.maxSteps) }
        compose.onNodeWithText("最大步数").performClick()
        compose.onNodeWithText("步数").assertEditing("30", TextRange(2))
        compose.onNodeWithText("取消", substring = false).performClick()
    }

    private fun SemanticsNodeInteraction.assertEditing(text: String, selection: TextRange) {
        assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(text)))
        assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
    }
}
