package com.bydhud.mapcapture;

/** The HUD owns crop-based rate selection; older collectors remain at one Hz. */
public final class CapturePollPolicy {
    public static final long MIN_MS = 1L;
    public static final long DEFAULT_MS = 1000L;

    public static final String CAPABILITIES = "bydhud-map-v2:source-mode,output-edge,frame-timeout,lease";
    public static long bounded(long requested, long fallback, long min, long max) {
        return requested < min || requested > max ? fallback : requested;
    }
    public static int outputEdge(int requested, boolean full) {
        return (int) bounded(requested, full ? 1920 : 320, 64, full ? 1920 : 320);
    }

    public static long interval(boolean active, long requestedMs) {
        // The HUD gates frame requests separately. Keep lease/control polling alive
        // at least once a second even when it selects a slower image cadence.
        return active && requestedMs > 0L ? Math.min(requestedMs, DEFAULT_MS) : DEFAULT_MS;
    }

}
