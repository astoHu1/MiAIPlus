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

    @Test public void launchDistinguishesAnOpenedTargetFromAPendingSystemPrompt() throws Exception {
        try (BackgroundDisplay opened = display(new JSONObject().put("status", "launched"), frameDirectory())) {
            assertTrue(opened.launch(null, "example.target/.Main"));
        }
        try (BackgroundDisplay pending = display(new JSONObject().put("status", "awaiting_confirmation"), frameDirectory())) {
            assertFalse(pending.launch(null, "example.target/.Main"));
        }
        try (BackgroundDisplay unknown = display(new JSONObject(), frameDirectory())) {
            assertThrows(IllegalStateException.class, () -> unknown.launch(null, "example.target/.Main"));
        }
        try (BackgroundDisplay stolen = display(new JSONObject().put("status", "error").put("code", "TASK_STOLEN")
                .put("message", "target moved to physical display"), frameDirectory())) {
            assertThrows(IllegalStateException.class, () -> stolen.launch(null, "example.target/.Main"));
        }
    }

    @Test public void aGenerationOnlyMatchesItsOwnDisplay() {
        DisplaySession session = new DisplaySession(7, "sf-7", 1080, 1920, 320, 0);
        assertTrue(session.matches(7, session.generation));
        // The system can reuse a display id for an unrelated display, so the id alone must never be trusted.
        assertFalse(session.matches(8, session.generation));
        assertFalse(session.matches(7, new DisplaySession(7, "sf-7", 1080, 1920, 320, 0).generation));
        assertFalse(session.matches(7, ""));
    }

    @Test public void backgroundFramesKeepLogicalDisplaySizeSeparateFromEncodedImageSize() {
        ScreenService.Frame physical = ScreenService.Frame.physical("img", "tree", 1440, 3200, "com.example");
        assertFalse(physical.isBackground());
        assertEquals(1440, physical.imageWidth());
        assertEquals(android.view.Display.DEFAULT_DISPLAY, physical.displayId());
        ScreenService.Frame background = ScreenService.Frame.background("img", "tree", 1080, 1920, 576, 1024,
            "com.example", 7, "generation-7");
        assertTrue(background.isBackground());
        assertEquals(7, background.displayId());
        assertEquals("generation-7", background.generation());
        // Mixing the logical display size with the encoded JPEG size misplaces taps.
        assertEquals(1080, background.width());
        assertEquals(1920, background.height());
        assertEquals(576, background.imageWidth());
        assertEquals(1024, background.imageHeight());
    }

    @Test public void grantRejectsWrongOwnerReplayAndExpiryWithoutConsumingTheRequest() {
        ForegroundConsent.Request request = consentRequest();
        assertFalse(request.respond("task-a", 200, request.token, true, 101));
        assertFalse(request.respond("task-b", 100, request.token, true, 101));
        assertFalse(request.respond("task-a", 100, "old-token", true, 101));
        assertNull(request.answer());
        assertTrue(request.respond("task-a", 100, request.token, true, 101));
        assertEquals(Boolean.TRUE, request.answer());

        ForegroundConsent.Request denied = consentRequest();
        assertTrue(denied.respond("task-a", 100, denied.token, false, 101));
        assertFalse(denied.respond("task-a", 100, denied.token, true, 102));
        assertEquals(Boolean.FALSE, denied.answer());

        ForegroundConsent.Request expired = consentRequest();
        assertFalse(expired.respond("task-a", 100, expired.token, true, expired.expires));
        assertNull(expired.answer());
        assertNotEquals(expired.token, consentRequest().token);
    }

    @Test public void declinedHandoffEndsTheNativeTurnOnlyForOurOwnPhoneTool() throws Exception {
        String paused = new ForegroundConsent.Paused("PAUSE：你已拒绝，任务已暂停").getMessage();
        assertTrue(ForegroundConsent.endsNativeTurn(paused));
        // The compatibility CLI serializes the AppResult inside its own JSON result envelope.
        assertTrue(ForegroundConsent.endsNativeTurn("{\"__envelope__\":1,\"ok\":true,\"data\":\"" + paused + "\"}"));
        assertTrue(ForegroundConsent.endsNativeTurn("{\"type\":\"text\",\"text\":\"" + paused + "\"}"));
        assertFalse(ForegroundConsent.endsNativeTurn("CONTINUE：控屏尚未完成。"));
        assertFalse(ForegroundConsent.endsNativeTurn("CONTINUE：控屏尚未完成。目标=" + paused));
        assertFalse(ForegroundConsent.endsNativeTurn("{\"data\":\"" + paused + "\"}"));

        assertTrue(ForegroundConsent.endsNativeToolTurn(NativePhoneUseTool.NAME, "{}", paused));
        assertTrue(ForegroundConsent.endsNativeToolTurn("miaiplus_background_phone_use", "{}", paused));
        assertTrue(ForegroundConsent.endsNativeToolTurn("voiceassist__controlApp", "{}", paused));
        String cli = new JSONObject().put("type", "text").put("text", paused).toString();
        assertTrue(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "  voiceassist__controlApp \"MIAIPLUS:...\"").toString(), cli));
        assertFalse(ForegroundConsent.endsNativeToolTurn("search", "{}", paused));
        assertFalse(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "echo voiceassist__controlApp").toString(), cli));
        assertFalse(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "voiceassist__controlApp_other").toString(), cli));
    }

    @Test public void initialLaunchRemainsInitialAfterWaitingButNotAfterAnAppAction() throws Exception {
        BackgroundActions.LaunchPolicy policy = new BackgroundActions.LaunchPolicy();
        policy.used(new Action(new JSONObject().put("action", "wait")));
        policy.used(new Action(new JSONObject().put("action", "wait")));
        assertTrue(policy.initial());
        policy.used(new Action(new JSONObject().put("action", "launch").put("app", "example.app")));
        assertFalse(policy.initial());
    }

    private static ForegroundConsent.Request consentRequest() {
        return new ForegroundConsent.Request("task-a", 100, "example.app", "Example", 100);
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
                    if (!reply.has("status")) reply.put("status", "ok");
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
