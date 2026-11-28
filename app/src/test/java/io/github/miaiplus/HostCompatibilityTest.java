package io.github.miaiplus;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import java.time.Duration;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public class HostCompatibilityTest {
    @After public void restoreLegacyProfile() {
        HostCompatibility.select("8.2.65.4714", 508002065L);
        HostTrace.clear();
    }

    @Test public void onlyExactVersionNameAndCodeSwitchProfilesAndLegacyBindingsRestore() {
        assertTrue(HostCompatibility.select("8.3.30.4420", 508003030L));
        assertEquals("legacy", HostCompatibility.profile("8.2.65.4714", 508002065L));
        assertEquals("legacy", HostCompatibility.profile("8.2.66.8912", 508002066L));
        assertEquals("unsupported", HostCompatibility.profile("8.2.66.8912", 508002065L));
        assertEquals("unsupported", HostCompatibility.profile("8.3.30.4420", 508003031L));
        assertEquals("unsupported", HostCompatibility.profile(null, 0));
        assertTrue("diagnostic lookup does not change active bindings", HostCompatibility.modern());
        assertEquals("dn.i", HostCompatibility.name("qm.i"));
        assertEquals("B", HostCompatibility.choose("A", "B"));
        assertEquals("com.xiaomi.voiceassistant.mainui.navigator.MainNavigatorActivity", HostCompatibility.chatActivity());
        assertEquals("com.aios.osbot.agent.model.Message",
            HostCompatibility.name("com.aios.osbot.agent.model.Message"));
        assertTrue(HostCompatibility.select("8.2.65.4714", 508002065L));
        assertEquals("qm.i", HostCompatibility.name("qm.i"));
        assertEquals("A", HostCompatibility.choose("A", "B"));
        assertEquals("com.xiaomi.voiceassistant.TabHostMainActivity", HostCompatibility.chatActivity());
        assertTrue(HostCompatibility.select("8.3.30.4420", 508003030L));
        assertFalse(HostCompatibility.select("8.3.30.4421", 508003030L));
        assertFalse(HostCompatibility.modern());
        assertFalse(HostCompatibility.select("8.3.30.4420", 508003031L));
        assertFalse(HostCompatibility.select("8.2.65.4714", 508003030L));
        assertTrue(HostCompatibility.select("8.2.66.8912", 508002066L));
        assertFalse(HostCompatibility.modern());
        assertEquals("qm.i", HostCompatibility.name("qm.i"));
        assertFalse(HostCompatibility.select(null, 508003030L));
    }

    @Test public void traceSnapshotKeepsItsOriginalRouteWhenAnotherRequestArrives() {
        String first = HostTrace.route(true);
        HostTrace.Snapshot captured = HostTrace.snapshot();
        ShadowSystemClock.advanceBy(Duration.ofMillis(30));
        String next = HostTrace.route(false);
        assertEquals(first, captured.reference());
        assertTrue(captured.forcedAgent());
        assertNotEquals(first, next);
        assertEquals(next, HostTrace.snapshot().reference());
        assertFalse(HostTrace.snapshot().forcedAgent());
    }

    @Test public void traceRoutesExpireRepeatedStatesAreBoundedAndReloadClearsContext() {
        HostTrace.route(true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(120_001));
        assertEquals("", HostTrace.snapshot().reference());
        assertEquals(-1, HostTrace.snapshot().ageMs());
        assertFalse(HostTrace.snapshot().forcedAgent());
        HostTrace.stage("model");
        assertTrue(HostTrace.changed("state", "ready", 60_000));
        assertFalse(HostTrace.changed("state", "ready", 60_000));
        assertTrue(HostTrace.changed("state", "busy", 60_000));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
        assertTrue(HostTrace.changed("state", "busy", 60_000));
        HostTrace.clear();
        assertEquals("", HostTrace.stage());
        assertEquals("", HostTrace.lastRoute());
        assertTrue(HostTrace.changed("state", "busy", 60_000));
    }

    @Test public void providerAuthoritiesFollowApplicationIdAndOnlyTheBridgeIsExported() {
        PackageManager packages = RuntimeEnvironment.getApplication().getPackageManager();
        assertEquals(BuildConfig.APPLICATION_ID, RuntimeEnvironment.getApplication().getPackageName());
        String bridgeAuthority = BuildConfig.APPLICATION_ID + ".bridge";
        assertEquals(Uri.parse("content://" + bridgeAuthority), BridgeProvider.URI);
        ProviderInfo bridge = packages.resolveContentProvider(bridgeAuthority, 0);
        assertEquals(BuildConfig.APPLICATION_ID, bridge.packageName);
        assertEquals("io.github.miaiplus.BridgeProvider", bridge.name);
        assertTrue(bridge.exported);
        assertEquals(bridge.name, packages.resolveContentProvider("io.github.miaiplus.bridge", 0).name);

        String fileAuthority = BuildConfig.APPLICATION_ID + ".log-files";
        ProviderInfo files = packages.resolveContentProvider(fileAuthority, PackageManager.GET_META_DATA);
        assertEquals(BuildConfig.APPLICATION_ID, files.packageName);
        assertEquals("androidx.core.content.FileProvider", files.name);
        assertFalse(files.exported);
        assertTrue(files.grantUriPermissions);
        assertTrue(files.metaData.getInt("android.support.FILE_PROVIDER_PATHS") != 0);
        assertEquals(files.name, packages.resolveContentProvider("io.github.miaiplus.log-files", 0).name);
    }

    @Test public void manifestComponentsKeepLoadableNamespaceClassNames() throws Exception {
        PackageManager packages = RuntimeEnvironment.getApplication().getPackageManager();
        ActivityInfo activity = packages.getActivityInfo(new ComponentName(BuildConfig.APPLICATION_ID,
            "io.github.miaiplus.MainActivity"), 0);
        assertEquals(BuildConfig.APPLICATION_ID, activity.packageName);
        assertTrue(android.app.Activity.class.isAssignableFrom(loadClass(activity.name)));

        ServiceInfo service = packages.getServiceInfo(new ComponentName(BuildConfig.APPLICATION_ID,
            "io.github.miaiplus.ScreenService"), 0);
        assertEquals(BuildConfig.APPLICATION_ID, service.packageName);
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission);
        assertTrue(android.app.Service.class.isAssignableFrom(loadClass(service.name)));

        PackageInfo info = packages.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_PROVIDERS);
        ProviderInfo bridge = Arrays.stream(info.providers)
            .filter(provider -> "io.github.miaiplus.BridgeProvider".equals(provider.name)).findFirst().orElseThrow();
        assertTrue(android.content.ContentProvider.class.isAssignableFrom(loadClass(bridge.name)));
        assertTrue(Application.class.isAssignableFrom(loadClass("io.github.miaiplus.ModuleApplication")));
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, HostCompatibilityTest.class.getClassLoader());
    }
}
