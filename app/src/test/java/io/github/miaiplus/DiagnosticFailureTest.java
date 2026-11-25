package io.github.miaiplus;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import io.github.miaiplus.hook.Reflector;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class DiagnosticFailureTest {
    private SharedPreferences preferences;

    @Before public void setUp() {
        preferences = Config.prefs(RuntimeEnvironment.getApplication());
        preferences.edit().clear().commit();
        PhoneUseLog.init(RuntimeEnvironment.getApplication());
        ShadowLog.clear();
    }

    @After public void tearDown() {
        preferences.edit().clear().commit();
        PhoneUseLog.disposeRemote();
    }

    @Test public void unwrapsWrappersAndLocatesTheRealFailureCauseAndFixedHostBindingLabel() throws Exception {
        IOException cause = new IOException("private-response-body");
        cause.setStackTrace(new StackTraceElement[]{frame("BackgroundDisplay", "request", 42)});
        IllegalStateException failure = new IllegalStateException("采集失败：private-goal", cause);
        failure.setStackTrace(new StackTraceElement[]{
            frame("ScreenService", "capture", 71),
            new StackTraceElement("android.os.Handler", "dispatchMessage", "private-path", 5),
            frame("NativeSession", "observe", 81)
        });
        Throwable wrapped = new ExecutionException(new CompletionException(new InvocationTargetException(
            new UndeclaredThrowableException(failure))));

        JSONObject description = describe(wrapped);
        assertEquals("IllegalStateException", description.getString("exception"));
        assertEquals("IOException", description.getString("causes"));
        assertTrue(description.getString("site").startsWith("ScreenService.capture"));
        assertTrue(description.getString("site").contains("NativeSession.observe"));
        assertTrue(description.getString("causeSite").startsWith("BackgroundDisplay.request"));
        assertEquals(PhoneUseLog.reference("采集失败"), description.getString("messageRef"));
        assertFalse(description.toString().contains("private-"));
        assertFalse(description.toString().contains("Handler"));
        assertFalse(description.toString().contains("ExecutionException"));
        assertFalse(description.toString().contains("InvocationTargetException"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
            () -> Reflector.findClass("fixture.diagnostic.MissingHost", getClass().getClassLoader()));
        JSONObject binding = describe(missing);
        assertEquals("Missing host class: fixture.diagnostic.MissingHost", binding.getString("binding"));
        assertEquals("ClassNotFoundException", binding.getString("causes"));
        assertTrue(binding.getString("site").contains("hook.Reflector.findClass"));
        assertEquals(PhoneUseLog.reference("Missing host class"), binding.getString("messageRef"));
    }

    @Test public void omitsMessagesUrlsPathsFreeTextAndUppercaseTokensThatAreNotHostBindings() throws Exception {
        for (Throwable failure : new Throwable[]{
            new ClassNotFoundException("https://private-host.example/private-page"),
            new NoSuchFieldException("/private/user/input.txt"),
            new NoSuchMethodException("host.method(private-input=value)"),
            new IllegalArgumentException("Missing host class: private goal with spaces"),
            new IllegalStateException("private-response-body")
        }) {
            failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.lang.reflect.Method", "invoke", "/private/source/path", 23)
            });
            JSONObject description = describe(failure);
            assertEquals(failure.getClass().getSimpleName(), description.getString("exception"));
            assertFalse(description.has("binding"));
            assertFalse(description.has("messageRef"));
            assertFalse(description.toString().contains("private"));
            assertFalse(description.toString().contains("https://"));
        }
        IllegalStateException uppercase = new IllegalStateException("PRIVATE_ACCOUNT_TOKEN_ABC123");
        uppercase.setStackTrace(new StackTraceElement[]{frame("NativeSession", "observe", 25)});
        JSONObject description = describe(uppercase);
        assertFalse(description.has("code"));
        assertFalse(description.has("binding"));
        assertFalse(description.toString().contains("PRIVATE_ACCOUNT_TOKEN_ABC123"));
        assertEquals(PhoneUseLog.reference("PRIVATE_ACCOUNT_TOKEN_ABC123"), description.getString("messageRef"));
    }

    @Test public void disabledHostInspectionDoesNotReadHostStateAndEnabledFailuresStayIsolated() {
        AtomicInteger inspections = new AtomicInteger();
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        assertEquals(0, inspections.get());

        preferences.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        PhoneUseLog.hostInspect(() -> { inspections.incrementAndGet(); throw new IllegalStateException("private-data"); });
        PhoneUseLog.hostInspect(() -> { inspections.incrementAndGet(); throw new NoClassDefFoundError("fixture.Missing"); });
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        assertEquals(3, inspections.get());
        assertTrue(ShadowLog.getLogsForTag(PhoneUseLog.TAG).isEmpty());

        preferences.edit().putBoolean(Config.PHONE_USE_LOG_KEY, false).commit();
        PhoneUseLog.hostInspect(inspections::incrementAndGet);
        assertEquals(3, inspections.get());
    }

    @Test public void recordsCarryProcessIdentityAndIncreasingSequenceThatPayloadCannotOverwrite() throws Exception {
        preferences.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        PhoneUseLog.event("capture_complete", "source", "accessibility", "event", "wrong",
            "time", -1, "elapsedMs", -1, "pid", -1, "seq", -1, "writer", "wrong", "ver", -1,
            "captureSource", "accessibility");
        PhoneUseLog.event("second_diagnostic");
        var logs = ShadowLog.getLogsForTag(PhoneUseLog.TAG);
        assertEquals(2, logs.size());
        JSONObject record = new JSONObject(logs.get(0).msg), second = new JSONObject(logs.get(1).msg);
        assertTrue(second.getLong("seq") > record.getLong("seq"));
        assertEquals(record.getInt("pid"), second.getInt("pid"));
        assertEquals(record.getString("writer"), second.getString("writer"));
        assertEquals(java.util.UUID.fromString(record.getString("writer")).toString(), record.getString("writer"));
        assertEquals("capture_complete", record.getString("event"));
        assertEquals("module", record.getString("source"));
        assertEquals("accessibility", record.getString("captureSource"));
        assertTrue(record.getLong("time") >= 0);
        assertTrue(record.getLong("elapsedMs") >= 0);
        assertEquals(android.os.Process.myPid(), record.getInt("pid"));
        assertTrue(record.getLong("seq") > 0);
        assertEquals(BuildConfig.VERSION_CODE, record.getInt("ver"));
        assertNotEquals("wrong", record.getString("writer"));
    }

    @Test public void environmentExportClassifiesConfigurationHookStateAndExitsWithoutPrivateValues() throws Exception {
        preferences.edit().putString("endpoint", "https://private-host.example")
            .putString("apiKey", "private-secret").putString("model", "private-model")
            .putString("api", "anthropic")
            .putString("planner", "private-planner").putString("protocol", "private-protocol")
            .putString("hook_status", new JSONObject().put("phase", "ready")
                .put("stage", "https://private-host.example").put("credential", "private-secret").toString())
            .putString("hook_reload", new JSONObject().put("phase", "reload_refused")
                .put("binding", "/private/user/input.txt").put("reason", "private-user-input").toString())
            .putString("hook_report", "probe: private-report").commit();
        ApplicationExitInfo exit = ReflectionHelpers.newInstance(ApplicationExitInfo.class);
        ReflectionHelpers.setField(exit, "mReason", ApplicationExitInfo.REASON_CRASH);
        ReflectionHelpers.setField(exit, "mDescription", "private-user-data in exception /private/path");
        ReflectionHelpers.setField(exit, "mTimestamp", System.currentTimeMillis() - 1000L);
        Shadows.shadowOf(context().getSystemService(ActivityManager.class)).addApplicationExitInfo(exit);

        JSONObject environment = DiagnosticEnvironment.snapshot(context());
        JSONObject config = environment.getJSONObject("config");
        assertEquals("unknown", config.getString("planner"));
        assertEquals("unknown", config.getString("protocol"));
        assertEquals("anthropic", config.getString("api"));
        assertTrue(config.getBoolean("endpointConfigured"));
        assertTrue(config.getBoolean("modelConfigured"));
        assertTrue(config.getBoolean("apiKeyConfigured"));
        JSONObject hook = environment.getJSONObject("hook");
        assertEquals("other", hook.getString("reportKind"));
        assertFalse(hook.has("report"));
        assertEquals("ready", hook.getJSONObject("status").getString("phase"));
        assertEquals("reload_refused", hook.getJSONObject("lastReload").getString("phase"));
        assertFalse(hook.getJSONObject("status").has("credential"));
        JSONObject recorded = environment.getJSONObject("process").getJSONArray("recentExits").getJSONObject(0);
        assertEquals(ApplicationExitInfo.REASON_CRASH, recorded.getInt("reason"));
        assertFalse(recorded.has("description"));
        assertFalse(hook.toString().contains("private"));
        assertFalse(environment.toString().contains("private-"));
        assertFalse(environment.toString().contains("/private/path"));
    }

    @Test public void brokenConfigurationAndHookStateDoNotPreventOtherDiagnosticSections() throws Exception {
        preferences.edit().putString("maxSteps", "private-invalid-type")
            .putString("hook_status", "private-invalid-json").commit();

        JSONObject environment = DiagnosticEnvironment.snapshot(context());
        assertEquals("ClassCastException", environment.getJSONObject("config").getString("error"));
        assertEquals("JSONException", environment.getJSONObject("hook").getString("error"));
        assertTrue(environment.getJSONObject("app").has("versionCode"));
        assertTrue(environment.getJSONObject("device").has("androidApi"));
        assertTrue(environment.getJSONObject("process").has("pid"));
        assertFalse(environment.toString().contains("private-"));
    }

    @Test public void installedHostProfileIsReportedWithoutChangingActiveBindings() throws Exception {
        PackageInfo host = new PackageInfo();
        host.packageName = DiagnosticEnvironment.HOST;
        host.versionName = "8.3.30.4420";
        host.setLongVersionCode(508003030L);
        Shadows.shadowOf(context().getPackageManager()).installPackage(host);
        boolean selectedBeforeExport = HostCompatibility.modern();

        JSONObject environment = DiagnosticEnvironment.snapshot(context());
        assertEquals("modern", environment.getJSONObject("host").getString("profile"));
        assertEquals(selectedBeforeExport, HostCompatibility.modern());
    }

    private static Application context() { return RuntimeEnvironment.getApplication(); }

    private static JSONObject describe(Throwable failure) throws Exception {
        Object[] fields = PhoneUseLog.describe(failure);
        JSONObject description = new JSONObject();
        for (int index = 0; index + 1 < fields.length; index += 2)
            description.put(String.valueOf(fields[index]), fields[index + 1]);
        return description;
    }

    private static StackTraceElement frame(String type, String method, int line) {
        return new StackTraceElement("io.github.miaiplus." + type, method, "private-source-path", line);
    }
}
