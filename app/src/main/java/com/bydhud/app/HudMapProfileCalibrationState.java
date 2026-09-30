package com.bydhud.app;

import java.util.Arrays;
import java.util.Objects;

/** Immutable in-memory status for a source map-profile calibration session. */
public final class HudMapProfileCalibrationState {
    public static final HudMapProfileCalibrationState STOPPED =
            new HudMapProfileCalibrationState(false, 0L, null, false, -1L, "", null);

    public final boolean running;
    public final long session;
    public final HudMapProfile draft;
    public final boolean frameReady;
    public final long frameRevision;
    public final String reason;
    private final byte[] png;

    public static HudMapProfileCalibrationState stopped(String reason) {
        if (reason == null || reason.isEmpty()) return STOPPED;
        return new HudMapProfileCalibrationState(false, 0L, null, false, -1L, reason, null);
    }

    HudMapProfileCalibrationState(boolean running, long session, HudMapProfile draft,
            boolean frameReady, long frameRevision, String reason, byte[] png) {
        this.running = running;
        this.session = running ? Math.max(1L, session) : 0L;
        this.draft = running ? draft : null;
        this.png = png == null || png.length == 0 ? new byte[0] : Arrays.copyOf(png, png.length);
        this.frameReady = running && frameReady && this.png.length > 0;
        this.frameRevision = this.frameReady ? frameRevision : -1L;
        this.reason = reason == null ? "" : reason;
    }

    public byte[] png() {
        return png.length == 0 ? new byte[0] : Arrays.copyOf(png, png.length);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HudMapProfileCalibrationState)) return false;
        HudMapProfileCalibrationState other = (HudMapProfileCalibrationState) value;
        return running == other.running && session == other.session
                && frameReady == other.frameReady && frameRevision == other.frameRevision
                && Objects.equals(draft, other.draft) && reason.equals(other.reason)
                && Arrays.equals(png, other.png);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(running, session, draft, frameReady, frameRevision, reason)
                + Arrays.hashCode(png);
    }
}
