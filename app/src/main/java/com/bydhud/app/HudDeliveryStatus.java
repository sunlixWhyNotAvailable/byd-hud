package com.bydhud.app;

import android.os.SystemClock;

//Tracks actual SOME/IP delivery instead of treating an armed sender as active output.
final class HudDeliveryStatus {
    private static final long RUNNING_FRESH_MS = 2_500L;
    private static volatile long lastSuccessfulNonClearMs;
    private static volatile boolean transportFailed;

    private HudDeliveryStatus() {
    }

    static void recordNonClearResult(int result) {
        if (result == 0) {
            lastSuccessfulNonClearMs = SystemClock.elapsedRealtime();
            transportFailed = false;
        } else {
            lastSuccessfulNonClearMs = 0L;
            transportFailed = true;
        }
    }

    static void recordFailure() {
        lastSuccessfulNonClearMs = 0L;
        transportFailed = true;
    }

    static void reset() {
        lastSuccessfulNonClearMs = 0L;
        transportFailed = false;
    }

    static boolean isRunning() {
        long successfulAt = lastSuccessfulNonClearMs;
        return successfulAt > 0L
                && SystemClock.elapsedRealtime() - successfulAt <= RUNNING_FRESH_MS;
    }

    static boolean hasTransportFailure() {
        return transportFailed;
    }
}
