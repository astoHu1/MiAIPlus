package io.github.miaiplus;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public class BridgeProviderProbeTest {
    private BridgeProvider provider;

    @Before public void setUp() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            // Fail before ProcessBuilder: unit tests must never start a root helper.
            @Override public String getPackageCodePath() { return ""; }
        };
        Config.prefs(context).edit().clear().putBoolean("enabled", true).commit();
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(context, info);
        resetState();
    }

    @After public void tearDown() throws Exception { resetState(); }

    @Test public void runningProbeRejectsScreenAndChatBeforeSchedulingWork() throws Exception {
        field(BridgeProvider.class, "backgroundProbeRunning").setBoolean(null, true);
        for (String mode : new String[]{"screen", "chat"}) {
            Bundle request = new Bundle();
            request.putString("mode", mode);
            request.putString("query", "test");
            Bundle result = provider.call("start", null, request);
            assertTrue(result.getString("error", "").contains("检测进行中"));
            assertFalse(result.containsKey("id"));
            assertNull(field(BridgeProvider.class, "active").get(null));
        }
    }

    @Test public void nativeSessionBetweenStepsRejectsProbeWithoutReservingIt() throws Exception {
        Class<?> stateClass = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = stateClass.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance("test", android.os.Process.myUid(), 10, null, true);
        field(NativeSession.class, "active").set(null, state);
        assertTrue(NativeSession.hasActiveSession());
        assertFalse(field(NativeSession.class, "executing").getBoolean(null));

        Bundle result = provider.call("background_display_probe", null, null);

        assertTrue(result.getString("error", "").contains("已有任务"));
        assertFalse(field(BridgeProvider.class, "backgroundProbeRunning").getBoolean(null));
        assertSame(state, field(NativeSession.class, "active").get(null));
    }

    @Test public void preparingTaskAndChatRunBothRejectProbe() throws Exception {
        for (boolean screen : new boolean[]{true, false}) {
            RunState run = new RunState("test", android.os.Process.myUid());
            run.screen = screen;
            run.preparingScreen = screen;
            field(BridgeProvider.class, "active").set(null, run);

            Bundle result = provider.call("background_display_probe", null, null);

            assertTrue(result.getString("error", "").contains("已有任务"));
            assertFalse(field(BridgeProvider.class, "backgroundProbeRunning").getBoolean(null));
            assertFalse(run.cancelled.get());
        }
    }

    @Test public void nativeExecutionAndCleanupRejectProbeAndNewScreenOrChatWork() throws Exception {
        for (String phase : new String[]{"closing", "executing"}) {
            field(NativeSession.class, phase).setBoolean(null, true);
            assertFalse(phase, NativeSession.hasActiveSession());
            Bundle probe = provider.call("background_display_probe", null, null);
            assertTrue(phase, probe.getString("error", "").contains("已有任务"));
            assertFalse(phase, field(BridgeProvider.class, "backgroundProbeRunning").getBoolean(null));
            for (String mode : new String[]{"screen", "chat"}) {
                Bundle request = new Bundle();
                request.putString("mode", mode); request.putString("query", "test");
                Bundle result = provider.call("start", null, request);
                String label = phase + "/" + mode;
                assertTrue(label, result.getString("error", "").contains("仍在清理"));
                assertFalse(label, result.containsKey("id"));
                assertNull(label, field(BridgeProvider.class, "active").get(null));
            }
            field(NativeSession.class, phase).setBoolean(null, false);
        }
    }

    @Test public void failedProbeReleasesReservationSoAnotherProbeCanRun() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Bundle result = provider.call("background_display_probe", null, null);
            assertTrue(result.getString("error", "").contains("无法定位模块代码路径"));
            assertFalse(result.getBoolean("root"));
            assertFalse(field(BridgeProvider.class, "backgroundProbeRunning").getBoolean(null));
        }
    }

    @Test public void statusDoesNotAcquireOrReleaseProbeReservation() throws Exception {
        field(BridgeProvider.class, "backgroundProbeRunning").setBoolean(null, true);

        Bundle result = provider.call("background_display_status", null, null);

        assertTrue(result.getString("error", "").contains("无法定位模块代码路径"));
        assertTrue(field(BridgeProvider.class, "backgroundProbeRunning").getBoolean(null));
    }

    private static void resetState() throws Exception {
        field(BridgeProvider.class, "backgroundProbeRunning").setBoolean(null, false);
        field(BridgeProvider.class, "active").set(null, null);
        field(NativeSession.class, "active").set(null, null);
        field(NativeSession.class, "executing").setBoolean(null, false);
        field(NativeSession.class, "closing").setBoolean(null, false);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
