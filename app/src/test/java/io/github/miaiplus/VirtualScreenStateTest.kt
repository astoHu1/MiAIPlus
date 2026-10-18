package io.github.miaiplus

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VirtualScreenStateTest {
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
