package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class PhoneUseLogTest {
    private SharedPreferences prefs;
    @Before public void setUp() {
        prefs = Config.prefs(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
        PhoneUseLog.init(RuntimeEnvironment.getApplication());
        FrameStore.clear(); ShadowLog.clear();
    }
    @After public void tearDown() {
        prefs.edit().clear().commit(); FrameStore.clear(); PhoneUseLog.disposeRemote();
    }
    private void enable(boolean enabled) { prefs.edit().putBoolean(Config.PHONE_USE_LOG_KEY, enabled).commit(); }
    private List<ShadowLog.LogItem> logs() { return ShadowLog.getLogsForTag(PhoneUseLog.TAG); }
    private String output() { return logs().stream().map(item -> item.msg).reduce("", (a, b) -> a + "\n" + b); }

    @Test public void disabledByDefaultAndToggleAffectsExistingScopeWithoutRestart() {
        assertFalse(new Config(prefs).phoneUseDetailedLog);
        try (PhoneUseLog.Scope ignored = PhoneUseLog.scope("task-id", 2)) {
            PhoneUseLog.event("disabled"); assertTrue(logs().isEmpty());
            enable(true); PhoneUseLog.event("enabled"); assertEquals(1, logs().size());
            enable(false); PhoneUseLog.event("disabled_again"); assertEquals(1, logs().size());
        }
        assertTrue(output().contains("enabled")); assertFalse(output().contains("task-id"));
    }
    @Test public void actionAndFailureLogsNeverContainUserTextOrExceptionMessages() throws Exception {
        enable(true);
        Action action = new Action(new JSONObject().put("action", "type").put("text", "private-input-secret")
            .put("message", "private-message-secret").put("intent", "private-intent-secret"));
        PhoneUseLog.action("action_proposed", action, null);
        PhoneUseLog.failure("input", new IllegalStateException("private-exception-secret"));
        assertTrue(output().contains("textLength")); assertTrue(output().contains("IllegalStateException"));
        assertFalse(output().contains("secret"));
    }
    @Test public void persistentJournalSurvivesDisableAndOmitsPrivateActionContents() throws Exception {
        var store = DiagnosticLogStore.get(RuntimeEnvironment.getApplication()); store.clear();
        enable(true);
        PhoneUseLog.action("action_proposed", new Action(new JSONObject().put("action", "type")
            .put("text", "private-input-secret").put("message", "private-goal-secret")), null);
        enable(false); PhoneUseLog.event("should_not_persist");
        String text = String.join("\n", store.snapshot().lines());
        assertTrue(text.contains("action_proposed")); assertTrue(text.contains("\"source\":\"module\""));
        assertFalse(text.contains("secret")); assertFalse(text.contains("should_not_persist"));
        store.clear();
    }
    @Test public void hostSwitchWorksDuringApplicationAttachRefreshesAndFailsClosedWhenBridgeIsUnavailable() throws Exception {
        var application = RuntimeEnvironment.getApplication();
        LogSettingsProvider provider = new LogSettingsProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(application, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(info.authority, provider);
        var field = PhoneUseLog.class.getDeclaredField("preferences"); field.setAccessible(true); field.set(null, null);
        Application attaching = new Application() {
            @Override public android.content.Context getApplicationContext() { return null; }
            @Override public android.content.ContentResolver getContentResolver() { return application.getContentResolver(); }
        };
        PhoneUseLog.initRemote(attaching);
        try {
            assertFalse(PhoneUseLog.enabled());
            provider.enabled = true;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            assertTrue(PhoneUseLog.enabled());
            provider.enabled = false;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            assertFalse(PhoneUseLog.enabled());
            provider.unavailable = true;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            assertFalse(PhoneUseLog.enabled());
        } finally { PhoneUseLog.init(application); PhoneUseLog.disposeRemote(); }
    }
    @Test public void traceFollowsMainThreadWorkAndRestoresPreviousThreadContext() throws Exception {
        enable(true);
        var executor = Executors.newSingleThreadExecutor();
        try (PhoneUseLog.Scope ignored = PhoneUseLog.scope("task-id", 3)) {
            PhoneUseLog.observation();
            JSONObject trace = PhoneUseLog.metadata();
            JSONObject propagated = executor.submit(PhoneUseLog.propagate(PhoneUseLog::metadata)).get();
            assertEquals(trace.toString(), propagated.toString());
            assertEquals("", executor.submit(PhoneUseLog::metadata).get().getString("session"));
            assertFalse(trace.getString("observation").isEmpty());
        } finally { executor.shutdown(); }
        assertEquals("", PhoneUseLog.metadata().getString("session"));
    }
    @Test public void frameRemainsOneUseAndHasSameImageWithLoggingOnOrOff() throws Exception {
        ScreenService.Frame frame = frame();
        for (boolean enabled : new boolean[]{false, true}) {
            enable(enabled);
            try (PhoneUseLog.Scope ignored = PhoneUseLog.scope("task", 4)) {
                PhoneUseLog.observation(); PhoneUseLog.frame(frame);
                String marker = FrameStore.put(frame, 1234);
                String token = marker.substring("MIAIPLUS_FRAME:".length());
                JSONObject payload = new JSONObject(new String(FrameStore.take(token, 1234), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(frame.image(), payload.getString("image"));
                assertEquals(8, payload.getInt("width"));
                assertEquals(enabled, payload.has("diagnostics"));
                if (enabled) assertEquals(4, payload.getJSONObject("diagnostics").getInt("step"));
                assertThrows(java.io.FileNotFoundException.class, () -> FrameStore.take(token, 1234));
                assertFalse(output().contains(token)); assertFalse(output().contains(frame.image()));
            }
        }
    }
    @Test public void expiredAndWrongOwnerFramesStillFailWithDiagnosticReasons() throws Exception {
        enable(true);
        String token = FrameStore.put(frame(), 1234).substring("MIAIPLUS_FRAME:".length());
        assertThrows(java.io.FileNotFoundException.class, () -> FrameStore.take(token, 5678));
        assertTrue(output().contains("wrong_uid"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61));
        assertThrows(java.io.FileNotFoundException.class, () -> FrameStore.take(token, 1234));
        assertTrue(output().contains("expired"));
    }
    @Test public void requestLogsCorrelateChatAndResponsesImagesWithoutLeakingBodies() throws Exception {
        enable(true);
        ScreenService.Frame frame = frame();
        try (PhoneUseLog.Scope ignored = PhoneUseLog.scope("task", 5)) {
            PhoneUseLog.observation(); PhoneUseLog.frame(frame);
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", "private-prompt-secret"))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                    .put("url", "data:image/jpeg;base64," + frame.image())));
            JSONObject chat = new JSONObject().put("model", "private-model-secret")
                .put("messages", new JSONArray().put(ModelClient.message("user", content)));
            PhoneUseLog.request(chat, "chat");
            PhoneUseLog.request(ModelProtocol.request(chat), "responses");
            String digest = PhoneUseLog.reference(frame.image());
            assertEquals(2, logs().stream().filter(item -> item.msg.contains("request_image") && item.msg.contains(digest)).count());
            PhoneUseLog.request(new JSONObject().put("messages", new JSONArray()
                .put(ModelClient.message("tool", "CONTINUE：控屏尚未完成。 private-prompt-secret"))), "chat");
            assertTrue(output().contains("\"images\":0"));
            assertFalse(output().contains("secret")); assertFalse(output().contains(frame.image()));
        }
    }
    @Test public void requestBurdenCountsChatAndResponsesRolesCallsAndUtf8WithoutChangingBody() throws Exception {
        enable(true);
        String system = "规则-secret", user = "任务😀-secret", assistant = "结果-secret", result = "操作-secret";
        JSONObject call = new JSONObject().put("id", "call-private-secret").put("type", "function")
            .put("function", new JSONObject().put("name", "private-tool-secret").put("arguments", "{\"text\":\"私密-secret\"}"));
        JSONArray tools = new JSONArray().put(new JSONObject().put("type", "function").put("function",
            new JSONObject().put("name", "private-tool-secret").put("description", "工具-secret")
                .put("parameters", new JSONObject().put("type", "object"))));
        JSONObject chat = new JSONObject().put("model", "private-model-secret").put("tools", tools)
            .put("messages", new JSONArray().put(ModelClient.message("system", system))
                .put(ModelClient.message("user", user))
                .put(ModelClient.message("assistant", assistant).put("tool_calls", new JSONArray().put(call)))
                .put(ModelClient.message("tool", result).put("tool_call_id", call.getString("id"))));
        try (PhoneUseLog.Scope ignored = PhoneUseLog.scope("task", 1)) {
            for (JSONObject body : new JSONObject[]{chat, ModelProtocol.request(chat)}) {
                ShadowLog.clear();
                String serialized = body.toString();
                PhoneUseLog.request(body, body == chat ? "chat" : "responses", serialized);
                JSONObject record = lastRequest();
                assertEquals(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, record.getLong("requestBytes"));
                assertEquals(system.length(), record.getLong("systemTextChars"));
                assertEquals(user.length(), record.getLong("userTextChars"));
                assertEquals(assistant.length(), record.getLong("assistantTextChars"));
                assertEquals(result.length(), record.getLong("toolTextChars"));
                assertEquals(system.length() + user.length() + assistant.length() + result.length(), record.getLong("textChars"));
                assertEquals(0, record.getLong("otherTextChars"));
                assertEquals(1, record.getInt("tools"));
                assertEquals(body.getJSONArray("tools").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    record.getLong("toolsBytes"));
                assertEquals(1, record.getInt("functionCalls"));
                assertEquals(1, record.getInt("functionResults"));
                assertEquals(call.getJSONObject("function").getString("arguments").length(), record.getLong("argumentChars"));
                assertEquals(serialized, body.toString());
                assertFalse(output().contains("secret"));
            }
        }
    }
    @Test public void responsesFunctionResultIdentifiesPhoneUseWithoutImagesOrThreadScope() throws Exception {
        enable(true);
        String result = "CONTINUE：控屏尚未完成。\n会话=private-session-secret；目标=private-goal-secret";
        JSONObject reasoning = new JSONObject().put("type", "reasoning").put("encrypted_content", "opaque-private-secret");
        JSONObject body = new JSONObject().put("input", new JSONArray().put(reasoning)
            .put(new JSONObject().put("type", "function_call_output").put("call_id", "private-call-secret").put("output", result)));
        PhoneUseLog.request(body, "responses");
        JSONObject record = lastRequest();
        assertEquals(result.length(), record.getLong("toolTextChars"));
        assertEquals(reasoning.getString("encrypted_content").length(), record.getLong("opaqueChars"));
        assertEquals(1, record.getInt("functionResults"));
        assertEquals(0, record.getInt("images"));
        assertFalse(output().contains("secret"));
    }
    @Test public void disabledRequestInspectionDoesNotSerializeOrReadDiagnosticCopies() {
        enable(false);
        JSONObject body = new JSONObject() {
            @Override public String toString() { throw new AssertionError("disabled diagnostics serialized the request"); }
        };
        PhoneUseLog.request(body, "chat");
        PhoneUseLog.request(body, "chat", (String)null);
        PhoneUseLog.request(body, "chat", 1024L);
        assertTrue(logs().isEmpty());
    }
    @Test public void unassociatedOrdinaryRequestDoesNotEmitPhoneUseMetadata() throws Exception {
        enable(true);
        PhoneUseLog.request(new JSONObject().put("messages", new JSONArray()
            .put(ModelClient.message("user", "ordinary-private-secret"))), "chat");
        assertTrue(logs().isEmpty());
    }
    private JSONObject lastRequest() throws Exception {
        for (int i = logs().size() - 1; i >= 0; i--) {
            JSONObject record = new JSONObject(logs().get(i).msg);
            if ("model_request".equals(record.optString("event"))) return record;
        }
        throw new AssertionError("model_request missing");
    }
    @Test
    @org.robolectric.annotation.Config(shadows = FailingLog.class)
    public void loggingBackendFailureDoesNotBreakFrameDelivery() throws Exception {
        enable(true);
        ScreenService.Frame frame = frame();
        PhoneUseLog.frame(frame);
        String token = FrameStore.put(frame, 1234).substring("MIAIPLUS_FRAME:".length());
        assertEquals(frame.image(), new JSONObject(new String(FrameStore.take(token, 1234),
            java.nio.charset.StandardCharsets.UTF_8)).getString("image"));
    }
    @Implements(Log.class)
    public static class FailingLog {
        @Implementation protected static int i(String tag, String text) { throw new IllegalStateException("logger unavailable"); }
    }
    public static class LogSettingsProvider extends android.content.ContentProvider {
        boolean enabled, unavailable;
        @Override public boolean onCreate() { return true; }
        @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
            assertEquals("phone_use_log_settings", method);
            if (unavailable) throw new IllegalStateException("bridge unavailable");
            var result = new android.os.Bundle(); result.putBoolean("enabled", enabled); return result;
        }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(android.net.Uri uri) { return null; }
        @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
        @Override public int delete(android.net.Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] args) { return 0; }
    }
    private ScreenService.Frame frame() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)); bitmap.recycle();
        return new ScreenService.Frame(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
            "private-page-secret", 1080, 1920, "fixture.app/.Search", 8, 8, 7, "generation");
    }
}
