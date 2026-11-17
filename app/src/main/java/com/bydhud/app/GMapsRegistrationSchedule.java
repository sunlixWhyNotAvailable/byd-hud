package com.bydhud.app;

/** Registration burst followed by event waiting, or slow discovery for legacy bridges. */
final class GMapsRegistrationSchedule {
    private long startedAt;
    private int attempts;

    void begin(long now) {
        startedAt = now;
        attempts = 0;
    }

    int attempts() { return attempts; }

    long afterAttempt(long now, boolean readyEvents) {
        attempts = Math.min(3, attempts + 1);
        if (attempts == 1) return Math.max(0L, startedAt + 5_000L - now);
        if (attempts == 2) return Math.max(0L, startedAt + 15_000L - now);
        return readyEvents ? -1L : 60_000L;
    }
}
