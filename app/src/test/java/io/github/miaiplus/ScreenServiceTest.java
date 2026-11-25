package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = ScreenServiceTest.FocusNodeShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenServiceTest {
    private static final String PACKAGE = "com.example.form";
    private ScreenService service;
    private AccessibilityWindowInfo window;
    private AccessibilityNodeInfo root;

    @Before public void setUp() {
        service = Robolectric.buildService(ScreenService.class).create().get();
        ScreenService.instance = service;
        root = inputNode(false, "");
        window = AccessibilityWindowInfo.obtain();
        Shadows.shadowOf(window).setId(42);
        Shadows.shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        Shadows.shadowOf(window).setRoot(root);
        Shadows.shadowOf(service).setWindowsOnDisplay(7, List.of(window));
    }

    @After public void tearDown() {
        ScreenService.instance = null;
        io.github.miaiplus.Config.prefs(service).edit().remove(io.github.miaiplus.Config.PHONE_USE_LOG_KEY).commit();
    }

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

    @Test public void captureSpacingWaitsOnlyForTheRemainingIntervalAndStopsOnCancellation() throws Exception {
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

        Timing during = new Timing(); during.now = 100; during.cancelDuringWait = true;
        assertThrows(CancellationException.class, () -> ScreenService.awaitCaptureInterval(0, during));
        Timing cancelled = new Timing(); cancelled.cancelled = true;
        assertThrows(CancellationException.class, () -> ScreenService.awaitCaptureInterval(-1, cancelled));
        assertEquals(0, cancelled.waited);
    }

    @Test public void writingWithoutInputFocusLeavesTheFieldUntouchedAndLogsNoPageText() {
        PhoneUseLog.init(service);
        String before = ScreenService.treeOnDisplay(7, PACKAGE);
        io.github.miaiplus.Config.prefs(service).edit().putBoolean(io.github.miaiplus.Config.PHONE_USE_LOG_KEY, true).commit();
        org.robolectric.shadows.ShadowLog.clear();
        assertEquals(before, ScreenService.treeOnDisplay(7, PACKAGE));
        AccessibilityNodeInfo field = inputNode(true, "private-existing-input");
        Shadows.shadowOf(root).addChild(field);
        assertFalse(ScreenService.setTextOnDisplay(7, PACKAGE, "private-replacement"));
        assertTrue(Shadows.shadowOf(field).getPerformedActions().isEmpty());
        assertEquals("private-existing-input", field.getText().toString());
        String logs = org.robolectric.shadows.ShadowLog.getLogsForTag(PhoneUseLog.TAG).toString();
        assertTrue(logs.contains("no_input_focus"));
        assertFalse(logs.contains("private-existing-input")); assertFalse(logs.contains("private-replacement"));
    }

    @Test public void textVerificationRequiresAnExactReadableReplacementIncludingClearing() throws Exception {
        record Sample(String actual, String expected, boolean matches) { }
        Sample[] cases = {
            new Sample("12345", "123", false),
            new Sample("123", "123", true),
            new Sample("12345", "", false),
            new Sample(null, "", false),
            new Sample("", "", true),
            new Sample("prefix目标suffix", "目标", false),
            new Sample("123 456", "123456", false),
            new Sample("中文目标", "中文目标", true)
        };
        for (Sample sample : cases) {
            assertEquals(sample.toString(), sample.matches(), matches(sample.actual(), sample.expected()));
        }
    }

    @Test public void readBackFollowsTheOriginalFieldAcrossFocusMovesAndFreshWindows() throws Exception {
        AccessibilityNodeInfo first = inputNode(true, "old");
        AccessibilityNodeInfo next = inputNode(true, "");
        Shadows.shadowOf(root).addChild(first);
        Shadows.shadowOf(root).addChild(next);
        first.setFocused(true);
        int[] performed = {0};
        Shadows.shadowOf(first).setOnPerformActionListener((action, arguments) -> {
            if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) return false;
            performed[0]++;
            // The app publishes a new tree snapshot and moves focus to the next field after applying the edit.
            AccessibilityNodeInfo updated = AccessibilityNodeInfo.obtain(first);
            updated.setText(arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));
            updated.setFocused(false);
            next.setFocused(true);
            AccessibilityNodeInfo freshRoot = inputNode(false, "");
            Shadows.shadowOf(freshRoot).addChild(updated);
            Shadows.shadowOf(freshRoot).addChild(next);
            Shadows.shadowOf(window).setRoot(freshRoot);
            return true;
        });

        assertTrue(ScreenService.setTextOnDisplay(7, PACKAGE + "/.FormActivity", "目标字段"));
        assertEquals("", next.getText().toString());
        assertEquals(1, performed[0]);

        // A removed field cannot be verified by another field that happens to hold the expected text.
        AccessibilityNodeInfo original = inputNode(true, "old");
        AccessibilityNodeInfo replacement = inputNode(true, "expected");
        AccessibilityNodeInfo otherRoot = inputNode(false, "");
        Shadows.shadowOf(otherRoot).addChild(replacement);
        replacement.setFocused(true);
        Method read = ScreenService.class.getDeclaredMethod("readBackText",
            AccessibilityNodeInfo.class, AccessibilityNodeInfo.class);
        read.setAccessible(true);
        assertNull(read.invoke(null, otherRoot, original));
    }

    @Test public void expandedAdPreservesNavigationAndOverlayChecksWithinTheNodeBudget() throws Exception {
        String before = dump(page(10, false));
        assertTrue(before.contains("text=首页"));
        for (boolean overlay : new boolean[]{false, true}) {
            String current = dump(page(500, overlay));
            String scenario = "overlay=" + overlay;
            assertTrue(scenario, current.contains("text=首页"));
            assertEquals(scenario, 250, current.lines().count());
            assertEquals(scenario, overlay, current.contains("id=overlay"));
            assertEquals(scenario, !overlay, PageGuard.canExecute(tap(), frame(before), frame(current)));
        }
    }

    @Test public void nodeTextEscapingAndPasswordRedactionRemainIntact() {
        AccessibilityNodeInfo node = treeNode("android.widget.FrameLayout", 0, 1000);
        node.setText("一\n二\r三\\四");
        assertTrue(dump(node).contains("text=一\\n二\\r三\\\\四\n"));
        node.setPassword(true);
        String redacted = dump(node);
        assertTrue(redacted.contains("[password redacted]"));
        assertFalse(redacted.contains("text="));
    }

    @Test public void cyclicReferencesAreSkippedButIdenticalSiblingsAreKept() throws Exception {
        AccessibilityNodeInfo cyclic = page(10, false);
        AccessibilityNodeInfo body = cyclic.getChild(0);
        Shadows.shadowOf(body).addChild(cyclic);
        String tree = dump(cyclic);
        assertEquals(15, tree.lines().count());
        assertTrue(tree.contains("text=首页"));
        assertTrue(PageGuard.canExecute(tap(), frame(dump(page(10, false))), frame(tree)));

        AccessibilityNodeInfo siblings = treeNode("android.widget.FrameLayout", 0, 1000);
        Shadows.shadowOf(siblings).addChild(treeNode("android.view.View", 0, 1000));
        Shadows.shadowOf(siblings).addChild(treeNode("android.view.View", 0, 1000));
        assertEquals(3, dump(siblings).lines().count());
    }

    private static AccessibilityNodeInfo inputNode(boolean editable, String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(PACKAGE);
        node.setEditable(editable);
        node.setVisibleToUser(true);
        node.setText(text);
        return node;
    }

    private static boolean matches(String actual, String expected) throws Exception {
        Class<?> valueType = Class.forName(ScreenService.class.getName() + "$EditableValue");
        Constructor<?> constructor = valueType.getDeclaredConstructor(String.class, boolean.class);
        constructor.setAccessible(true);
        Method compare = ScreenService.class.getDeclaredMethod("textMatches", valueType, String.class);
        compare.setAccessible(true);
        return (Boolean) compare.invoke(null, constructor.newInstance(actual, true), expected);
    }

    private static AccessibilityNodeInfo page(int adChildren, boolean overlay) {
        AccessibilityNodeInfo root = treeNode("android.widget.FrameLayout", 0, 1000);
        AccessibilityNodeInfo body = treeNode("android.widget.FrameLayout", 0, 900);
        AccessibilityNodeInfo ad = treeNode("android.widget.FrameLayout", 100, 700);
        Shadows.shadowOf(root).addChild(body);
        Shadows.shadowOf(body).addChild(ad);
        for (int index = 0; index < adChildren; index++) {
            AccessibilityNodeInfo child = treeNode("android.view.View", 100, 700);
            child.setText("广告" + index);
            Shadows.shadowOf(ad).addChild(child);
        }
        AccessibilityNodeInfo tabs = treeNode("android.widget.FrameLayout", 900, 1000);
        Shadows.shadowOf(root).addChild(tabs);
        AccessibilityNodeInfo home = treeNode("android.widget.Button", 900, 1000);
        home.setText("首页"); home.setClickable(true);
        Shadows.shadowOf(tabs).addChild(home);
        if (overlay) {
            AccessibilityNodeInfo cover = treeNode("android.view.View", 0, 1000);
            cover.setViewIdResourceName("overlay");
            Shadows.shadowOf(root).addChild(cover);
        }
        return root;
    }

    private static AccessibilityNodeInfo treeNode(String name, int top, int bottom) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(new android.view.View(org.robolectric.RuntimeEnvironment.getApplication()));
        node.setClassName(name); node.setBoundsInScreen(new Rect(0, top, 1000, bottom));
        node.setVisibleToUser(true); node.setEnabled(true);
        return node;
    }

    private static String dump(AccessibilityNodeInfo root) {
        StringBuilder tree = new StringBuilder();
        ScreenService.dump(root, tree, new int[]{0});
        return tree.toString();
    }

    private static Action tap() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray().put(500).put(950)));
    }

    private static ScreenService.Frame frame(String tree) {
        return new ScreenService.Frame("image", tree, 1000, 1000, "app", 1000, 1000, 0, "");
    }

    /** Android normally answers this query over accessibility IPC; only focus lookup is supplied by the fixture. */
    @Implements(AccessibilityNodeInfo.class)
    public static class FocusNodeShadow extends ShadowAccessibilityNodeInfo {
        @RealObject private AccessibilityNodeInfo node;

        @Implementation protected AccessibilityNodeInfo findFocus(int focus) {
            if (focus != AccessibilityNodeInfo.FOCUS_INPUT) return null;
            if (node.isFocused()) return node;
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                AccessibilityNodeInfo focused = child == null ? null : child.findFocus(focus);
                if (focused != null) return focused;
            }
            return null;
        }
    }
}
