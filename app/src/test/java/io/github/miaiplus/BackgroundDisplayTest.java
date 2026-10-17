package io.github.miaiplus;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class BackgroundDisplayTest {
    @Test public void closingAStatusHelperKeepsAnotherSessionsFrame() throws Exception {
        File activeDirectory = frameDirectory();
        File activeFrame = new File(activeDirectory, "frame.jpg");
        Files.write(activeFrame.toPath(), new byte[] { 1, 2, 3 });
        File statusDirectory = frameDirectory();
        BackgroundDisplay status = display(new JSONObject(), statusDirectory);
        try {
            assertNotEquals(activeDirectory, statusDirectory);
            status.close();
            assertFalse(statusDirectory.exists());
            assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(activeFrame.toPath()));
        } finally {
            status.close();
            activeFrame.delete();
            activeDirectory.delete();
        }
    }

    @Test public void rejectedOrUnverifiedInputStopsInsteadOfReportingSuccess() throws Exception {
        try (BackgroundDisplay rejected = display(new JSONObject()
                .put("down", "false").put("up", "true").put("accepted", "false")
                .put("delivered", "false").put("output", ""), frameDirectory())) {
            assertInputFailure(() -> rejected.tap(null, 100, 200));
            assertInputFailure(() -> rejected.longPress(null, 100, 200));
            assertInputFailure(() -> rejected.swipe(null, new double[] { 100, 200 }, new double[] { 100, 600 }));
            assertInputFailure(() -> rejected.key(null, 4));
        }
        try (BackgroundDisplay unverified = display(new JSONObject(), frameDirectory())) {
            assertInputFailure(() -> unverified.key(null, 4));
            assertInputFailure(() -> unverified.tap(null, 100, 200));
        }
    }

    @Test public void acceptedHelperInputCompletesNormally() throws Exception {
        try (BackgroundDisplay display = display(new JSONObject().put("down", "true").put("up", "true")
                .put("accepted", "true").put("delivered", "true"), frameDirectory())) {
            display.tap(null, 100, 200);
            display.longPress(null, 100, 200);
            display.swipe(null, new double[] { 100, 200 }, new double[] { 100, 600 });
            display.key(null, 4);
        }
    }

    private static File frameDirectory() throws Exception {
        Method method = BackgroundDisplay.class.getDeclaredMethod("createFrameDirectory", Context.class);
        method.setAccessible(true);
        return (File) method.invoke(null, RuntimeEnvironment.getApplication());
    }

    private static BackgroundDisplay display(JSONObject response, File directory) throws Exception {
        Constructor<BackgroundDisplay> constructor = BackgroundDisplay.class.getDeclaredConstructor(Process.class, File.class);
        constructor.setAccessible(true);
        BackgroundDisplay display = constructor.newInstance(new ReplyProcess(response), directory);
        Field session = BackgroundDisplay.class.getDeclaredField("session");
        session.setAccessible(true);
        session.set(display, new DisplaySession(7, "7", 1080, 1920, 320, 0));
        return display;
    }

    private interface InputAction { void run() throws Exception; }

    private static void assertInputFailure(InputAction action) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
        assertTrue(failure.getMessage(), failure.getMessage().startsWith(BackgroundDisplay.ERROR_INPUT_FAILED));
    }

    /** Protocol fixture: replies only after receiving a complete command, retaining the caller's token. */
    private static final class ReplyProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream replies;
        private final JSONObject response;
        private boolean alive = true;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
            @Override public void flush() throws IOException {
                if (size() == 0) return;
                String command = toString(StandardCharsets.UTF_8);
                reset();
                try {
                    JSONObject reply = new JSONObject(response.toString());
                    reply.put("token", Long.parseLong(command.substring(0, command.indexOf(' '))));
                    reply.put("status", "ok");
                    replies.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                    replies.flush();
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
            }
        };

        ReplyProcess(JSONObject response) throws IOException {
            this.response = response;
            replies = new PipedOutputStream(stdout);
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean isAlive() { return alive; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
    }
}
