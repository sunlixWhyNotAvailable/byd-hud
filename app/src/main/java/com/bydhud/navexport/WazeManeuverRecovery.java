package com.bydhud.navexport;

final class WazeManeuverRecovery {
    static final int MAX_ATTEMPTS = 3;
    private static final long NO_RETRY = -1L;
    private static final long FIRST_RETRY_MS = 2_000L;
    private static final long SECOND_RETRY_MS = 5_000L;

    private int attempts;
    private long retryAtMs = NO_RETRY;

    void reset() {
        attempts = 0;
        retryAtMs = NO_RETRY;
    }

    int beginAttempt() {
        if (attempts >= MAX_ATTEMPTS) return 0;
        retryAtMs = NO_RETRY;
        return ++attempts;
    }

    void finishAttempt(long finishedAtMs, boolean retryNeeded) {
        if (!retryNeeded || attempts >= MAX_ATTEMPTS) {
            retryAtMs = NO_RETRY;
            return;
        }
        retryAtMs = finishedAtMs + (attempts == 1 ? FIRST_RETRY_MS : SECOND_RETRY_MS);
    }

    boolean retryDue(long nowMs) {
        return retryAtMs >= 0 && nowMs >= retryAtMs;
    }

    long retryDelayMs(long nowMs) {
        return retryAtMs < 0 ? NO_RETRY : Math.max(0L, retryAtMs - nowMs);
    }

    int attempts() {
        return attempts;
    }

    static int missingCoordinateCount(RouteData.Maneuver[] maneuvers) {
        int missing = 0;
        for (RouteData.Maneuver maneuver : maneuvers) {
            if (maneuver.latitude == 0.0 && maneuver.longitude == 0.0) missing++;
        }
        return missing;
    }

    static boolean needsRetry(RouteData.Maneuver[] maneuvers) {
        return maneuvers.length == 0 || missingCoordinateCount(maneuvers) != 0;
    }

    static RouteData.Maneuver[] preserveUsefulResponse(RouteData.Maneuver[] previous,
            RouteData.Maneuver[] received) {
        if (previous.length > 0 && received.length == 0) return previous;
        int previousAvailable = previous.length - missingCoordinateCount(previous);
        int receivedAvailable = received.length - missingCoordinateCount(received);
        if (previousAvailable > 0 && needsRetry(received)
                && receivedAvailable < previousAvailable) {
            return previous;
        }
        return received;
    }
}
