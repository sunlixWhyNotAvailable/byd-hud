package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class NavHudLiveSenderFrameHandoffBehaviorTest {
    private static final String WAZE = "com.waze";

    @Test
    public void alternatingSourceBurstKeepsLatestFrameForEachSource() {
        NavHudLiveSender.WazeListenerFrameSlot slot =
                new NavHudLiveSender.WazeListenerFrameSlot();

        assertTrue(offer(slot, "cluster-1", false));
        assertFalse(offer(slot, "surface-1", true));
        assertFalse(offer(slot, "cluster-2", false));
        assertFalse(offer(slot, "surface-2", true));

        NavHudLiveSender.WazeListenerFrameBatch batch = slot.take();
        assertFalse(batch.deferred);
        assertEquals("cluster-2", batch.cluster.frame.getRoadText());
        assertEquals("surface-2", batch.surface.frame.getRoadText());
        assertEquals(1, batch.coalescedCluster);
        assertEquals(1, batch.coalescedSurface);
        assertTrue(batch.cluster.ingressSequence < batch.surface.ingressSequence);
        assertFalse(batch.cluster.fromSurface);
        assertEquals(3, batch.cluster.sessionGeneration);
        assertTrue(batch.surface.fromSurface);
        assertEquals(7, batch.surface.sessionGeneration);
        assertEquals(11L, batch.surface.surfaceDeliveryGeneration);
        assertEquals(17L, batch.surface.surfaceInstanceId);
        assertEquals(19L, batch.surface.surfaceEpoch);

        NavHudLiveSender.WazeListenerFrameBatch empty = slot.take();
        assertNull(empty.cluster);
        assertNull(empty.surface);
    }

    @Test
    public void latestPerSourceIsRetainedWhenSurfaceArrivesAfterCluster() {
        NavHudLiveSender.WazeListenerFrameSlot slot =
                new NavHudLiveSender.WazeListenerFrameSlot();

        offer(slot, "surface-1", true);
        offer(slot, "cluster-1", false);
        offer(slot, "surface-2", true);

        NavHudLiveSender.WazeListenerFrameBatch batch = slot.take();
        assertEquals("cluster-1", batch.cluster.frame.getRoadText());
        assertEquals("surface-2", batch.surface.frame.getRoadText());
        assertTrue(batch.surface.ingressSequence > batch.cluster.ingressSequence);
    }

    @Test
    public void alertClearDropsEarlierFramesAndReleasesLaterIngressAfterControl() {
        NavHudLiveSender.WazeListenerFrameSlot slot =
                new NavHudLiveSender.WazeListenerFrameSlot();
        offer(slot, "stale-before-clear", false);

        long controlSequence = slot.beginAlertClear();
        assertFalse(slot.isCurrent(0));
        assertFalse(slot.offer(WAZE, 3, frame("fresh-after-clear"), "frame", null,
                false, 0L, 0L, 0L));

        NavHudLiveSender.WazeListenerFrameBatch beforeControl = slot.take();
        assertNull(beforeControl.cluster);
        assertNull(beforeControl.surface);
        assertTrue(slot.finishAlertClear(controlSequence));

        NavHudLiveSender.WazeListenerFrameBatch afterControl = slot.take();
        assertNotNull(afterControl.cluster);
        assertEquals("fresh-after-clear", afterControl.cluster.frame.getRoadText());
        assertTrue(afterControl.cluster.ingressSequence > controlSequence);
        assertNull(afterControl.surface);
    }

    @Test
    public void terminalInvalidationDropsPendingSourcesAndRejectsOldInputGeneration() {
        NavHudLiveSender.WazeListenerFrameSlot slot =
                new NavHudLiveSender.WazeListenerFrameSlot();
        offer(slot, "cluster-pending", false);
        offer(slot, "surface-pending", true);
        assertTrue(slot.isCurrent(0));

        slot.invalidate();

        assertFalse(slot.isCurrent(0));
        NavHudLiveSender.WazeListenerFrameBatch cleared = slot.take();
        assertNull(cleared.cluster);
        assertNull(cleared.surface);
        assertTrue(offer(slot, "fresh-after-terminal", true));
        NavHudLiveSender.WazeListenerFrameBatch fresh = slot.take();
        assertEquals("fresh-after-terminal", fresh.surface.frame.getRoadText());
        assertNull(fresh.cluster);
    }

    private static boolean offer(NavHudLiveSender.WazeListenerFrameSlot slot,
            String road, boolean fromSurface) {
        return slot.offer(WAZE, fromSurface ? 7 : 3, frame(road), road,
                null, fromSurface, fromSurface ? 11L : 0L,
                fromSurface ? 17L : 0L, fromSurface ? 19L : 0L);
    }

    private static DirectTbtFrame frame(String road) {
        return new DirectTbtFrame(11, 3, 9, 420, road, "Cue", road,
                null, null, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive());
    }
}
