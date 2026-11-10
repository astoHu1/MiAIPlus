package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.SharedPreferences;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class NativeHostErrorHookTest {
    private SharedPreferences prefs;

    @Before public void setUp() {
        prefs = Config.prefs(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
        PhoneUseLog.init(RuntimeEnvironment.getApplication());
        HostTrace.clear();
        ShadowLog.clear();
    }

    @After public void cleanup() throws Exception {
        drain();
        prefs.edit().clear().commit();
        PhoneUseLog.disposeRemote();
        HostTrace.clear();
        HostCompatibility.select("8.2.65.4714", 508002065L);
    }

    @Test public void disabledDiagnosticsDoNotReadHostErrorPayload() throws Exception {
        UiError error = new UiError();
        PhoneUseLog.hostInspect(() -> NativeHostErrorHook.recordUiError(error, HostTrace.snapshot()));
        drain();
        assertEquals(0, error.reads);
        assertTrue(ShadowLog.getLogsForTag(PhoneUseLog.TAG).isEmpty());
    }

    @Test public void hostErrorKeepsCodesAndCapturedRouteWithoutKeepingItsMessage() throws Exception {
        prefs.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        String route = HostTrace.route(true);
        HostTrace.Snapshot captured = HostTrace.snapshot();
        HostTrace.route(false);
        UiError error = new UiError();
        PhoneUseLog.hostInspect(() -> NativeHostErrorHook.recordUiError(error, captured));
        drain();
        var entries = ShadowLog.getLogsForTag(PhoneUseLog.TAG);
        assertEquals(1, entries.size());
        String text = entries.get(0).msg;
        JSONObject record = new JSONObject(text);
        assertEquals("host_error", record.getString("event"));
        assertEquals("1001", record.getString("code"));
        assertEquals("NOT_SUPPORTED", record.getString("upstream"));
        assertEquals(route, record.getString("route"));
        assertTrue(record.getBoolean("forcedAgent"));
        assertFalse(text.contains("private-payload"));
        assertEquals("[NOT_SUPPORTED] private-payload", error.message);
    }

    @Test public void errorCategoriesNeverInvokeArbitraryToString() {
        Object untrusted = new Object() {
            @Override public String toString() { throw new AssertionError("must not serialize arbitrary values"); }
        };
        assertEquals("other", NativeHostErrorHook.code(untrusted));
        assertEquals("other", NativeHostErrorHook.category(untrusted));
        assertEquals("SERVER", NativeHostErrorHook.category(Origin.SERVER));
        assertEquals("other", NativeHostErrorHook.code("private-payload"));
        assertEquals("other", NativeHostErrorHook.code("[NOT_SUPPORTED] private-payload"));
        assertEquals("429", NativeHostErrorHook.code(429));
    }

    @Test public void missingOptionalAgentMetadataDoesNotLoseTheErrorCode() throws Exception {
        prefs.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        HostCompatibility.select("8.3.30.4420", 508003030L);
        NativeHostErrorHook.recordAgentError(new AgentError(), HostTrace.snapshot());
        var entries = ShadowLog.getLogsForTag(PhoneUseLog.TAG);
        assertEquals(1, entries.size());
        JSONObject record = new JSONObject(entries.get(0).msg);
        assertEquals("host_agent_error", record.getString("event"));
        assertEquals("300101", record.getString("code"));
        assertEquals(-1, record.getInt("httpStatus"));
        assertEquals("other", record.getString("origin"));
        assertFalse(entries.get(0).msg.contains("private-payload"));
    }

    private static void drain() throws Exception {
        var field = PhoneUseLog.class.getDeclaredField("delivery");
        field.setAccessible(true);
        ((ThreadPoolExecutor)field.get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private enum Origin {
        SERVER;
        @Override public String toString() { return "private-payload"; }
    }

    public static final class UiError {
        public final String message = "[NOT_SUPPORTED] private-payload";
        public boolean d = true;
        public int reads;
        public int getErrorCode() { reads++; return 1001; }
        public String getErrorMessage() { reads++; return message; }
    }

    public static final class AgentError {
        public String getCode() { return "300101"; }
        public String getMessage() { return "private-payload"; }
    }
}
