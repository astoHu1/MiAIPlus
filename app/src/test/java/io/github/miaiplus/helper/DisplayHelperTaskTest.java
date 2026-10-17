package io.github.miaiplus.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DisplayHelperTaskTest {
    public static final class TaskFixture extends ActivityManager.RunningTaskInfo {
        // An intentionally unrelated affinity must never become ownership evidence.
        public String affinity;
    }

    public static final class TaskServiceFixture {
        private final List<TaskFixture> tasks;
        TaskServiceFixture(List<TaskFixture> tasks) { this.tasks = tasks; }
        public List<TaskFixture> getTasks(int count, boolean visibleRecents, boolean extras, int display) {
            assertEquals(-1, display);
            return tasks;
        }
    }

    public static final class FailedTaskService {
        public List<TaskFixture> getTasks(int count, boolean visibleRecents, boolean extras) {
            throw new SecurityException("task query denied");
        }
    }

    public static final class RejectedImePolicy {
        public void setDisplayImePolicy(int display, int policy) { throw new SecurityException("policy denied"); }
        public int getDisplayImePolicy(int display) { return 1; }
    }

    public static final class IgnoredImePolicy {
        public void setDisplayImePolicy(int display, int policy) { }
        public int getDisplayImePolicy(int display) { return 1; }
    }

    private TaskFixture task(int id, int display, boolean visible, String packageName, String affinity) throws Exception {
        TaskFixture task = new TaskFixture();
        set(task, "taskId", id);
        set(task, "displayId", display);
        set(task, "isVisible", visible);
        task.baseActivity = new ComponentName(packageName, packageName + ".Main");
        task.topActivity = task.baseActivity;
        task.baseIntent = new Intent().setComponent(task.baseActivity);
        task.affinity = affinity;
        return task;
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    @Test public void customAndEmptyAffinitiesStillProtectActualMainScreenComponent() throws Exception {
        for (String affinity : new String[] { "unrelated.task.group", "" }) {
            var tasks = DisplayHelper.queryTasks(new TaskServiceFixture(List.of(task(7, 0, true, "example.target", affinity))));
            assertEquals(0, DisplayHelper.visibleTaskDisplay(tasks, "example.target", 9));
            assertEquals(-1, DisplayHelper.visibleTaskDisplay(tasks, "unrelated.task.group", 9));
            assertEquals(7, tasks.get(0).id());
        }
    }

    @Test public void otherVirtualScreenIsProtectedWhileOwnScreenCanBeReused() throws Exception {
        var tasks = DisplayHelper.queryTasks(new TaskServiceFixture(List.of(
            task(7, 9, true, "example.target", ""), task(8, 12, true, "example.target", ""))));
        assertEquals(12, DisplayHelper.visibleTaskDisplay(tasks, "example.target", 9));
    }

    @Test public void queryFailureAndIncompleteSnapshotsCannotBecomeNoConflict() {
        assertThrows(Exception.class, () -> DisplayHelper.queryTasks(new FailedTaskService()));
        assertThrows(Exception.class, () -> DisplayHelper.queryTasks(new TaskServiceFixture(List.of())));
    }

    @Test public void unknownVisibleTaskOwnershipFailsClosed() throws Exception {
        TaskFixture unknown = task(5, 0, true, "example.target", "");
        unknown.baseActivity = null;
        unknown.topActivity = null;
        unknown.baseIntent = new Intent();
        assertThrows(Exception.class, () -> DisplayHelper.queryTasks(new TaskServiceFixture(List.of(unknown))));
    }

    private JSONObject imeReply(Object policy) throws Exception {
        Field display = DisplayHelper.class.getDeclaredField("displayId");
        display.setAccessible(true);
        int previous = display.getInt(null);
        PrintStream output = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            display.setInt(null, 99);
            System.setOut(new PrintStream(captured));
            DisplayHelper.setImePolicy(42L, policy);
        } finally {
            display.setInt(null, previous);
            System.setOut(output);
        }
        return new JSONObject(captured.toString().strip());
    }

    @Test public void unsupportedImePolicyIsAnErrorInsteadOfReady() throws Exception {
        JSONObject reply = imeReply(new RejectedImePolicy());
        assertEquals(42L, reply.getLong("token"));
        assertEquals("error", reply.getString("status"));
        assertEquals("IME_ISOLATION_FAILED", reply.getString("code"));
        assertTrue(!reply.optBoolean("verified", false));
    }

    @Test public void ignoredImePolicyFailsReadbackInsteadOfReportingReady() throws Exception {
        JSONObject reply = imeReply(new IgnoredImePolicy());
        assertEquals("error", reply.getString("status"));
        assertEquals("IME_ISOLATION_FAILED", reply.getString("code"));
        assertTrue(!reply.optBoolean("verified", false));
    }
}
