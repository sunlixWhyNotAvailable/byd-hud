package com.bydhud.mapcapture;

/** Capture demand is separate from the producer's control heartbeat. */
public final class WazeRenderPolicy {
    public static long retryDelay(long imageIntervalMs) {
        return Math.min(Math.max(1, imageIntervalMs), 50);
    }
    public static boolean admit(long now, long retryAt, boolean bootstrap,
            boolean pendingCapture, boolean drawingCallback) {
        return (bootstrap || pendingCapture || drawingCallback) && now >= retryAt;
    }
    public static long idleDelay(long now, long retryAt, boolean workDue) {
        return workDue ? Math.max(1, Math.min(1000, retryAt - now)) : 1000;
    }
}
