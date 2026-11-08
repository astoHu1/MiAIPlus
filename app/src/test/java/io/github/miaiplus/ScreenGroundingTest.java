package io.github.miaiplus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenGroundingTest {
    @Test public void imageAndTreeDoNotOverrideTheSystemWindowOwnerInTheObservation() {
        ScreenService.Frame screenshot = ScreenService.Frame.physical("image with another app's watermark",
            "android.widget.ImageView text=抖音 前台=com.ss.android.ugc.aweme", 1000, 1000, "com.tencent.mm");
        String provenance = ModelObservation.provenance(screenshot);
        assertTrue(provenance, provenance.startsWith("系统观察：前台应用包名=com.tencent.mm；"));
        assertTrue(provenance, provenance.contains("执行屏幕=foreground；displayId=0"));
        assertFalse(provenance, provenance.contains("com.ss.android.ugc.aweme"));
    }

    @Test public void backgroundComponentReportsItsPackageAndItsOwnDisplay() {
        ScreenService.Frame background = ScreenService.Frame.background("image", "", 1000, 1000,
            500, 500, "com.example.video/.PlayerActivity", 7, "generation");
        String provenance = ModelObservation.provenance(background);
        assertTrue(provenance, provenance.contains("前台应用包名=com.example.video；"));
        assertTrue(provenance, provenance.contains("执行屏幕=background；displayId=7"));
        assertFalse(provenance, provenance.contains(".PlayerActivity"));
    }

    @Test public void absentWindowOwnerIsNotGuessedFromTheDisplayedContent() {
        ScreenService.Frame screenshot = ScreenService.Frame.physical("image", "com.example.video", 1000, 1000, null);
        assertEquals("", ModelObservation.foregroundPackage(screenshot));
        String provenance = ModelObservation.provenance(screenshot);
        assertTrue(provenance, provenance.startsWith("系统观察：前台应用包名=；"));
        assertFalse(provenance, provenance.contains("com.example.video"));
    }

    @Test public void observationExplainsThatTheScreenshotMayDepictAnotherApp() {
        ScreenService.Frame screenshot = ScreenService.Frame.physical("image", "", 1000, 1000, "com.tencent.mm");
        String provenance = ModelObservation.provenance(screenshot);
        assertTrue(provenance, provenance.contains("包名来自系统窗口，不是图片识别"));
        assertTrue(provenance, provenance.contains("截图可能是宿主应用展示的另一应用截图"));
    }
}
