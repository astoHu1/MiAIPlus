package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowBinder;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class PhoneUsePluginGateTest {
    private final Map<Field, Object> previousFields = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private Map<String, ?> previousPreferences;
    private Context context;
    private BridgeProvider provider;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ShadowBinder.setCallingUid(Process.myUid());
        prefs = Config.prefs(context);
        previousPreferences = new LinkedHashMap<>(prefs.getAll());
        prefs.edit().clear().putBoolean("enabled", true).putString("planner", "native").commit();
        replace(NativeSession.class, "active", null);
        // A deliberately occupied preflight guarantees every accepted gate exits before reserving a worker.
        // No root helper, display, accessibility action or screen job is started by this fixture.
        replace(BridgeProvider.class, "backgroundProbeRunning", true);
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(context, info);
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> saved : previousFields.entrySet()) saved.getKey().set(null, saved.getValue());
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> saved : previousPreferences.entrySet()) {
            Object value = saved.getValue();
            if (value instanceof String) editor.putString(saved.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(saved.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(saved.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(saved.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(saved.getKey(), (Float) value);
            else if (value instanceof Set<?>) editor.putStringSet(saved.getKey(), strings(value));
        }
        editor.commit();
        ShadowBinder.setCallingUid(Process.myUid());
    }

    @Test public void legacyForegroundContinuationChecksOriginalPluginAfterDefaultChangesToBackground() throws Exception {
        Object session = activate(false, "");
        String query = query(session, "");
        prefs.edit().putBoolean("backgroundDisplay", true).commit();
        PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, false);
        PluginSettings.setEnabled(context, PluginSettings.BACKGROUND_ID, true);
        assertPluginStopped(start(query));
        assertSame(session, field(NativeSession.class, "active").get(null));
        assertFalse(NativeSession.continuationBackground(query, Process.myUid(), ""));
        assertFalse(field(session.getClass(), "initialBackground").getBoolean(session));
        assertFalse(run(session).cancelled.get());
    }

    @Test public void disablingNewDefaultDoesNotBlockLegacyForegroundSessionWhoseOriginalPluginIsEnabled() throws Exception {
        Object session = activate(false, "");
        prefs.edit().putBoolean("backgroundDisplay", true).commit();
        PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, true);
        PluginSettings.setEnabled(context, PluginSettings.BACKGROUND_ID, false);
        Bundle result = start(query(session, ""));
        assertFalse(result.containsKey("id"));
        assertTrue(result.getString("error", ""), result.getString("error", "").contains("后台显示检测进行中"));
        assertSame(session, field(NativeSession.class, "active").get(null));
        assertFalse(run(session).cancelled.get());
    }

    @Test public void legacyBackgroundSessionKeepsOriginalPluginAfterExplicitForegroundHandoff() throws Exception {
        Object session = activate(true, "");
        field(session.getClass(), "background").setBoolean(session, false);
        prefs.edit().putBoolean("backgroundDisplay", false).commit();
        PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, true);
        PluginSettings.setEnabled(context, PluginSettings.BACKGROUND_ID, false);
        String query = query(session, "");
        assertTrue(NativeSession.continuationBackground(query, Process.myUid(), ""));
        assertTrue(field(session.getClass(), "initialBackground").getBoolean(session));
        assertPluginStopped(start(query));
        assertFalse("preflight must not switch a handed-off session back to its old display",
            field(session.getClass(), "background").getBoolean(session));
        assertFalse(run(session).cancelled.get());
    }

    @Test public void typedBackgroundContinuationStillBelongsToBackgroundPluginAfterHandoff() throws Exception {
        Object session = activate(true, PhoneUseRouting.BACKGROUND_TOOL);
        field(session.getClass(), "background").setBoolean(session, false);
        prefs.edit().putBoolean("backgroundDisplay", false).commit();
        PluginSettings.setEnabled(context, PluginSettings.FOREGROUND_ID, true);
        PluginSettings.setEnabled(context, PluginSettings.BACKGROUND_ID, false);
        String query = query(session, PhoneUseRouting.BACKGROUND_TOOL);
        assertTrue(NativeSession.continuationBackground(query, Process.myUid(), PhoneUseRouting.BACKGROUND_TOOL));
        assertPluginStopped(start(query));
        assertSame(session, field(NativeSession.class, "active").get(null));
        assertFalse(run(session).cancelled.get());
    }

    @Test public void continuationBackendLookupRejectsStaleOwnerSessionToolExpiredAndCancelledWithoutChangingState() throws Exception {
        for (String invalid : new String[]{"owner", "session", "tool", "expired", "cancelled"}) {
            Object session = activate(true, "");
            RunState run = run(session);
            String query = invalid.equals("session")
                ? "MIAIPLUS:" + new JSONObject().put("session", UUID.randomUUID().toString())
                    .put("action", new JSONObject().put("action", "wait"))
                : query(session, "");
            if (invalid.equals("expired")) run.deadline = SystemClock.elapsedRealtime() - 1;
            if (invalid.equals("cancelled")) run.cancelled.set(true);
            long deadline = run.deadline;
            int owner = invalid.equals("owner") ? Process.myUid() + 1 : Process.myUid();
            String tool = invalid.equals("tool") ? PhoneUseRouting.FOREGROUND_TOOL : "";
            assertThrows(invalid, IllegalStateException.class,
                () -> NativeSession.continuationBackground(query, owner, tool));
            assertSame(session, field(NativeSession.class, "active").get(null));
            assertEquals(deadline, run.deadline);
            assertEquals(0, field(session.getClass(), "steps").getInt(session));
            assertEquals(invalid.equals("cancelled"), run.cancelled.get());
            assertTrue(field(session.getClass(), "initialBackground").getBoolean(session));
        }
    }

    private Object activate(boolean background, String sourceTool) throws Exception {
        Class<?> type = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class, String.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance("fixture", Process.myUid(), 20, null, background, sourceTool);
        field(NativeSession.class, "active").set(null, state);
        return state;
    }

    private static String query(Object state, String sourceTool) throws Exception {
        JSONObject request = new JSONObject().put("session", field(state.getClass(), "id").get(state))
            .put("action", new JSONObject().put("action", "wait"));
        if (!sourceTool.isEmpty()) request.put("origin_tool", sourceTool);
        return "MIAIPLUS:" + request;
    }

    private Bundle start(String query) {
        Bundle request = new Bundle(); request.putString("mode", "screen"); request.putString("query", query);
        return provider.call("start", null, request);
    }

    private static void assertPluginStopped(Bundle result) {
        assertFalse(result.containsKey("id"));
        assertFalse(result.containsKey("screen_task"));
        String error = result.getString("error", "");
        assertTrue(error, PhoneUseResult.endsNativeTurn(error));
        assertTrue(error, error.contains("对应 Phone Use 插件已停用"));
    }

    private static RunState run(Object state) throws Exception { return (RunState) field(state.getClass(), "run").get(state); }

    private void replace(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name); previousFields.put(field, field.get(null)); field.set(null, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    @SuppressWarnings("unchecked") private static Set<String> strings(Object value) { return (Set<String>) value; }
}
