package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
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
public class VisualRegionTest {
    private static final String ROOT = "android.widget.FrameLayout [0,0][1000,1000] window=3 id=root enabled=true";
    private static final String BUTTON = "android.widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n";

    @Test public void containersWithoutNonemptyLabelsNeedStableLocalPixels() throws Exception {
        String original = image(0, Color.WHITE, 80), unrelated = image(1, Color.WHITE, 80), local = image(2, Color.WHITE, 80);
        for (String node : new String[]{"android.widget.FrameLayout [100,100][700,700] window=3 id=ad enabled=true clickable\n",
                "android.widget.TextView [100,100][700,700] window=3 enabled=true text= description=\n"}) {
            String container = ROOT + "\n" + node;
            assertTrue(node, PageGuard.canExecute(tap(200, 200), frame(original, container), frame(unrelated, container)));
            assertFalse(node, PageGuard.canExecute(tap(200, 200), frame(original, container), frame(local, container)));
        }
    }

    @Test public void nearbyChangesAndTinyIconsCannotBeHiddenByRegionAveraging() throws Exception {
        ScreenService.Frame before = frame(image(0, Color.WHITE, 80), "");
        for (int change : new int[]{3, 4})
            assertFalse("image change=" + change, PageGuard.canExecute(tap(200, 200), before,
                frame(image(change, Color.WHITE, 80), "")));
    }

    @Test public void aggregatedParentAdTextUsesStablePixelsAroundTheWholeButton() throws Exception {
        ScreenService.Frame before = frame(image(0, Color.WHITE, 80), ROOT + " description=广告一\n" + BUTTON);
        assertTrue(PageGuard.canExecute(tap(200, 200), before,
            frame(image(1, Color.WHITE, 80), ROOT + " description=广告二\n" + BUTTON)));
        assertFalse(PageGuard.canExecute(tap(200, 200), before,
            frame(image(5, Color.WHITE, 80), ROOT + " description=广告二\n" + BUTTON)));
    }

    @Test public void identicalPixelsCannotApproveAReplacedOrNewCoveringNode() throws Exception {
        String oldImage = image(0, Color.WHITE, 80), newImage = image(1, Color.WHITE, 80);
        String oldTree = ROOT + " description=广告一\n" + BUTTON;
        for (String tree : new String[]{ROOT.replace("id=root", "id=dialog") + " description=广告二\n" + BUTTON,
                ROOT + " description=广告二\n" + BUTTON.replace("提交", "删除"),
                oldTree + "android.view.View [0,0][1000,1000] window=4 enabled=true\n"})
            assertFalse(PageGuard.canExecute(tap(200, 200), frame(oldImage, oldTree), frame(newImage, tree)));
    }

    @Test public void blankInvalidOrDifferentlySizedImagesCannotSupplyLocalProof() throws Exception {
        ScreenService.Frame before = frame(image(0, Color.WHITE, 80), "");
        for (String image : new String[]{"", "invalid", sizedImage(128)})
            assertFalse(PageGuard.canExecute(tap(200, 200), before, frame(image, "")));
    }

    @Test public void smallJpegRoundingIsAllowedButBroadBrightnessChangesAreRejected() throws Exception {
        ScreenService.Frame before = frame(image(0, Color.WHITE, 80), "");
        assertTrue(PageGuard.canExecute(tap(200, 200), before, frame(image(0, Color.rgb(253, 253, 253), 85), "")));
        assertFalse(PageGuard.canExecute(tap(200, 200), before, frame(image(0, Color.rgb(245, 245, 245), 80), "")));
    }

    @Test public void farEdgeCoordinatesUseClippedContextAndActualEncodedDimensions() throws Exception {
        assertTrue(PageGuard.canExecute(tap(1000, 1000), frame(image(0, Color.WHITE, 80), ""),
            frame(image(1, Color.WHITE, 80), "")));
    }

    @Test public void allPointActionsUseTheSameLocalGuard() throws Exception {
        ScreenService.Frame before = frame(image(0, Color.WHITE, 80), "");
        ScreenService.Frame unrelated = frame(image(1, Color.WHITE, 80), "");
        ScreenService.Frame local = frame(image(2, Color.WHITE, 80), "");
        for (String name : new String[]{"tap", "double_tap", "long_press"}) {
            Action action = tap(200, 200);
            action = new Action(action.data.put("action", name));
            assertTrue(name, PageGuard.canExecute(action, before, unrelated));
            assertFalse(name, PageGuard.canExecute(action, before, local));
        }
    }

    private static Action tap(int x, int y) throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray().put(x).put(y)));
    }

    private static ScreenService.Frame frame(String image, String tree) {
        // Legacy physical frames advertise logical dimensions, while the JPEG is actually 256x256.
        return new ScreenService.Frame(image, tree, 1000, 1000, "app", 1000, 1000, 0, "");
    }

    private static String image(int change, int background, int quality) {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(background);
        if (change == 1) paint(bitmap, 160, 20, 240, 80); // An unrelated rotating ad.
        if (change == 2) paint(bitmap, 45, 45, 60, 60); // The planned point itself.
        if (change == 3) paint(bitmap, 70, 45, 76, 60); // Context within the margin.
        if (change == 4) paint(bitmap, 50, 50, 52, 52); // Tiny but significant new icon.
        if (change == 5) paint(bitmap, 26, 26, 32, 32); // Button corner outside the point-only margin.
        return encode(bitmap, quality);
    }

    private static String sizedImage(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        return encode(bitmap, 80);
    }

    private static void paint(Bitmap bitmap, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) bitmap.setPixel(x, y, Color.BLACK);
    }

    private static String encode(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out));
        bitmap.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
