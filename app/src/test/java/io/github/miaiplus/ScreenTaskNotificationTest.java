package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
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

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class ScreenTaskNotificationTest {
    private BridgeProvider provider;

    @Before public void setUp() throws Exception {
        reset();
        provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    }

    @After public void tearDown() throws Exception { reset(); }

    @Test public void completedToolStepKeepsTaskNotificationActiveDuringModelPlanning() throws Exception {
        Object session = nativeSession();
        field(NativeSession.class, "active").set(null, session);
        task("task-a", "CONTINUE：控屏尚未完成。");
        // The individual tool's worker has exited; only the native session remains while the model plans.
        assertNull(field(BridgeProvider.class, "active").get(null));
        for (int poll = 0; poll < 3; poll++) {
            Bundle state = provider.call("screen_state", "task-a", null);
            assertTrue(state.getBoolean("active"));
            assertFalse(state.getBoolean("completed"));
            assertFalse(InteractionUi.completedTask(state, "task-a"));
        }
        assertSame(session, field(NativeSession.class, "active").get(null));
    }

    @Test public void completionWaitsForNativeCleanupAndAcknowledgesOnlyCurrentTask() throws Exception {
        task("task-a", "模型报告任务完成：已检查结果");
        field(NativeSession.class, "closing").setBoolean(null, true);
        Bundle closing = provider.call("screen_state", "task-a", null);
        assertTrue(closing.getBoolean("active"));
        assertFalse(InteractionUi.completedTask(closing, "task-a"));
        field(NativeSession.class, "closing").setBoolean(null, false);
        Bundle ended = provider.call("screen_state", "task-a", null);
        assertFalse(ended.getBoolean("active"));
        assertTrue(InteractionUi.completedTask(ended, "task-a"));
        assertFalse(InteractionUi.completedTask(ended, "task-b"));
        assertFalse(InteractionUi.completedTask(provider.call("screen_state", "task-b", null), "task-b"));
    }

    @Test public void inactiveOrMissingStateAloneDoesNotClaimCompletion() {
        Bundle state = new Bundle();
        state.putString("screen_task", "task-a");
        assertFalse(InteractionUi.completedTask(state, "task-a"));
        assertFalse(InteractionUi.completedTask(null, "task-a"));
        state.putBoolean("completed", true);
        state.putBoolean("active", true);
        assertFalse(InteractionUi.completedTask(state, "task-a"));
    }

    @Test public void onlyConfirmedFinishClaimsSuccessAndErrorsShowStopped() {
        assertEquals("手机操作已结束", InteractionUi.completionTitle("模型报告任务完成：已检查结果"));
        assertTrue(InteractionUi.completionText("模型报告任务完成：已检查结果").startsWith("已完成"));
        for (String failure : new String[]{"页面已改变，请重新观察", "任务已停止或超时", "控屏已停止", "CONTINUE：控屏尚未完成。"}) {
            assertEquals("手机操作已停止", InteractionUi.completionTitle(failure));
            assertTrue(InteractionUi.completionText(failure).startsWith("任务未完成"));
        }
        assertEquals("需要你接管手机", InteractionUi.completionTitle("PAUSE：需要确认"));
    }

    private static void task(String id, String result) throws Exception {
        field(BridgeProvider.class, "screenTaskId").set(null, id);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, true);
        field(BridgeProvider.class, "screenResult").set(null, result);
    }
    private static Object nativeSession() throws Exception {
        Class<?> type = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test", android.os.Process.myUid(), 10, null, false);
    }
    private static void reset() throws Exception {
        field(BridgeProvider.class, "active").set(null, null);
        field(BridgeProvider.class, "screenTaskId").set(null, null);
        field(BridgeProvider.class, "screenTaskActive").setBoolean(null, false);
        field(BridgeProvider.class, "screenResult").set(null, "");
        field(NativeSession.class, "active").set(null, null);
        field(NativeSession.class, "executing").setBoolean(null, false);
        field(NativeSession.class, "closing").setBoolean(null, false);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
