package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class WazeListenerFrameOrderingTest {
    @Test
    public void selectedGuidanceClearFencesBothSourcesAndAllowsNewGuidance() {
        NavHudLiveSender.WazeListenerFrameSlot slots = new NavHudLiveSender.WazeListenerFrameSlot();
        DirectTbtFrame oldFrame = guidanceFrame();
        DirectTbtFrame nextFrame = guidanceFrame();
        slots.offer("com.waze", 1, oldFrame, 10, "old", null, false, 0, 0, 0);
        slots.offer("com.waze", 2, oldFrame, 9, "surface", null, true, 3, 4, 5);
        NavHudLiveSender.WazeListenerFrameBatch alreadyTaken = slots.take();

        long clear = slots.beginGuidanceClear();
        DirectTbtFrame nextSurface = guidanceFrame();
        assertFalse(slots.offer("com.waze", 2, nextSurface, 10,
                "surface-after-ingress", null, true, 3, 4, 5));
        assertFalse(slots.offer("com.waze", 1, nextFrame, 13,
                "cluster-after-ingress", null, false, 0, 0, 0));
        assertSame(oldFrame, slots.frameWithCurrentGuidance(oldFrame, false, 10));
        slots.applyGuidanceClear(false, 12, true, 9);
        assertTrue(slots.isCurrent(alreadyTaken.cluster));
        assertTrue(slots.isCurrent(alreadyTaken.surface));
        DirectTbtFrame staleCluster = slots.frameWithCurrentGuidance(
                alreadyTaken.cluster.frame, false, alreadyTaken.cluster.guidanceSequence);
        DirectTbtFrame staleSurface = slots.frameWithCurrentGuidance(
                alreadyTaken.surface.frame, true, alreadyTaken.surface.guidanceSequence);
        assertFalse(staleCluster.hasGuidance());
        assertFalse(staleSurface.hasGuidance());
        assertSame(oldFrame.getTripMetrics(), staleCluster.getTripMetrics());
        assertSame(oldFrame.getSpeedLimit(), staleCluster.getSpeedLimit());
        assertTrue(staleCluster.getAlertOverlay().isActive());
        assertFalse(staleCluster.getAlertOverlay().useRouteFrame());

        assertSame(nextSurface, slots.frameWithCurrentGuidance(nextSurface, true, 10));
        assertTrue(slots.finishAlertClear(clear));
        NavHudLiveSender.WazeListenerFrameBatch restored = slots.take();
        assertSame(nextFrame, restored.cluster.frame);
        assertSame(nextSurface, restored.surface.frame);
        assertTrue(slots.frameWithCurrentGuidance(
                restored.cluster.frame, false, restored.cluster.guidanceSequence).hasGuidance());
        assertTrue(slots.frameWithCurrentGuidance(
                restored.surface.frame, true, restored.surface.guidanceSequence).hasGuidance());

        slots.offer("com.waze", 1, nextFrame, 13, "cluster", null, false, 0, 0, 0);
        long surfaceClear = slots.beginGuidanceClear();
        slots.applyGuidanceClear(true, 11, false, 13);
        assertTrue(slots.finishAlertClear(surfaceClear));
        assertSame(nextFrame, slots.take().cluster.frame);
        assertSame(nextFrame, slots.frameWithCurrentGuidance(nextFrame, false, 13));

        long obsoleteClear = slots.beginGuidanceClear();
        slots.invalidate();
        assertFalse(slots.finishAlertClear(obsoleteClear));
        assertFalse(slots.isCurrent(restored.cluster));
    }

    private static DirectTbtFrame guidanceFrame() {
        DirectTbtFrame.TripMetrics metrics = DirectTbtFrame.TripMetrics.nextStopOnly(
                new DirectTbtFrame.TravelMetrics(1_800_000L, 600L, 8_000L));
        DirectTbtFrame.SpeedLimit speed = new DirectTbtFrame.SpeedLimit(60, 60, "km/h", 100L);
        DirectTbtFrame.AlertOverlay alert = DirectTbtFrame.AlertOverlay.active(
                7, 35, "speed camera", null).withRouteFrame(true);
        return new DirectTbtFrame(1, 2, 1, 300, "road", "turn", "road",
                new byte[]{1}, new byte[]{2}, Collections.emptyList(), alert, metrics)
                .withSpeedLimit(speed);
    }
}
