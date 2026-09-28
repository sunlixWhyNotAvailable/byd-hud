package com.bydhud.app;

import java.util.Objects;

/** Immutable status for the current in-memory map live-display session. */
public final class HudMapLiveState {
    public static final HudMapLiveState STOPPED = new HudMapLiveState(false, 0L);

    public final boolean running;
    public final long session;

    public HudMapLiveState(boolean running, long session) {
        this.running = running;
        this.session = running ? Math.max(1L, session) : 0L;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HudMapLiveState)) return false;
        HudMapLiveState other = (HudMapLiveState) value;
        return running == other.running && session == other.session;
    }

    @Override
    public int hashCode() {
        return Objects.hash(running, session);
    }
}
