package io.github.miaiplus;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class DiagnosticEnvironmentTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Config.prefs(context).edit().clear().commit();
    }

    @Test public void exportClassifiesConfigurationWithoutIncludingPrivateOrUnexpectedValues() throws Exception {
        Config.prefs(context).edit().putString("endpoint", "https://private-host.example")
            .putString("apiKey", "private-secret").putString("model", "private-model")
            .putString("planner", "private-planner").putString("protocol", "private-protocol")
            .putString("hook_report", "probe: private-report").commit();

        JSONObject environment = DiagnosticEnvironment.snapshot(context);
        JSONObject config = environment.getJSONObject("config");
        assertEquals("unknown", config.getString("planner"));
        assertEquals("unknown", config.getString("protocol"));
        assertTrue(config.getBoolean("endpointConfigured"));
        assertTrue(config.getBoolean("modelConfigured"));
        assertTrue(config.getBoolean("apiKeyConfigured"));
        assertEquals("other", environment.getJSONObject("hook").getString("reportKind"));
        assertFalse(environment.getJSONObject("hook").has("report"));
        assertFalse(environment.toString().contains("private-"));
    }

    @Test public void brokenConfigurationAndHookStateDoNotPreventOtherDiagnosticSections() throws Exception {
        Config.prefs(context).edit().putString("maxSteps", "private-invalid-type")
            .putString("hook_status", "private-invalid-json").commit();

        JSONObject environment = DiagnosticEnvironment.snapshot(context);
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
        Shadows.shadowOf(context.getPackageManager()).installPackage(host);
        boolean selectedBeforeExport = HostCompatibility.modern();

        JSONObject environment = DiagnosticEnvironment.snapshot(context);
        assertEquals("modern", environment.getJSONObject("host").getString("profile"));
        assertEquals(selectedBeforeExport, HostCompatibility.modern());
    }

    @Test public void oldHookPreferencesAreFilteredAgainBeforeExport() throws Exception {
        Config.prefs(context).edit().putString("hook_status", new JSONObject().put("phase", "ready")
                .put("stage", "https://private-host.example").put("credential", "private-secret").toString())
            .putString("hook_reload", new JSONObject().put("phase", "reload_refused")
                .put("binding", "/private/user/input.txt").put("reason", "private-user-input").toString()).commit();

        JSONObject hook = DiagnosticEnvironment.snapshot(context).getJSONObject("hook");
        assertEquals("ready", hook.getJSONObject("status").getString("phase"));
        assertEquals("reload_refused", hook.getJSONObject("lastReload").getString("phase"));
        assertFalse(hook.toString().contains("private"));
        assertFalse(hook.getJSONObject("status").has("credential"));
    }

    @Test public void processExitReportsReasonButNeverFreeFormDescription() throws Exception {
        ApplicationExitInfo exit = ReflectionHelpers.newInstance(ApplicationExitInfo.class);
        ReflectionHelpers.setField(exit, "mReason", ApplicationExitInfo.REASON_CRASH);
        ReflectionHelpers.setField(exit, "mDescription", "private-user-data in exception /private/path");
        ReflectionHelpers.setField(exit, "mTimestamp", System.currentTimeMillis() - 1000L);
        Shadows.shadowOf(context.getSystemService(ActivityManager.class)).addApplicationExitInfo(exit);

        JSONObject environment = DiagnosticEnvironment.snapshot(context);
        JSONObject recorded = environment.getJSONObject("process").getJSONArray("recentExits").getJSONObject(0);
        assertEquals(ApplicationExitInfo.REASON_CRASH, recorded.getInt("reason"));
        assertFalse(recorded.has("description"));
        assertFalse(environment.toString().contains("private-user-data"));
        assertFalse(environment.toString().contains("/private/path"));
    }
}
