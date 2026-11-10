package io.github.miaiplus;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.SharedPreferences;
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
import org.robolectric.shadows.ShadowLog;

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

    @Test public void unwrapsFutureAndReflectionWrappersAndLocatesTheRealFailureAndCause() throws Exception {
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
    }

    @Test public void omitsMessagesUrlsPathsAndFreeTextThatIsNotAHostBinding() throws Exception {
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
    }

    @Test public void arbitraryUppercaseExceptionMessageIsNeverPublishedAsAnErrorCode() throws Exception {
        IllegalStateException failure = new IllegalStateException("PRIVATE_ACCOUNT_TOKEN_ABC123");
        failure.setStackTrace(new StackTraceElement[]{frame("NativeSession", "observe", 25)});

        JSONObject description = describe(failure);
        assertFalse(description.has("code"));
        assertFalse(description.has("binding"));
        assertFalse(description.toString().contains("PRIVATE_ACCOUNT_TOKEN_ABC123"));
        assertEquals(PhoneUseLog.reference("PRIVATE_ACCOUNT_TOKEN_ABC123"), description.getString("messageRef"));
    }

    @Test public void preservesFixedReflectorLookupLabelForMissingVersionPinnedBinding() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Reflector.findClass("fixture.diagnostic.MissingHost", getClass().getClassLoader()));

        JSONObject description = describe(failure);
        assertEquals("Missing host class: fixture.diagnostic.MissingHost", description.getString("binding"));
        assertEquals("ClassNotFoundException", description.getString("causes"));
        assertTrue(description.getString("site").contains("hook.Reflector.findClass"));
        assertEquals(PhoneUseLog.reference("Missing host class"), description.getString("messageRef"));
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

    @Test public void emittedRecordsCarryVersionAndIncreasingPerProcessSequence() throws Exception {
        preferences.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        PhoneUseLog.event("first_diagnostic");
        PhoneUseLog.event("second_diagnostic");

        var logs = ShadowLog.getLogsForTag(PhoneUseLog.TAG);
        assertEquals(2, logs.size());
        JSONObject first = new JSONObject(logs.get(0).msg), second = new JSONObject(logs.get(1).msg);
        assertEquals(BuildConfig.VERSION_CODE, first.getInt("ver"));
        assertEquals(BuildConfig.VERSION_CODE, second.getInt("ver"));
        assertTrue(first.getLong("seq") > 0);
        assertTrue(second.getLong("seq") > first.getLong("seq"));
        assertEquals(first.getInt("pid"), second.getInt("pid"));
        assertEquals(first.getString("writer"), second.getString("writer"));
        assertEquals(java.util.UUID.fromString(first.getString("writer")).toString(), first.getString("writer"));
    }

    @Test public void eventPayloadCannotOverwriteProcessIdentityOrOrderingFields() throws Exception {
        preferences.edit().putBoolean(Config.PHONE_USE_LOG_KEY, true).commit();
        PhoneUseLog.event("capture_complete", "source", "accessibility", "event", "wrong",
            "time", -1, "elapsedMs", -1, "pid", -1, "seq", -1, "writer", "wrong", "ver", -1,
            "captureSource", "accessibility");
        JSONObject record = new JSONObject(ShadowLog.getLogsForTag(PhoneUseLog.TAG).get(0).msg);
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
