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
    public void surfaceSourceWaitsForWindowAndUsableCurrentRouteFrame() {
        assertFalse(select(true, true, false, false, false, false, true, true));
        assertFalse(select(true, false, true, true, true, false, true, true));
        assertFalse(select(true, true, true, false, true, false, true, true));
        assertFalse(select(true, true, true, true, false, false, true, true));
        assertTrue(select(true, true, true, true, true, false, true, true));

        // The same predicate covers frame-before-ready and ready-before-frame orders.
        assertFalse(select(true, false, true, true, true, false, true, true));
        assertTrue(select(true, true, true, true, true, false, true, true));
        assertFalse(select(true, true, false, false, false, false, true, true));
        assertTrue(select(true, true, true, true, true, false, true, true));
    }

    @Test
    public void surfaceSourceRejectsTerminalHudOffShutdownAndNonRouteFrames() {
        assertFalse(select(true, true, true, true, true, true, true, true));
        assertFalse(select(true, true, true, true, true, false, false, true));
        assertFalse(select(true, true, true, true, true, false, true, false));
        assertFalse(NavHudLiveSender.isUsableWazeNavigationFrame(
                DirectTbtFrame.empty()));
        assertFalse(NavHudLiveSender.isUsableWazeNavigationFrame(
                new DirectTbtFrame(-1, 0, 0, 0, "", "", "", null, null,
                        java.util.Collections.emptyList(),
                        DirectTbtFrame.AlertOverlay.active(7, 25, "hazard", null),
                        DirectTbtFrame.TripMetrics.empty())));
        assertFalse(NavHudLiveSender.isUsableWazeNavigationFrame(
                new DirectTbtFrame(-1, 0, 0, 0, "", "", "", null, null,
                        java.util.Collections.emptyList(),
                        DirectTbtFrame.AlertOverlay.inactive(),
                        DirectTbtFrame.TripMetrics.nextStopOnly(
                                new DirectTbtFrame.TravelMetrics(-1L, 15L, 500L)))));
        assertFalse(NavHudLiveSender.isUsableWazeNavigationFrame(
                DirectTbtFrame.empty().withSpeedLimit(
                        new DirectTbtFrame.SpeedLimit(50, 50, "km/h", 1L))));
        assertTrue(NavHudLiveSender.isUsableWazeNavigationFrame(
                new DirectTbtFrame(-1, 0, 0, 0, "road", "", "", null, null,
                        java.util.Collections.emptyList(),
                        DirectTbtFrame.AlertOverlay.inactive())));
        assertTrue(NavHudLiveSender.isUsableWazeNavigationFrame(
                new DirectTbtFrame(-1, 0, 0, 0, "", "", "", null, null,
                        java.util.Collections.singletonList(
                                new DirectTbtFrame.Lane(1, true, "straight")),
                        DirectTbtFrame.AlertOverlay.inactive())));
        assertTrue(NavHudLiveSender.isUsableWazeNavigationFrame(
                new DirectTbtFrame(-1, 2, 2, 0, "", "", "", null, null,
                        java.util.Collections.emptyList(),
                        DirectTbtFrame.AlertOverlay.inactive())));
        assertTrue(NavHudLiveSender.isUsableWazeNavigationFrame(routeFrame()));
    }

    @Test
    public void clusterSuccessorAvoidsClearOnlyWhenCurrentAndUsable() {
        assertTrue(NavHudLiveSender.shouldRestoreWazeClusterWithoutClear(
                true, true, true, true));
        assertFalse(NavHudLiveSender.shouldRestoreWazeClusterWithoutClear(
                false, true, true, true));
        assertFalse(NavHudLiveSender.shouldRestoreWazeClusterWithoutClear(
                true, false, true, true));
        assertFalse(NavHudLiveSender.shouldRestoreWazeClusterWithoutClear(
                true, true, false, true));
        assertFalse(NavHudLiveSender.shouldRestoreWazeClusterWithoutClear(
                true, true, true, false));
    }

    @Test
    public void leaseFollowsSelectedGuidanceSource() {
        assertTrue(NavHudLiveSender.shouldRenewWazeLeaseForSource(
                true, false, false));
        assertFalse(NavHudLiveSender.shouldRenewWazeLeaseForSource(
                true, false, true));
        assertTrue(NavHudLiveSender.shouldRenewWazeLeaseForSource(
                true, true, true));
        assertFalse(NavHudLiveSender.shouldRenewWazeLeaseForSource(
                true, true, false));
        assertFalse(NavHudLiveSender.shouldRenewWazeLeaseForSource(
                false, false, false));
    }

    @Test
    public void retainedWazeHudStopReleasesSurfaceSourceBeforeDeactivation()
            throws IOException {
        assertTrue(NavHudLiveSender.shouldReleaseWazeSurfaceBeforeHudStopForTest(
                true, true, true));
        assertFalse(NavHudLiveSender.shouldReleaseWazeSurfaceBeforeHudStopForTest(
                true, true, false));
        assertFalse(NavHudLiveSender.shouldReleaseWazeSurfaceBeforeHudStopForTest(
                false, true, true));
        assertFalse(NavHudLiveSender.shouldReleaseWazeSurfaceBeforeHudStopForTest(
                true, false, true));

        String sender = sourcePath(
                "app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        int stop = sender.indexOf("private void stopOnMain(");
        int release = sender.indexOf("waze-stop-retain:", stop);
        int deactivate = sender.indexOf("active = false;", stop);
        assertTrue(stop >= 0 && release > stop && deactivate > release);
    }

    @Test
    public void surfaceCacheDeliveryIdentityRejectsOldCallbacksAndAcceptsNewOnes() {
        assertTrue(NavHudLiveSender.isCurrentWazeSurfaceCacheIdentityForTest(
                true, 7L, 7L));
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceCacheIdentityForTest(
                true, 7L, 8L));
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceCacheIdentityForTest(
                false, 8L, 8L));

        // An old callback captured before Surface loss cannot adopt the new identity.
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceCacheIdentityForTest(
                true, 11L, 12L));
        assertTrue(NavHudLiveSender.isCurrentWazeSurfaceCacheIdentityForTest(
                true, 12L, 12L));
    }

    @Test
    public void hiddenSurfaceRecreationRejectsCacheEvenWithoutUnavailableCallback() {
        long frameInstance = 9L;
        long frameEpoch = 4L;
        long activeEpoch = frameEpoch;
        assertTrue(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                frameInstance, frameEpoch, 9L, activeEpoch, true));

        // Pause/destruction; also covers HUD-off where unavailable callbacks are ignored.
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                frameInstance, frameEpoch, 9L, activeEpoch, false));
        activeEpoch++;
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                frameInstance, frameEpoch, 9L, activeEpoch, true));

        // New ready evidence alone cannot make the old frame current.
        boolean oldFrameCurrent = NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                frameInstance, frameEpoch, 9L, activeEpoch, true);
        assertFalse(select(true, true, true, oldFrameCurrent, true, false, true, true));
        frameEpoch = activeEpoch;
        boolean newFrameCurrent = NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                frameInstance, frameEpoch, 9L, activeEpoch, true);
        assertTrue(select(true, true, true, newFrameCurrent, true, false, true, true));
        assertFalse(select(true, false, true, newFrameCurrent, true, false, true, true));
    }

    @Test
    public void activityReplacementCannotReuseAnEqualSurfaceEpoch() {
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                9L, 1L, 10L, 1L, true));
        assertTrue(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                10L, 1L, 10L, 1L, true));
        assertFalse(NavHudLiveSender.isCurrentWazeSurfaceWindowIdentity(
                0L, 0L, 0L, 0L, true));
    }

    @Test
    public void queuedFramesMustMatchSelectedSourceAndCurrentGeneration() {
        assertTrue(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, false, false));
        assertTrue(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, true, false));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, false, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, false, true, true, false, false));
    }

    @Test
    public void sourceStateIsDistinctFromWindowReadinessInProductionPath() throws IOException {
        String sender = sourcePath(
                "app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        assertTrue(sender.contains("surfaceSourceSelected"));
        assertTrue(sender.contains(
                "waze surface window ready; keeping cluster source until current route frame"));
        assertTrue(sender.contains(
                "sourceCurrent, publisherCurrent, fromSurface,\n"
                        + "                    wazeSurfaceSourceSelected"));
        assertTrue(sender.contains("withRetainedWazeClusterAlert"));
        assertTrue(sender.contains("latestRestorableWazeFrame()"));
        assertTrue(sender.contains("isCurrentWazeSurfaceFrame"));
        assertTrue(sender.contains("waze data source switch previous="));
        assertTrue(sender.contains("switchWazeSurfaceToClusterBeforeReadinessLoss"));
        assertTrue(sender.contains(
                "long callbackDeliveryGeneration = wazeSurfaceFrameDeliveryGeneration;"));
        assertTrue(sender.contains(
                "latestWazeSurfaceFrameDeliveryGeneration = callbackDeliveryGeneration;"));
        assertTrue(sender.contains("wazeSurfaceFrameDeliveryGeneration++"));
        int surfaceListener = sender.indexOf("private WazeDirectChannel.Listener createWazeSurfaceListener(");
        int callbackFrame = sender.indexOf("public void onFrame(", surfaceListener);
        int captureEpoch = sender.indexOf("long callbackEpoch = WazeSurfaceActivity.activeSurfaceEpoch();", callbackFrame);
        int postFrame = sender.indexOf("handler.post(() ->", callbackFrame);
        assertTrue(surfaceListener >= 0 && callbackFrame > surfaceListener
                && captureEpoch > callbackFrame && captureEpoch < postFrame);
        assertTrue(sender.contains("latestWazeSurfaceFrameEpoch = callbackEpoch;"));
        assertTrue(sender.contains("latestWazeSurfaceFrameEpoch = retainedSurfaceEpoch;"));
        assertTrue(sender.contains("isCurrentWazeSurfaceWindow(callbackInstanceId, callbackEpoch)"));
        int activation = sender.indexOf("private void activateWazeSurface(");
        int activationEnd = sender.indexOf("\n    private void invalidateWazeSurfaceReadiness", activation);
        assertTrue(activation >= 0 && activationEnd > activation);
        assertFalse(sender.substring(activation, activationEnd)
                .contains("clearDirectFrameForLoss"));
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

    private static boolean select(boolean routeCurrent, boolean windowReady,
            boolean framePresent, boolean frameCurrent, boolean frameUsable,
            boolean terminal, boolean hudEnabled, boolean runtimeEnabled) {
        return NavHudLiveSender.shouldSelectWazeSurfaceSource(
                routeCurrent, windowReady, framePresent, frameCurrent,
                frameUsable, terminal, hudEnabled, runtimeEnabled);
    }

    private static DirectTbtFrame routeFrame() {
        return new DirectTbtFrame(2, 2, 2, 100,
                "road", "cue", "road", null, null, null,
                DirectTbtFrame.AlertOverlay.inactive());
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
