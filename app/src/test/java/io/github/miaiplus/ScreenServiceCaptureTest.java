package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenServiceCaptureTest {
    @Test public void encodedScreenshotDimensionsDriveImageCoordinatesForPortraitLandscapeAndSmallDisplays() throws Exception {
        for (int[] size : new int[][]{{1440, 3200}, {3200, 1440}, {720, 1080}}) {
            Bitmap source = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            try {
                ScreenService.Frame frame = ScreenService.encodePhysical(source, "tree", "example.app");
                byte[] jpeg = Base64.decode(frame.image(), Base64.NO_WRAP);
                Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                try {
                    assertEquals(size[0], frame.width());
                    assertEquals(size[1], frame.height());
                    assertEquals(decoded.getWidth(), frame.imageWidth());
                    assertEquals(decoded.getHeight(), frame.imageHeight());
                    assertTrue(Math.max(frame.imageWidth(), frame.imageHeight()) <= 1280);
                    JSONObject center = new JSONObject().put("action", "tap").put("element",
                        new JSONArray().put(decoded.getWidth() / 2.0).put(decoded.getHeight() / 2.0));
                    JSONArray normalized = Coordinates.normalize(center, "image_pixels", frame.width(),
                        frame.height(), frame.imageWidth(), frame.imageHeight()).getJSONArray("element");
                    assertEquals(500, normalized.getDouble(0), 0.001);
                    assertEquals(500, normalized.getDouble(1), 0.001);
                    assertFalse(frame.isBackground());
                } finally { decoded.recycle(); }
                assertFalse(source.isRecycled());
            } finally { source.recycle(); }
        }
    }

    private static final class Timing implements ScreenService.CaptureTiming {
        long now, waited;
        boolean cancelDuringWait, cancelled;
        @Override public long now() { return now; }
        @Override public void pause(long millis) {
            waited += millis; now += millis;
            if (cancelDuringWait) cancelled = true;
        }
        @Override public void check() { if (cancelled) throw new CancellationException(); }
    }

    @Test public void freshCapturesWaitOnlyForTheRemainingMonotonicInterval() throws Exception {
        Timing timing = new Timing(); timing.now = 1000;
        long first = ScreenService.awaitCaptureInterval(-1, timing);
        assertEquals(1000, first);
        assertEquals(0, timing.waited);
        timing.now += 100;
        long second = ScreenService.awaitCaptureInterval(first, timing);
        assertTrue(second - first >= ScreenService.CAPTURE_INTERVAL_MS);
        assertEquals(250, timing.waited);
        timing.now += 1000;
        assertEquals(timing.now, ScreenService.awaitCaptureInterval(second, timing));
        assertEquals(250, timing.waited);
    }

    @Test public void cancellationWhileSpacingScreenshotsPreventsTheNextRequest() {
        Timing timing = new Timing(); timing.now = 100; timing.cancelDuringWait = true;
        assertThrows(CancellationException.class, () -> ScreenService.awaitCaptureInterval(0, timing));
        Timing cancelled = new Timing(); cancelled.cancelled = true;
        assertThrows(CancellationException.class, () -> ScreenService.awaitCaptureInterval(-1, cancelled));
        assertEquals(0, cancelled.waited);
    }
}
