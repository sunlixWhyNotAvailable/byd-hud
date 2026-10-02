package com.bydhud.mapcapture;

/** A dead collector must not leave the navigator rendering offscreen indefinitely. */
public final class CaptureLease {
    private volatile long until;
    public void update(boolean active, long now) { update(active, now, 6000); }
    public void update(boolean active, long now, long duration) {
        until = active ? now + CapturePollPolicy.bounded(duration, 6000, 1000, 30000) : 0;
    }
    public boolean active(long now) { return until != 0 && now < until; }
    public static void main(String[] args) {
        CaptureLease lease = new CaptureLease();
        check(!lease.active(0));
        lease.update(true, 100); check(lease.active(6099)); check(!lease.active(6100));
        lease.update(true, 5000); check(lease.active(10999));
        lease.update(false, 5001); check(!lease.active(5001));
        System.out.println("CaptureLease: inactive, renewal, expiry and explicit stop passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
