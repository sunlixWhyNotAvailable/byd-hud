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

    public static void main(String[] args) {
        check(outputEdge(1280, true) == 1280 && outputEdge(99999, true) == 1920, "bounded output");
        check(bounded(-1, 6000, 1000, 30000) == 6000, "invalid lease fallback");
        check(interval(true, 200L) == 200L, "active HUD selects five Hz");
        check(interval(true, DEFAULT_MS) == 1000L, "unchanged crop selects one Hz");
        check(interval(false, 100L) == 1000L, "stopped collector returns to idle polling");
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0}) {
            check(interval(true, invalid) == 1000L, "unsupported interval falls back safely");
        }
        long[] requests = {1L, 50L, 100L, 199L, 201L, 500L, 777L, 1000L, 200L};
        for (long request : requests) {
            check(interval(true, request) == request, "rate changes apply on the next poll");
        }
        check(interval(true, 5000L) == 1000L, "slow frames retain control heartbeat");
        check(interval(true, Long.MAX_VALUE) == 1000L, "long delay cannot overflow the scheduler");
        System.out.println("Capture polling: flexible intervals, legacy fallback, idle and heartbeat PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
