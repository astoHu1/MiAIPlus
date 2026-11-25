package io.github.miaiplus

import android.app.Application
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class VirtualScreenStateTest {
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

    @Test fun probeParsingNeverTurnsMissingOrUnknownEvidenceIntoSuccess() {
        val report = DisplayProbeReport.from(Bundle().apply {
            putString("items", """[{"item":"createOwnDisplay","status":"supported"},{"item":"capture","status":"new-status"}]""")
            putString("supported", "8")
        }, "fixture")
        assertEquals(1, report.passed)
        assertEquals(7, report.untested)
        assertEquals("未验证", report.items.first { it.id == "capture" }.verdict)
        assertThrows(IllegalStateException::class.java) { DisplayProbeReport.from(Bundle(), "fixture") }
        assertThrows(IllegalStateException::class.java) { DisplayProbeReport.from(Bundle().apply {
            putString("items", "not JSON")
        }, "fixture") }
        assertThrows(IllegalStateException::class.java) { DisplayProbeReport.from(Bundle().apply {
            putString("error", "PERMISSION_DENIED")
        }, "fixture") }
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
}
