package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Process;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowBinder;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class BridgeProviderPromptSettingsTest {
    private static final int HOST = 20211;
    private static final int STRANGER = 20212;
    private static final Set<String> PROMPT_KEYS = Set.of("mode", "text", "routeText", "applyVoice", "applyStandalone");
    private static final Set<String> CONVERSATION_KEYS = Set.of("conversationTimeoutMinutes", "screenBusy",
        SpeechReplyPolicy.KEY);

    private final Map<Field, Object> savedState = new LinkedHashMap<>();
    private Context context;
    private BridgeProvider provider;
    private SharedPreferences prefs;
    private Map<String, ?> savedPreferences;
    private String[] previousHostPackages;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
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
        ShadowBinder.setCallingUid(Process.myUid());
        previousHostPackages = context.getPackageManager().getPackagesForUid(HOST);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(HOST, "com.miui.voiceassist");
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(context, info);
    }

    @After public void tearDown() throws Exception {
        ShadowBinder.setCallingUid(Process.myUid());
        for (Map.Entry<Field, Object> entry : savedState.entrySet()) entry.getKey().set(null, entry.getValue());
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(HOST,
            previousHostPackages == null ? new String[0] : previousHostPackages);
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

    @Test public void promptSettingsExposeExactlyTheContractKeysAndReadLiveValuesWithoutRestart() {
        for (int uid : new int[]{Process.myUid(), HOST}) {
            ShadowBinder.setCallingUid(uid);
            Bundle defaults = prompt();
            assertEquals(PROMPT_KEYS, defaults.keySet());
            assertEquals("off", defaults.getString("mode"));
            assertEquals("", defaults.getString("text"));
            assertFalse(defaults.getBoolean("routeText"));
            assertFalse(defaults.getBoolean("applyVoice"));
            assertFalse(defaults.getBoolean("applyStandalone"));
        }
        String text = "字".repeat(Config.MAX_CUSTOM_PROMPT_CHARS + 30);
        prefs.edit().putString(Config.CUSTOM_PROMPT_MODE_KEY, "append").putString(Config.CUSTOM_PROMPT_TEXT_KEY, text)
            .putBoolean(Config.PROMPT_ROUTE_TEXT_CHAT_KEY, true).putBoolean(Config.PROMPT_APPLY_VOICE_KEY, true)
            .putBoolean(Config.PROMPT_APPLY_STANDALONE_CHAT_KEY, true).commit();
        Bundle updated = prompt();
        assertEquals(PROMPT_KEYS, updated.keySet());
        assertEquals("append", updated.getString("mode"));
        assertEquals(Config.MAX_CUSTOM_PROMPT_CHARS, updated.getString("text").length());
        assertTrue(text.startsWith(updated.getString("text")));
        assertTrue(updated.getBoolean("routeText"));
        assertTrue(updated.getBoolean("applyVoice"));
        assertTrue(updated.getBoolean("applyStandalone"));
        prefs.edit().putString(Config.CUSTOM_PROMPT_MODE_KEY, "not-a-mode").commit();
        assertEquals("off", prompt().getString("mode"));
    }

    @Test public void foreignUidIsRejectedAndNeitherBundleCarriesCredentialsOrScreenState() throws Exception {
        prefs.edit().putString("apiKey", "fixture-secret").putString("endpoint", "https://example.invalid/v1")
            .putString("model", "fixture-model").putString(Config.CUSTOM_PROMPT_TEXT_KEY, "私有提示词")
            .putString(Config.CUSTOM_PROMPT_MODE_KEY, "replace").commit();
        field(BridgeProvider.class, "screenTaskId").set(null, "private-task-id");
        field(BridgeProvider.class, "screenResult").set(null, "private screen contents");
        ShadowBinder.setCallingUid(STRANGER);
        assertThrows(SecurityException.class, () -> provider.call("prompt_settings", null, null));
        ShadowBinder.setCallingUid(HOST);
        Bundle prompt = prompt();
        Bundle conversation = provider.call("conversation_settings", null, null);
        assertEquals(CONVERSATION_KEYS, conversation.keySet());
        assertFalse(conversation.keySet().toString(), conversation.keySet().stream().anyMatch(PROMPT_KEYS::contains));
        String exported = values(prompt) + values(conversation);
        for (String secret : new String[]{"fixture-secret", "https://example.invalid/v1", "fixture-model",
            "private-task-id", "private screen contents"}) {
            assertFalse(secret, exported.contains(secret));
        }
        assertEquals("私有提示词", prompt.getString("text"));
    }

    private static String values(Bundle bundle) {
        StringBuilder text = new StringBuilder();
        for (String key : bundle.keySet()) text.append(key).append('=').append(bundle.get(key)).append('\n');
        return text.toString();
    }

    private Bundle prompt() { return provider.call("prompt_settings", null, null); }

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
