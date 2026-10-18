package io.github.miaiplus

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class SettingsStateDisplayGeometryTest {
    private val application = RuntimeEnvironment.getApplication()

    @After
    fun clearPreferences() {
        Config.prefs(application).edit().clear().commit()
    }

    @Test
    fun savesDisplayGeometryAndRejectsValuesOutsideHelperRange() {
        val state = SettingsState(application)

        assertTrue(state.setDisplayGeometry(1440, 3200, 560))
        assertEquals(1440, state.config.displayWidth)
        assertEquals(3200, state.config.displayHeight)
        assertEquals(560, state.config.displayDensity)
        val reloaded = Config(application)
        assertEquals(1440, reloaded.displayWidth)
        assertEquals(3200, reloaded.displayHeight)
        assertEquals(560, reloaded.displayDensity)

        assertFalse(state.setDisplayGeometry(Config.MIN_DISPLAY_SIZE - 1, 1920, 320))
        assertFalse(state.setDisplayGeometry(1080, Config.MAX_DISPLAY_SIZE + 1, 320))
        assertFalse(state.setDisplayGeometry(1080, 1920, Config.MIN_DISPLAY_DENSITY - 1))
        assertFalse(state.setDisplayGeometry(1080, 1920, Config.MAX_DISPLAY_DENSITY + 1))
        assertEquals(1440, state.config.displayWidth)
        assertEquals(3200, Config(application).displayHeight)
        assertEquals(560, Config(application).displayDensity)

        assertTrue(state.setDisplayGeometry(Config.MIN_DISPLAY_SIZE, Config.MAX_DISPLAY_SIZE,
            Config.MIN_DISPLAY_DENSITY))
        assertTrue(state.setDisplayGeometry(Config.MAX_DISPLAY_SIZE, Config.MIN_DISPLAY_SIZE,
            Config.MAX_DISPLAY_DENSITY))
    }

    @Test
    fun readsCurrentDefaultDisplayGeometry() {
        val geometry = SettingsState(application).currentDisplayGeometry()
        assertTrue(geometry.width > 0)
        assertTrue(geometry.height > 0)
        assertTrue(geometry.density > 0)
    }
}
