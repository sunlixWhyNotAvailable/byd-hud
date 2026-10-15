package com.bydhud.app;

import java.util.Arrays;

/** Denza Apps HudGuidanceAccessibilityMonitor: changed guidance/AR state immediately,
 * 350 ms while AR is active, otherwise an unchanged compact instruction every 5 seconds.
 * A failed send never advances the clock. The existing coordinator owns the only transport.
 */
final class DenzaOutputCadence {
    static final long POLL_MS = 350L;
    private byte[] guidance;
    private boolean arActive;
    private long sentAt;

    boolean shouldSend(byte[] next, boolean ar, long now) {
        return guidance == null || !Arrays.equals(guidance, next) || ar != arActive
                || now < sentAt || now - sentAt >= (ar ? POLL_MS : 5000L);
    }

    void sent(byte[] next, boolean ar, long now) {
        guidance = next.clone();
        arActive = ar;
        sentAt = now;
    }

    void reset() { guidance = null; }
}
