package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DenzaOutputCadenceTest {
    @Test public void arRepeatsAtDenzaCadenceWithoutRestartingEvery50ms() {
        DenzaOutputCadence cadence = new DenzaOutputCadence();
        byte[] frame = {1};
        assertTrue(cadence.shouldSend(frame, true, 1000));
        cadence.sent(frame, true, 1000);
        for (long at = 1050; at < 1350; at += 50) assertFalse(cadence.shouldSend(frame, true, at));
        assertTrue(cadence.shouldSend(frame, true, 1350));
    }
    @Test public void changedGuidanceAndLostGeometryPublishImmediately() {
        DenzaOutputCadence cadence = new DenzaOutputCadence();
        cadence.sent(new byte[]{1}, true, 1000);
        assertTrue(cadence.shouldSend(new byte[]{2}, true, 1001));
        assertTrue(cadence.shouldSend(new byte[]{1}, false, 1001));
    }
    @Test public void unchangedCompactGuidanceHasFiveSecondHeartbeat() {
        DenzaOutputCadence cadence = new DenzaOutputCadence();
        cadence.sent(new byte[]{1}, false, 1000);
        assertFalse(cadence.shouldSend(new byte[]{1}, false, 5999));
        assertTrue(cadence.shouldSend(new byte[]{1}, false, 6000));
        cadence.reset();
        assertTrue(cadence.shouldSend(new byte[]{1}, false, 1001));
    }
}
