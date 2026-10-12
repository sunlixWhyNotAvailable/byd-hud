package com.bydhud.mapcapture;

/** A dead collector must not leave the navigator rendering offscreen indefinitely. */
public final class CaptureLease {
    private volatile long until;
    public void update(boolean active, long now, long duration) {
        until = active ? now + CapturePollPolicy.bounded(duration, 6000, 1000, 30000) : 0;
    }
    public boolean active(long now) { return until != 0 && now < until; }
}
