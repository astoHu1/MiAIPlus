package io.github.miaiplus;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class NativeConversationHookIntentTest {
    private static final String[] WAKE_ACTIONS = {
        "com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST",
        "com.miui.voicetrigger.ACTION_VOICE_TRIGGER_START_VOICEASSIST",
        "com.xiaoai.ACTION_BLUETOOTH_START_VOICEASSIST",
        Intent.ACTION_VOICE_COMMAND,
        Intent.ACTION_ASSIST
    };

    @Test public void voiceAndHardwareWakeActionsAreRecognized() {
        for (String action : WAKE_ACTIONS) {
            Intent intent = new Intent(action);
            assertTrue(action, NativeConversationHook.isWakeIntent(intent));
            assertFalse(action, NativeConversationHook.isLauncherIntent(intent));
        }
    }

    @Test public void explicitConversationReturnIsNeverAFreshVoiceWake() {
        for (String action : WAKE_ACTIONS) {
            Intent intent = conversationReturn(action);
            assertTrue(action, NativeConversationHook.hasExplicitRoute(intent));
            assertFalse(action, NativeConversationHook.isWakeIntent(intent));
            assertFalse(action, NativeConversationHook.isLauncherIntent(intent));
        }
    }

    @Test public void onlyAnActualLauncherEntryStartsLauncherWake() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        assertTrue(NativeConversationHook.isLauncherIntent(launcher));
        assertFalse(NativeConversationHook.isWakeIntent(launcher));
        assertFalse(NativeConversationHook.isLauncherIntent(new Intent(Intent.ACTION_MAIN)));
        assertFalse(NativeConversationHook.isLauncherIntent(
            new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_LAUNCHER)));
        assertFalse(NativeConversationHook.isLauncherIntent(
            new Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
        assertTrue(NativeConversationHook.isLauncherIntent(
            new Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    @Test public void completionCardAndHistoryNavigationDoNotBecomeLauncherWake() {
        Intent routedLauncher = conversationReturn(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        assertTrue(NativeConversationHook.hasExplicitRoute(routedLauncher));
        assertFalse(NativeConversationHook.isLauncherIntent(routedLauncher));
        assertFalse(NativeConversationHook.isWakeIntent(routedLauncher));
        Intent history = conversationReturn(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        assertTrue(NativeConversationHook.hasExplicitRoute(history));
        assertFalse(NativeConversationHook.isLauncherIntent(history));
        assertFalse(NativeConversationHook.isWakeIntent(history));
    }

    @Test public void nativeHomeShortcutStartsWakeButHistoryAndExplicitRoutesDoNot() {
        String action = "com.xiaomi.voiceassistant.ACTION_SHORTCUT_START";
        Intent shortcut = new Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra("from", "HOME_ICON_CLICK");
        assertTrue(NativeConversationHook.isLauncherIntent(shortcut));
        assertFalse(NativeConversationHook.isWakeIntent(shortcut));
        assertFalse(NativeConversationHook.isLauncherIntent(
            new Intent(shortcut).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
        assertFalse(NativeConversationHook.isLauncherIntent(conversationReturn(action)));
    }

    @Test public void ordinaryActivityAndToolResultIntentsDoNotStartFreshConversation() {
        Intent[] ordinary = {
            new Intent(),
            new Intent(Intent.ACTION_VIEW),
            new Intent(Intent.ACTION_SEND),
            new Intent("com.miui.voiceassist.MIAIPLUS_STOP"),
            new Intent("com.miui.voiceassist.MIAIPLUS_CONFIRM_ACTION"),
            new Intent("io.github.miaiplus.TOOL_RESULT").putExtra("result", "completed"),
            new Intent().setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.mainui.MainActivity")
        };
        for (Intent intent : ordinary) {
            assertFalse(intent.toString(), NativeConversationHook.isWakeIntent(intent));
            assertFalse(intent.toString(), NativeConversationHook.isLauncherIntent(intent));
            assertFalse(intent.toString(), NativeConversationHook.hasExplicitRoute(intent));
        }
        assertFalse(NativeConversationHook.isWakeIntent(null));
        assertFalse(NativeConversationHook.isLauncherIntent(null));
        assertFalse(NativeConversationHook.hasExplicitRoute(null));
    }

    private static Intent conversationReturn(String action) {
        // The host navigation object is unavailable in JVM tests; the routing marker is sufficient
        // to exercise the same exclusion used by completion notifications and explicit history.
        Bundle page = new Bundle();
        page.putString("chatId", "owned-conversation");
        return new Intent(action).putExtra("page_navigation_data", page);
    }
}
