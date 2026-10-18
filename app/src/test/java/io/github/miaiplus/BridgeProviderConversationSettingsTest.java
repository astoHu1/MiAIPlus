package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class BridgeProviderConversationSettingsTest {
    private final Map<Field, Object> savedState = new LinkedHashMap<>();
    private BridgeProvider provider;
    private SharedPreferences prefs;
    private Map<String, ?> savedPreferences;

    @Before public void setUp() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageCodePath() { return ""; }
        };
        prefs = Config.prefs(context);
        savedPreferences = new LinkedHashMap<>(prefs.getAll());
        prefs.edit().clear().commit();
        replace(BridgeProvider.class, "active", null);
        replace(BridgeProvider.class, "screenTaskId", null);
        replace(BridgeProvider.class, "screenTaskActive", false);
        replace(BridgeProvider.class, "screenResult", "");
        replace(NativeSession.class, "active", null);
        replace(NativeSession.class, "executing", false);
        replace(NativeSession.class, "closing", false);
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(context, info);
    }

    @After public void tearDown() throws Exception {
        // No worker jobs are started. Restore only fields owned by this fixture; never clear JOBS.
        for (Map.Entry<Field, Object> entry : savedState.entrySet()) entry.getKey().set(null, entry.getValue());
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : savedPreferences.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set<?>) editor.putStringSet(entry.getKey(), strings(value));
        }
        editor.commit();
    }

    @Test public void readsDefaultThenUpdatedAndDisabledTimeoutWithoutProviderRestart() {
        assertEquals(Config.DEFAULT_CONVERSATION_TIMEOUT_MINUTES, settings().getInt("conversationTimeoutMinutes"));
        assertFalse(settings().getBoolean("screenBusy"));
        prefs.edit().putInt("conversationTimeoutMinutes", 5).commit();
        assertEquals(5, settings().getInt("conversationTimeoutMinutes"));
        prefs.edit().putInt("conversationTimeoutMinutes", 0).commit();
        assertEquals(0, settings().getInt("conversationTimeoutMinutes"));
    }

    @Test public void wakeSettingsDoNotExportModelCredentialsOrScreenContents() throws Exception {
        prefs.edit().putBoolean("globalModel", true).putString("endpoint", "https://example.invalid/v1")
            .putString("apiKey", "fixture-secret").putString("model", "fixture-model").commit();
        field(BridgeProvider.class, "screenTaskId").set(null, "private-task-id");
        field(BridgeProvider.class, "screenResult").set(null, "private screen contents");
        Bundle result = settings();
        assertEquals(Set.of("conversationTimeoutMinutes", "screenBusy"), result.keySet());
        assertFalse(result.getBoolean("screenBusy"));
    }

    @Test public void nativeSessionBetweenStepsStaysBusyUntilActualFinish() throws Exception {
        Object session = activateNativeSession();
        assertFalse(field(BridgeProvider.class, "screenTaskActive").getBoolean(null));
        assertTrue("native session is busy even without a notification task", settings().getBoolean("screenBusy"));
        field(BridgeProvider.class, "screenTaskId").set(null, "native-task");
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        field(BridgeProvider.class, "screenResult").set(null, "CONTINUE：等待模型下一步");
        assertNull(field(BridgeProvider.class, "active").get(null));
        assertTrue(NativeSession.hasActiveSession());
        assertTrue(settings().getBoolean("screenBusy"));

        JSONObject finish = new JSONObject().put("session", field(session.getClass(), "id").get(session))
            .put("action", new JSONObject().put("action", "finish").put("message", "fixture done"));
        assertTrue(NativeSession.handle(new Config(prefs), "MIAIPLUS:" + finish, android.os.Process.myUid())
            .startsWith("模型报告任务完成"));

        assertFalse(NativeSession.hasPendingOperation());
        assertFalse(settings().getBoolean("screenBusy"));
        assertFalse(field(BridgeProvider.class, "screenTaskActive").getBoolean(null));
    }

    @Test public void reservedScreenStepStaysBusyThroughCancellationUntilWorkerUnwinds() throws Exception {
        RunState run = new RunState("preparing-screen", android.os.Process.myUid());
        run.screen = true;
        run.preparingScreen = true;
        field(BridgeProvider.class, "active").set(null, run);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        assertFalse(NativeSession.hasPendingOperation());
        assertTrue(settings().getBoolean("screenBusy"));
        run.cancel();
        assertTrue(settings().getBoolean("screenBusy"));
        field(BridgeProvider.class, "active").set(null, null);
        assertFalse(settings().getBoolean("screenBusy"));
    }

    @Test public void nativeExecutionAndCleanupBothPreventWakeRolloverWithoutLiveSession() throws Exception {
        for (String phase : new String[]{"executing", "closing"}) {
            field(NativeSession.class, phase).setBoolean(null, true);
            assertFalse(NativeSession.hasActiveSession());
            assertTrue(settings().getBoolean("screenBusy"));
            field(NativeSession.class, phase).setBoolean(null, false);
            assertFalse(settings().getBoolean("screenBusy"));
        }
    }

    @Test public void unrelatedChatAndStaleCompletedScreenDoNotRemainScreenBusy() throws Exception {
        RunState chat = new RunState("ordinary-chat", android.os.Process.myUid());
        field(BridgeProvider.class, "active").set(null, chat);
        field(BridgeProvider.class, "screenTaskId").set(null, "completed-screen");
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        assertFalse(settings().getBoolean("screenBusy"));
        assertSame(chat, field(BridgeProvider.class, "active").get(null));
        assertFalse(chat.cancelled.get());
    }

    private Bundle settings() { return provider.call("conversation_settings", null, null); }

    private Object activateNativeSession() throws Exception {
        Class<?> type = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance("fixture goal", android.os.Process.myUid(), 200, null, false);
        field(NativeSession.class, "active").set(null, session);
        return session;
    }

    private void replace(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name);
        savedState.put(field, field.get(null));
        field.set(null, value);
    }

    @SuppressWarnings("unchecked") private static Set<String> strings(Object value) { return (Set<String>) value; }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
