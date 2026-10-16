package io.github.miaiplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Spinner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Deterministic settings UI checks; restores private preferences and never calls a model. */
final class EndpointProfilesRunner {
    private EndpointProfilesRunner() { }

    static void run(Instrumentation test) {
        var context = test.getTargetContext();
        var prefs = Config.prefs(context);
        Map<String, ?> original = prefs.getAll();
        MainActivity activity = null;
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
            check(state != null && !state.getBoolean("active"), "Stop active work before settings tests");
            prefs.edit().clear().putString("endpoint", "https://a.example.com/v1").putString("apiKey", "fixture-a-key")
                .putString("model", "fixture-a").putString("api", "responses").putString("protocol", "computer").commit();
            activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity settings = activity;
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                check("默认配置".equals(text(settings, "profileName")), "Legacy name missing");
                check("fixture-a-key".equals(text(settings, "key")), "Legacy key lost");
                check("phone_use".equals(new Config(context).protocol), "Legacy protocol migration failed");
                setText(settings, "profileName", "服务 A");
                check((Boolean) invoke(settings, "saveProfile"), "Save failed");
                var second = new EndpointProfiles.Profile("fixture-b", "服务 B", "https://b.example.com/v1", "fixture-b-key", "fixture-b", "chat");
                var editor = prefs.edit();
                new EndpointProfiles(prefs).write(editor, second, false); editor.commit();
                invoke(settings, "loadProfile");
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                check("fixture-b-key".equals(text(settings, "key")), "Second key not loaded");
                check(((Spinner) field(settings, "api")).getSelectedItemPosition() == 2, "Second API not loaded");
                ((Spinner) field(settings, "profiles")).setSelection(0);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                check("服务 A".equals(text(settings, "profileName")), "Spinner did not switch");
                check("fixture-a-key".equals(new Config(context).apiKey), "Runtime key did not switch");
                check("responses".equals(new Config(context).api), "Runtime API did not switch");
                setText(settings, "model", "edited-model");
                check((Boolean) invoke(settings, "saveProfile"), "Edit failed");
                invoke(settings, "switchProfile", new Class<?>[]{String.class}, "fixture-b");
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                ((Spinner) field(settings, "profiles")).setSelection(0);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                check("edited-model".equals(text(settings, "model")), "Saved edit lost on return");
                check(((Spinner) field(settings, "api")).getSelectedItemPosition() == 1, "Responses not restored");
            });
            result.putString("stream", "PASS: settings legacy migration, phone use migration, saved edits, spinner switching and runtime endpoint/key/model/API snapshots; no model requests.\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            if (activity != null) { MainActivity settings = activity; test.runOnMainSync(settings::finish); test.waitForIdleSync(); }
            var editor = prefs.edit().clear();
            for (var entry : original.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String s) editor.putString(key, s);
                else if (value instanceof Boolean b) editor.putBoolean(key, b);
                else if (value instanceof Integer i) editor.putInt(key, i);
                else if (value instanceof Long l) editor.putLong(key, l);
                else if (value instanceof Float f) editor.putFloat(key, f);
                else if (value instanceof Set<?>) {
                    @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                    editor.putStringSet(key, strings);
                }
            }
            check(editor.commit(), "Could not restore settings");
        }
        test.finish(code, result);
    }

    private static Object field(Object target, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static String text(Object target, String name) { return ((EditText) field(target, name)).getText().toString(); }
    private static void setText(Object target, String name, String text) { ((EditText) field(target, name)).setText(text); }
    private static Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
