package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WazeSurfaceHandoffPolicyTest {
    @Test
    public void handoffChoosesOnlyRequiredAction() {
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_NOT_REQUIRED,
                action(false, false, false, -1, -1, 2, false));
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_RELAUNCH,
                action(true, false, false, -1, -1, 2, false));
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_MOVE,
                action(true, false, false, 41, 0, 2, true));
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_WAIT,
                action(true, false, false, 41, 2, 2, false));
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_READY,
                action(true, false, false, 41, 2, 2, true));
        assertEquals(NavHudLiveSender.SURFACE_HANDOFF_FAILED,
                action(true, true, true, -1, -1, 2, false));
    }

    @Test
    public void hiddenExistingSurfaceIsRestored() {
        assertTrue(NavHudLiveSender.wazeSurfaceHandoffNeedsLaunch(
                NavHudLiveSender.SURFACE_HANDOFF_WAIT));
        assertTrue(NavHudLiveSender.wazeSurfaceHandoffNeedsLaunch(
                NavHudLiveSender.SURFACE_HANDOFF_RELAUNCH));
        assertFalse(NavHudLiveSender.wazeSurfaceHandoffNeedsLaunch(
                NavHudLiveSender.SURFACE_HANDOFF_MOVE));
    }

    @Test
    public void readyRequiresCurrentTargetScopedSurfaceDelivery() {
        assertTrue(ready(true, true, true, true, 9L, 9L, 2, 2, 2, 4L, 4L));
        assertFalse(ready(true, true, true, true, 9L, 8L, 2, 2, 2, 4L, 4L));
        assertFalse(ready(true, true, true, true, 9L, 9L, 2, 0, 2, 4L, 4L));
        assertFalse(ready(true, true, true, true, 9L, 9L, 2, 2, 2, 5L, 4L));
        assertFalse(ready(false, true, true, true, 9L, 9L, 2, 2, 2, 4L, 4L));
    }

    @Test
    public void surfaceDestructionDuringEligibleRouteIsTransient() {
        assertTrue(NavHudLiveSender.isTransientWazeSurfaceUnavailable(
                "activity-surface-destroyed", true));
        assertFalse(NavHudLiveSender.isTransientWazeSurfaceUnavailable(
                "surface-delivery-failed", true));
        assertFalse(NavHudLiveSender.isTransientWazeSurfaceUnavailable(
                "activity-surface-destroyed", false));
    }

    private static int action(boolean routeCurrent, boolean failed, boolean dismissed,
            int taskId, int actualDisplay, int targetDisplay, boolean ready) {
        return NavHudLiveSender.wazeSurfaceHandoffAction(
                routeCurrent, failed, dismissed, taskId,
                actualDisplay, targetDisplay, ready);
    }

    private static boolean ready(boolean routeCurrent, boolean active, boolean visible,
            boolean validSurface, long activeInstanceId, long readyInstanceId,
            int actualDisplay, int readyDisplay, int targetDisplay,
            long activeSurfaceEpoch, long readySurfaceEpoch) {
        return NavHudLiveSender.wazeSurfaceReadyForHandoff(
                routeCurrent, active, visible, validSurface,
                activeInstanceId, readyInstanceId,
                actualDisplay, readyDisplay, targetDisplay,
                activeSurfaceEpoch, readySurfaceEpoch);
    }
}
