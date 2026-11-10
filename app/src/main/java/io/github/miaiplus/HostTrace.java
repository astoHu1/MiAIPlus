package io.github.miaiplus;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small host-side bookkeeping for diagnostic records: which routing decision a later XiaoAi error belongs to,
 * and how often a repeating state may be written.
 */
final class HostTrace {
    private static final AtomicLong routes = new AtomicLong();
    private static final Map<String, String> states = new LinkedHashMap<>();
    private static final Map<String, Long> written = new LinkedHashMap<>();
    private static final long ROUTE_MAX_AGE_MS = 120_000;
    private record Route(String reference, long at, boolean forcedAgent) { }
    record Snapshot(String reference, long ageMs, boolean forcedAgent) { }
    private static volatile Route lastRoute = new Route("", 0, false);
    private static volatile String stage = "";

    /** The hook group being installed, so a rollback can name where a version-pinned binding failed. */
    static void stage(String name) { stage = name; }
    static String stage() { return stage; }

    private HostTrace() { }

    /** A new routing decision; later host errors cite it so a refusal can be paired with what was sent. */
    static String route(boolean forcedAgent) {
        String reference = android.os.Process.myPid() + "-" + routes.incrementAndGet();
        lastRoute = new Route(reference, SystemClock.elapsedRealtime(), forcedAgent);
        return reference;
    }
    /** A recent route is temporal context only; it is not proof that an asynchronous error belongs to it. */
    static Snapshot snapshot() {
        Route route = lastRoute;
        long age = SystemClock.elapsedRealtime() - route.at();
        if (route.reference().isEmpty() || age < 0 || age > ROUTE_MAX_AGE_MS) return new Snapshot("", -1, false);
        return new Snapshot(route.reference(), age, route.forcedAgent());
    }
    static String lastRoute() { return snapshot().reference(); }
    static boolean lastRouteForced() { return snapshot().forcedAgent(); }
    /** Milliseconds since a recent routing decision, or -1 when no recent route is known. */
    static long sinceRoute() { return snapshot().ageMs(); }

    /**
     * Whether a record for this state should be written now: when it differs from the last state written under
     * the key, and otherwise at most once per interval. Hooks on hot paths report states, not calls, and the
     * interval lets a state that settled before logging was switched on still appear.
     */
    static boolean changed(String key, String state, long intervalMs) {
        long now = SystemClock.elapsedRealtime();
        synchronized (states) {
            Long previous = written.get(key);
            if (state.equals(states.get(key)) && previous != null && now - previous < intervalMs) return false;
            states.remove(key); written.remove(key);
            states.put(key, state); written.put(key, now);
            while (states.size() > 64) {
                String oldest = states.keySet().iterator().next();
                states.remove(oldest); written.remove(oldest);
            }
            return true;
        }
    }

    static void clear() {
        synchronized (states) { states.clear(); written.clear(); }
        lastRoute = new Route("", 0, false);
        stage = "";
    }
}
