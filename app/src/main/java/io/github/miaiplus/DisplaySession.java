package io.github.miaiplus;

import java.util.UUID;

/**
 * Identity and geometry of one virtual display owned by one run.
 *
 * <p>The system may hand the same display id to an unrelated display later, so every request carries the
 * generation minted at open time; a reply from an older generation is rejected instead of being applied to a
 * different screen. Instances are immutable and safe to publish across threads.
 */
public final class DisplaySession {
    public final String generation = UUID.randomUUID().toString();
    public final int displayId;
    public final String surfaceFlingerId;
    public final int width, height, densityDpi, rotation;

    public DisplaySession(int displayId, String surfaceFlingerId, int width, int height, int densityDpi, int rotation) {
        this.displayId = displayId;
        this.surfaceFlingerId = surfaceFlingerId;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.rotation = rotation;
    }

    public boolean matches(int candidateId, String candidateGeneration) {
        return candidateId == displayId && generation.equals(candidateGeneration);
    }

    @Override public String toString() {
        return "display=" + displayId + " " + width + "x" + height + "@" + densityDpi + " rot=" + rotation;
    }
}
