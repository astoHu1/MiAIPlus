package io.github.miaiplus;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

/** Application identity may change without moving Java/Kotlin component classes. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class PackageIdentityTest {
    private Context context;
    private PackageManager packages;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packages = context.getPackageManager();
    }

    @Test public void mainlineKeepsOriginalApplicationPackage() {
        assertEquals("io.github.miaiplus", BuildConfig.APPLICATION_ID);
        assertEquals(BuildConfig.APPLICATION_ID, context.getPackageName());
    }

    @Test public void bridgeManifestAuthorityAndRuntimeUriFollowApplicationId() throws Exception {
        String authority = BuildConfig.APPLICATION_ID + ".bridge";
        assertEquals(Uri.parse("content://" + authority), BridgeProvider.URI);
        ProviderInfo info = packages.resolveContentProvider(authority, 0);
        assertNotNull(info);
        assertEquals(authority, info.authority);
        assertEquals(BuildConfig.APPLICATION_ID, info.packageName);
        assertEquals("io.github.miaiplus.BridgeProvider", info.name);
        assertTrue(info.exported);
        assertEquals(info.name, packages.resolveContentProvider("io.github.miaiplus.bridge", 0).name);
    }

    @Test public void fileProviderAuthorityFollowsApplicationIdAndRemainsPrivate() throws Exception {
        String authority = BuildConfig.APPLICATION_ID + ".log-files";
        ProviderInfo info = packages.resolveContentProvider(authority, PackageManager.GET_META_DATA);
        assertNotNull(info);
        assertEquals(authority, info.authority);
        assertEquals(BuildConfig.APPLICATION_ID, info.packageName);
        assertEquals("androidx.core.content.FileProvider", info.name);
        assertFalse(info.exported);
        assertTrue(info.grantUriPermissions);
        assertNotNull(info.metaData);
        assertTrue(info.metaData.getInt("android.support.FILE_PROVIDER_PATHS") != 0);
        assertEquals(info.name, packages.resolveContentProvider("io.github.miaiplus.log-files", 0).name);
    }

    @Test public void manifestComponentsKeepLoadableNamespaceClassNames() throws Exception {
        ActivityInfo activity = packages.getActivityInfo(new ComponentName(BuildConfig.APPLICATION_ID,
            "io.github.miaiplus.MainActivity"), 0);
        assertEquals("io.github.miaiplus.MainActivity", activity.name);
        assertEquals(BuildConfig.APPLICATION_ID, activity.packageName);
        assertTrue(android.app.Activity.class.isAssignableFrom(loadClass(activity.name)));

        ServiceInfo service = packages.getServiceInfo(new ComponentName(BuildConfig.APPLICATION_ID,
            "io.github.miaiplus.ScreenService"), 0);
        assertEquals("io.github.miaiplus.ScreenService", service.name);
        assertEquals(BuildConfig.APPLICATION_ID, service.packageName);
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission);
        assertTrue(android.app.Service.class.isAssignableFrom(loadClass(service.name)));

        PackageInfo info = packages.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_PROVIDERS);
        assertNotNull(info.providers);
        ProviderInfo bridge = Arrays.stream(info.providers)
            .filter(provider -> "io.github.miaiplus.BridgeProvider".equals(provider.name)).findFirst().orElseThrow();
        assertTrue(android.content.ContentProvider.class.isAssignableFrom(loadClass(bridge.name)));
        assertTrue(Application.class.isAssignableFrom(loadClass("io.github.miaiplus.ModuleApplication")));
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, PackageIdentityTest.class.getClassLoader());
    }
}
