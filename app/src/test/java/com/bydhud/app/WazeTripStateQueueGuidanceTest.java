package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WazeTripStateQueueGuidanceTest {
    @Test
    public void snapshotKeepsGuidanceSequenceSeparateFromNewerMetricsIngress() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        assertTrue(queue.merge(step(10L, "step-10", true, 0)));
        assertTrue(queue.merge(metrics(11L, "metrics-11")));

        WazeTripStateQueue.Snapshot<String, String> snapshot = queue.take();

        assertTrue(snapshot.stepUpdated);
        assertTrue(snapshot.metricsUpdated);
        assertEquals(11L, snapshot.sequence);
        assertEquals(10L, snapshot.guidanceSequence);
        assertTrue(snapshot.distanceKnown);
        assertEquals(0, snapshot.distanceMeters);
    }

    @Test
    public void invalidatingOlderStepPreservesNewerMetricsAndRejectsLateOldStep() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        assertTrue(queue.merge(step(10L, "step-10", true, 42)));
        assertTrue(queue.merge(metrics(11L, "metrics-11")));

        queue.discardThrough(10L);

        assertFalse(queue.merge(step(10L, "late-step-10", true, 7)));
        WazeTripStateQueue.Snapshot<String, String> snapshot = queue.take();

        assertFalse(snapshot.stepUpdated);
        assertTrue(snapshot.metricsUpdated);
        assertEquals(-1L, snapshot.guidanceSequence);
        assertEquals(11L, snapshot.sequence);
        assertEquals("metrics-11", snapshot.metrics);
    }

    @Test
    public void authoritativeStepWithNoSemanticLanesClearsPriorLaneStrip() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        assertTrue(queue.merge(lanes(10L, "step-10", false, true)));
        WazeTripStateQueue.Snapshot<String, String> initial = queue.take();
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, initial.laneDisposition);
        queue.finishProcessing();

        assertTrue(queue.merge(lanes(11L, "step-11", true, false)));
        WazeTripStateQueue.Snapshot<String, String> cleared = queue.take();

        assertTrue(cleared.laneUpdated);
        assertEquals(WazeTripStateQueue.LANES_CLEAR, cleared.laneDisposition);
    }

    private static WazeTripStateQueue.Update<String, String> step(
            long sequence, String value, boolean distanceKnown, int distanceMeters) {
        return new WazeTripStateQueue.Update<>(sequence, sequence, true, value,
                distanceKnown, distanceMeters, false, -1, false, false, null);
    }

    private static WazeTripStateQueue.Update<String, String> metrics(
            long sequence, String value) {
        return new WazeTripStateQueue.Update<>(sequence, sequence, false, null,
                false, 0, false, -1, false, true, value);
    }

    private static WazeTripStateQueue.Update<String, String> lanes(
            long sequence, String value, boolean authoritative, boolean hasGuidance) {
        return new WazeTripStateQueue.Update<>(sequence, sequence, true, value,
                true, 0, authoritative, 5, hasGuidance, false, null);
    }
}
