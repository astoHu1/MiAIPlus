package io.github.miaiplus

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class SettingsPromptStateTest {
    private val application = RuntimeEnvironment.getApplication()
    private val prefs get() = Config.prefs(application)

    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun changingModeWritesImmediatelyAndKeepsUnsavedPersonaEditsSeparate() {
        val state = SettingsState(application)
        state.updatePersona(name = "草稿名称", text = "草稿内容")

        assertTrue(state.setPromptMode(PromptKeys.MODE_REPLACE))
        assertEquals("replace", Config(application).customPromptMode)
        assertEquals("", Config(application).customPromptText)
        assertEquals("默认人设", PersonaProfiles(prefs).active().name)
        assertEquals("草稿名称", state.personaDraft?.name)
        assertEquals("草稿内容", state.personaDraft?.text)
        assertTrue(state.promptDirty)

        state.discardPersona()
        assertEquals("replace", state.personaDraft?.mode)
        assertFalse(state.promptDirty)
        assertFalse(state.setPromptMode("unknown"))
        assertEquals("replace", Config(application).customPromptMode)
    }

    @Test fun switchingAppliesSavedTextAndRetainsEachPersonasDraftWithoutRevertingGlobalOptions() {
        val state = SettingsState(application)
        state.updatePersona(name = "甲", text = "甲已保存")
        assertTrue(state.savePersona())
        val firstId = requireNotNull(state.personaDraft).id
        state.updatePersona(text = "甲未保存")
        assertTrue(state.createPersona("乙"))
        val secondId = requireNotNull(state.personaDraft).id
        assertEquals("", state.personaDraft?.text)
        assertEquals("", Config(application).customPromptText)
        state.updatePersona(text = "乙已保存")
        assertTrue(state.savePersona())
        state.updatePersona(name = "乙的新名称", text = "乙未保存")
        assertTrue(state.setPromptMode(PromptKeys.MODE_APPEND))
        assertTrue(state.setPromptSwitch(PromptKeys.APPLY_VOICE, true))

        assertTrue(state.switchPersona(firstId))
        assertEquals("甲已保存", Config(application).customPromptText)
        assertEquals("甲未保存", state.personaDraft?.text)
        assertEquals("append", state.personaDraft?.mode)
        assertEquals(true, state.personaDraft?.applyVoice)
        state.refresh()
        assertEquals("甲未保存", state.personaDraft?.text)

        assertTrue(state.switchPersona(secondId))
        assertEquals("乙已保存", Config(application).customPromptText)
        assertEquals("乙的新名称", state.personaDraft?.name)
        assertEquals("乙未保存", state.personaDraft?.text)
        assertTrue(state.savePersona())
        assertEquals("乙未保存", Config(application).customPromptText)
        assertEquals("乙的新名称", SettingsState(application).personaDraft?.name)
        assertFalse(state.promptDirty)
    }

    @Test fun blankPersonaCanBeCreatedAndSavedWithAnEnabledModeAndDuplicateCreationKeepsTheCurrentDraft() {
        val state = SettingsState(application)
        assertTrue(state.setPromptMode(PromptKeys.MODE_APPEND))
        assertTrue(state.createPersona("新方案"))
        assertTrue(state.savePersona())
        assertFalse(Config(application).hasEffectivePrompt())
        state.updatePersona(text = "未保存内容")

        assertFalse(state.createPersona(" 默认人设 "))
        assertEquals("人设名称已存在", state.error)
        assertEquals("新方案", PersonaProfiles(prefs).active().name)
        assertEquals("未保存内容", state.personaDraft?.text)
        assertEquals("", Config(application).customPromptText)
        assertEquals(2, PersonaProfiles(prefs).profiles.size)

        assertTrue(state.savePersona())
        assertTrue(Config(application).hasEffectivePrompt())
        state.updatePersona(text = "")
        assertTrue(state.savePersona())
        assertEquals("", Config(application).customPromptText)
        assertFalse(Config(application).hasEffectivePrompt())
    }

    @Test fun deletingAPersonaDropsItsDraftAndSelectsTheRemainingSavedText() {
        val state = SettingsState(application)
        state.updatePersona(text = "保留内容")
        assertTrue(state.savePersona())
        val firstId = requireNotNull(state.personaDraft).id
        assertTrue(state.createPersona("待删除"))
        val deletedId = requireNotNull(state.personaDraft).id
        state.updatePersona(text = "待删除草稿")
        assertTrue(state.switchPersona(firstId))
        assertTrue(state.switchPersona(deletedId))
        assertTrue(state.deletePersona())

        assertEquals(firstId, state.personaDraft?.id)
        assertEquals("保留内容", Config(application).customPromptText)
        assertEquals(1, PersonaProfiles(prefs).profiles.size)
        assertFalse(state.deletePersona())
        assertEquals("保留内容", Config(application).customPromptText)
    }
}
