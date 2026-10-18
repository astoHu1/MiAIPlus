package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ScreenServiceTreeTest {
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
        AccessibilityNodeInfo root = node("android.widget.FrameLayout", 0, 1000);
        root.setText("一\n二\r三\\四");
        assertTrue(dump(root).contains("text=一\\n二\\r三\\\\四\n"));
        root.setPassword(true);
        String redacted = dump(root);
        assertTrue(redacted.contains("[password redacted]"));
        assertFalse(redacted.contains("text="));
    }

    @Test public void cyclicNodeReferencesCannotDuplicateTheRootOrHideNavigation() throws Exception {
        AccessibilityNodeInfo root = page(10, false);
        AccessibilityNodeInfo body = root.getChild(0);
        Shadows.shadowOf(body).addChild(root);
        String tree = dump(root);
        assertEquals(15, tree.lines().count());
        assertTrue(tree.contains("text=首页"));
        assertTrue(PageGuard.canExecute(tap(), frame(dump(page(10, false))), frame(tree)));
    }

    @Test public void differentNodesWithIdenticalSignaturesAreNotDeduplicated() {
        AccessibilityNodeInfo root = node("android.widget.FrameLayout", 0, 1000);
        Shadows.shadowOf(root).addChild(node("android.view.View", 0, 1000));
        Shadows.shadowOf(root).addChild(node("android.view.View", 0, 1000));
        assertEquals(3, dump(root).lines().count());
    }

    private static AccessibilityNodeInfo page(int adChildren, boolean overlay) {
        AccessibilityNodeInfo root = node("android.widget.FrameLayout", 0, 1000);
        AccessibilityNodeInfo body = node("android.widget.FrameLayout", 0, 900);
        AccessibilityNodeInfo ad = node("android.widget.FrameLayout", 100, 700);
        Shadows.shadowOf(root).addChild(body);
        Shadows.shadowOf(body).addChild(ad);
        for (int index = 0; index < adChildren; index++) {
            AccessibilityNodeInfo child = node("android.view.View", 100, 700);
            child.setText("广告" + index);
            Shadows.shadowOf(ad).addChild(child);
        }
        AccessibilityNodeInfo tabs = node("android.widget.FrameLayout", 900, 1000);
        Shadows.shadowOf(root).addChild(tabs);
        AccessibilityNodeInfo home = node("android.widget.Button", 900, 1000);
        home.setText("首页"); home.setClickable(true);
        Shadows.shadowOf(tabs).addChild(home);
        if (overlay) {
            AccessibilityNodeInfo cover = node("android.view.View", 0, 1000);
            cover.setViewIdResourceName("overlay");
            Shadows.shadowOf(root).addChild(cover);
        }
        return root;
    }

    private static AccessibilityNodeInfo node(String name, int top, int bottom) {
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
}
