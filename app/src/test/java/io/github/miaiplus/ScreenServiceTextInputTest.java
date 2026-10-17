package io.github.miaiplus;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = ScreenServiceTextInputTest.FocusNodeShadow.class)
public class ScreenServiceTextInputTest {
    private static final String PACKAGE = "com.example.form";
    private ScreenService service;
    private AccessibilityWindowInfo window;
    private AccessibilityNodeInfo root;

    @Before public void setUp() {
        service = Robolectric.buildService(ScreenService.class).create().get();
        ScreenService.instance = service;
        root = node(false, "");
        window = AccessibilityWindowInfo.obtain();
        Shadows.shadowOf(window).setId(42);
        Shadows.shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        Shadows.shadowOf(window).setRoot(root);
        Shadows.shadowOf(service).setWindowsOnDisplay(7, List.of(window));
    }

    @After public void tearDown() {
        ScreenService.instance = null;
    }

    @Test public void shorterReplacementMustNotAcceptTheOldLongerValue() throws Exception {
        assertFalse(matches("12345", "123"));
        assertTrue(matches("123", "123"));
    }

    @Test public void clearingRequiresAnActuallyEmptyReadableValue() throws Exception {
        assertFalse(matches("12345", ""));
        assertFalse(matches(null, ""));
        assertTrue(matches("", ""));
    }

    @Test public void formattedOrPartialValuesRemainUnverified() throws Exception {
        assertFalse(matches("prefix目标suffix", "目标"));
        assertFalse(matches("123 456", "123456"));
        assertTrue(matches("中文目标", "中文目标"));
    }

    @Test public void writingWithoutInputFocusDoesNotOverwriteTheFirstEditableField() {
        AccessibilityNodeInfo first = node(true, "keep this value");
        Shadows.shadowOf(root).addChild(first);

        assertFalse(ScreenService.setTextOnDisplay(7, PACKAGE, "replacement"));
        assertTrue(Shadows.shadowOf(first).getPerformedActions().isEmpty());
        assertEquals("keep this value", first.getText().toString());
    }

    @Test public void autoFocusAndFreshWindowReadBackStillVerifyTheOriginalField() {
        AccessibilityNodeInfo first = node(true, "old");
        AccessibilityNodeInfo next = node(true, "");
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
            AccessibilityNodeInfo freshRoot = node(false, "");
            Shadows.shadowOf(freshRoot).addChild(updated);
            Shadows.shadowOf(freshRoot).addChild(next);
            Shadows.shadowOf(window).setRoot(freshRoot);
            return true;
        });

        assertTrue(ScreenService.setTextOnDisplay(7, PACKAGE + "/.FormActivity", "目标字段"));
        assertEquals("", next.getText().toString());
        assertEquals(1, performed[0]);
    }

    @Test public void aRemovedFieldCannotBeVerifiedByAnotherFieldWithTheExpectedText() throws Exception {
        AccessibilityNodeInfo original = node(true, "old");
        AccessibilityNodeInfo replacement = node(true, "expected");
        Shadows.shadowOf(root).addChild(replacement);
        replacement.setFocused(true);

        Method read = ScreenService.class.getDeclaredMethod("readBackText",
            AccessibilityNodeInfo.class, AccessibilityNodeInfo.class);
        read.setAccessible(true);
        assertNull(read.invoke(null, root, original));
    }

    private static AccessibilityNodeInfo node(boolean editable, String text) {
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
