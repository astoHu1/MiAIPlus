package io.github.miaiplus;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-use frame capabilities avoid sending image data through Binder transaction bundles. */
public final class FrameStore {
    private record Entry(int owner, long expires, byte[] bytes) {}
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    /**
     * How many unread observations may exist at once.
     *
     * <p>This bounds memory, so it must stay small. It must not bound the task, though: a frame is a convenience
     * for viewing the last screenshot, and a planner that never fetches them used to fill this and stop the whole
     * control session with "尚有未读取的屏幕观察" — which the user experiences as the session expiring mid-task.
     * The oldest entry is dropped instead, and the live task keeps running.
     */
    private static final int MAX_PENDING = 4;
    public static String put(ScreenService.Frame frame, int owner) throws Exception {
        long now = android.os.SystemClock.elapsedRealtime();
        ENTRIES.entrySet().removeIf(e -> e.getValue().expires < now);
        while (ENTRIES.size() >= MAX_PENDING) {
            String oldest = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (var entry : ENTRIES.entrySet()) {
                long expires = entry.getValue().expires;
                if (expires < oldestExpiry) { oldestExpiry = expires; oldest = entry.getKey(); }
            }
            // An entry that vanished concurrently is already gone; re-check the bound rather than assume.
            if (oldest == null || ENTRIES.remove(oldest) == null) break;
        }
        String id = UUID.randomUUID().toString();
        byte[] jpeg = android.util.Base64.decode(frame.image(), android.util.Base64.DEFAULT);
        var bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalStateException("截图编码无效");
        byte[] bytes = new JSONObject().put("image", frame.image()).put("width", bounds.outWidth).put("height", bounds.outHeight)
            .toString().getBytes(StandardCharsets.UTF_8);
        ENTRIES.put(id, new Entry(owner, now + 60000, bytes));
        return "MIAIPLUS_FRAME:" + id;
    }
    public static byte[] take(String id, int caller) throws java.io.FileNotFoundException {
        Entry entry = ENTRIES.get(id);
        if (entry == null || entry.expires < android.os.SystemClock.elapsedRealtime()
            || (entry.owner != caller && caller != android.os.Process.myUid())) throw new java.io.FileNotFoundException("Frame unavailable");
        if (!ENTRIES.remove(id, entry)) throw new java.io.FileNotFoundException("Frame already consumed");
        return entry.bytes;
    }
    public static void clear() { ENTRIES.clear(); }
}
