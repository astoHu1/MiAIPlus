package io.github.miaiplus;

/** Opening and user activity clock shared by every XiaoAI surface. Replies never touch it. */
final class ConversationPolicy {
    enum Decision { DISABLED, BUSY, CONTINUE, NEW }

    private long lastActivity;
    private boolean hasConversation;

    ConversationPolicy(long lastActivity, boolean hasConversation) {
        this.lastActivity = Math.max(0, lastActivity);
        this.hasConversation = hasConversation;
    }

    synchronized Decision wake(long now, int timeoutMinutes, boolean busy) {
        if (timeoutMinutes <= 0) return Decision.DISABLED;
        if (busy) return Decision.BUSY;
        long timeout = Math.min(1440, timeoutMinutes) * 60_000L;
        // A backwards wall-clock change must not continue an arbitrarily old conversation.
        boolean fresh = hasConversation && lastActivity > 0 && now >= lastActivity
            && now - lastActivity <= timeout;
        // An accepted opening starts its own timeout before asynchronous native routing finishes.
        // Expiry also retires the old selection: another entry callback must not resume it merely
        // because this opening has already advanced the clock. Only selection confirms a new topic.
        lastActivity = Math.max(0, now);
        if (!fresh) hasConversation = false;
        return fresh ? Decision.CONTINUE : Decision.NEW;
    }

    synchronized void userActivity(long now) { lastActivity = Math.max(0, now); }

    synchronized void selected(long now) {
        hasConversation = true;
        userActivity(now);
    }

    synchronized long lastActivity() { return lastActivity; }
    synchronized boolean hasConversation() { return hasConversation; }
    synchronized void invalidate() { hasConversation = false; }

    static boolean acceptsExplicitRoute(boolean hasPendingWake, boolean moduleReturn,
                                        long wakeInputSequence, long currentInputSequence) {
        return !hasPendingWake || moduleReturn || currentInputSequence > wakeInputSequence;
    }
}
