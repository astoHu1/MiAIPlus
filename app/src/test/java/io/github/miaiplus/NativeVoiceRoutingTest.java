package io.github.miaiplus;

import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeVoiceRoutingTest {
    @After public void cleanup() { NativeVoiceRoutingHook.dispose(); }

    @Test public void onlyTheCurrentMainVoiceRequestCanBeHandedOff() {
        Manager manager = new Manager();
        assertTrue(owns(manager, "current"));
        manager.a.get("main").engine.a.requests.put("old", State.VAD_END);
        assertFalse(owns(manager, "old"));
        assertFalse(owns(manager, "unknown"));
        assertFalse(owns(manager, null)); // MiClaw's own ASR Final has no id.
        assertFalse(owns(manager, ""));
        manager.a.get("main").g.mode = Mode.TEXT;
        assertFalse(owns(manager, "current"));
    }

    @Test public void cancelledFinishedAndForeignEngineRequestsCannotRestartTools() {
        Manager manager = new Manager();
        Task main = manager.a.get("main");
        main.engine.a.requests.put("current", State.FINISH);
        assertFalse(owns(manager, "current"));
        main.engine.a.requests.clear();
        assertTrue("real ASR does not have to populate the legacy request-state map", owns(manager, "current"));
        main.engine.a.speech.requesting = false;
        assertFalse("a cancelled engine cannot hand off a retained request event", owns(manager, "current"));
        main.engine.a.speech.requesting = true;
        main.engine.a.speech.main = false;
        assertFalse("a foreign engine cannot borrow the registered main task event", owns(manager, "current"));
        main.engine.a.speech.main = true;
        manager.a.put("miclaw_v2", new Task());
        assertTrue(owns(manager, "current"));
        main.engine.a.requests.put("current", State.VAD_END);
        main.g = null;
        assertFalse(owns(manager, "current"));
    }

    @Test public void duplexSpeechUsesItsOwnCurrentRecognitionEvent() {
        Manager manager = new Manager();
        Request request = manager.a.get("main").g;
        request.event = null;
        request.duplex = new Event("current");
        assertTrue(owns(manager, "current"));
        request.duplex = new Event("other");
        assertFalse(owns(manager, "current"));
    }

    @Test public void duplicateFinalsAreScopedToTheirManagerAndClearedOnReload() {
        Manager manager = new Manager();
        assertTrue(NativeVoiceRoutingHook.claim(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.wasClaimed(manager, "current"));
        assertFalse(NativeVoiceRoutingHook.claim(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.claim(new Manager(), "current"));
        NativeVoiceRoutingHook.dispose();
        assertFalse(NativeVoiceRoutingHook.wasClaimed(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.claim(manager, "current"));
    }

    @Test public void queuedNewRecordingWinsOverCancellationOfThePreviousFinal() {
        Manager manager = new Manager();
        Task main = manager.a.get("main");
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        queue.add(() -> {
            main.g = new Request();
            main.g.event = new Event("new");
            main.engine.a.requests.put("new", State.VAD_END);
        });
        queue.add(() -> assertFalse(cancel(manager, main, "current")));
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals("new", main.g.event.getId());
        assertEquals(0, main.stops);
        assertEquals(0, main.intercepts);
    }

    @Test public void ownedCancellationDoesNotDiscardTheFollowingRecording() {
        Manager manager = new Manager();
        Task main = manager.a.get("main");
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        queue.add(() -> assertTrue(cancel(manager, main, "current")));
        queue.add(() -> {
            main.g = new Request();
            main.g.event = new Event("following");
        });
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals("following", main.g.event.getId());
        assertEquals(1, main.stops);
        assertEquals(1, main.intercepts);
    }

    @Test public void replacedMainTaskCannotBeCancelledByAnEarlierCallback() {
        Manager manager = new Manager();
        Task old = manager.a.put("main", new Task());
        assertFalse(cancel(manager, old, "current"));
        assertEquals(0, old.stops);
        assertEquals(0, manager.a.get("main").stops);
    }

    @Test public void hostRemovingAPendingCallbackCannotKeepReloadBusyForever() {
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        NativeVoiceRoutingHook.PendingStop pending = new NativeVoiceRoutingHook.PendingStop(releases::incrementAndGet);
        assertTrue(pending.expire());
        assertFalse(pending.start());
        assertFalse(pending.expire());
        pending.finish();
        assertEquals(1, releases.get());
    }

    @Test public void timeoutCannotReleaseAnAlreadyRunningCancellation() {
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        NativeVoiceRoutingHook.PendingStop pending = new NativeVoiceRoutingHook.PendingStop(releases::incrementAndGet);
        assertTrue(pending.start());
        assertFalse(pending.expire());
        assertEquals(0, releases.get());
        pending.finish();
        pending.finish();
        assertFalse(pending.expire());
        assertEquals(1, releases.get());
    }

    private static boolean cancel(Manager manager, Task main, String id) {
        return NativeVoiceRoutingHook.cancelOwnedTask(manager, main, id, "event", "duplex", "mode");
    }

    private static boolean owns(Manager manager, String id) {
        return NativeVoiceRoutingHook.ownsMainVoiceRequest(manager, id, "event", "duplex", "mode");
    }

    enum Mode { VOICE, TEXT }
    enum State { VAD_END, FINISH }
    static final class Manager {
        final Map<String, Task> a = new HashMap<>(Map.of("main", new Task()));
    }
    static final class Task {
        Request g = new Request();
        final Engine engine = new Engine();
        int stops, intercepts;
        public Engine getEngine() { return engine; }
        public void interceptAndCancelDialogOperation() { intercepts++; }
        public void G(boolean stopTts, int reason) { stops++; g = null; }
        public void forceStop(boolean stopTts, int reason) {
            throw new AssertionError("forceStop clears later request callbacks");
        }
    }
    static final class Request {
        Mode mode = Mode.VOICE;
        Event event = new Event("current");
        Event duplex;
    }
    record Event(String id) { public String getId() { return id; } }
    static final class Engine { final Account a = new Account(); }
    static final class Account {
        final Map<String, State> requests = new HashMap<>(Map.of("current", State.VAD_END));
        final Speech speech = new Speech();
        public Map<String, State> getRequestInfo() { return requests; }
        public Speech getSpeechEngine() { return speech; }
    }
    static final class Speech {
        boolean main = true, requesting = true;
        public boolean isMainEngine() { return main; }
        public boolean isRequesting() { return requesting; }
    }
}
