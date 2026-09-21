package com.bydhud.app;

/** Cached UI snapshot; no platform reads are performed while rendering it. */
public final class ShanghaiTestState {
    public enum Phase { IDLE, STARTING, PREPARING, DRIVING, FINISHING,
        COMPLETED, STOPPED, ERROR, RECOVERING }

    public static final int PREPARATION_SECONDS = 15;
    public static final int DURATION_SECONDS = 295;
    public final Phase phase;
    public final int elapsedSeconds;
    public final String sessionId;
    public final String detail;
    public final String captureCoverage;
    public final boolean cleanupPending;
    public final String captureDay;
    public final String logcatDay;

    public ShanghaiTestState() {
        this(Phase.IDLE, 0, "", "", "", false, "", "");
    }

    ShanghaiTestState(Phase phase, int elapsedSeconds, String sessionId, String detail,
            String captureCoverage, boolean cleanupPending, String captureDay, String logcatDay) {
        this.phase = phase;
        this.elapsedSeconds = Math.max(0, Math.min(DURATION_SECONDS, elapsedSeconds));
        this.sessionId = safe(sessionId);
        this.detail = safe(detail);
        this.captureCoverage = safe(captureCoverage);
        this.cleanupPending = cleanupPending;
        this.captureDay = safe(captureDay);
        this.logcatDay = safe(logcatDay);
    }

    public boolean isBusy() {
        return phase == Phase.STARTING || phase == Phase.PREPARING
                || phase == Phase.DRIVING || phase == Phase.FINISHING
                || phase == Phase.RECOVERING;
    }

    public boolean canStop() {
        return phase == Phase.STARTING || phase == Phase.PREPARING || phase == Phase.DRIVING;
    }

    public boolean canReset() {
        return phase != Phase.FINISHING && phase != Phase.RECOVERING;
    }

    public float progress() { return elapsedSeconds / (float) DURATION_SECONDS; }

    public boolean protectsDay(String day) {
        return isBusy() && day != null && !day.isEmpty()
                && (day.equals(captureDay) || day.equals(logcatDay));
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
