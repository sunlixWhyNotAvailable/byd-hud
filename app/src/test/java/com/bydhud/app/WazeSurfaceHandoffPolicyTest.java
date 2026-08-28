package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    @Test
    public void surfaceTeardownWaitsForBoundedHostAcknowledgment() throws IOException {
        String activity = sourcePath(
                "app/src/main/java/com/bydhud/app/WazeSurfaceActivity.java");
        String channel = sourcePath(
                "app/src/main/java/com/bydhud/app/WazeDirectChannel.java");

        assertTrue(activity.contains("SURFACE_DESTROY_ACK_TIMEOUT_MS = 250L"));
        assertTrue(activity.contains("private volatile Surface surface"));
        assertTrue(activity.contains("private volatile int surfaceWidth"));
        assertTrue(activity.contains("private volatile int surfaceHeight"));
        assertTrue(activity.contains("private volatile int surfaceDpi"));
        assertTrue(activity.contains("private volatile Rect visibleArea"));
        assertTrue(activity.contains("private volatile long surfaceEpoch"));
        assertTrue(activity.contains("private volatile boolean visible"));
        assertTrue(activity.contains("Surface currentSurface = activity.surface"));
        assertTrue(activity.contains("bridge.onSurfaceDestroyed(destroyed::countDown)"));
        assertTrue(activity.contains(
                "destroyed.await(SURFACE_DESTROY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)"));
        assertTrue(channel.contains(
                "notifySurfaceDestroyed(\"activity-surface-destroyed\", completion)"));
        assertTrue(channel.contains(
                "new DoneCallback(expectedGeneration, \"onSurfaceDestroyed\", null,"));
        assertTrue(channel.contains("if (completion != null) completion.run()"));
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

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
