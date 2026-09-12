package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ProjectionLifecycleSourceContractTest {
    private static String serviceSource() throws Exception {
        Path source = Path.of("src/main/java/com/bydhud/app/ClusterProjectionService.java");
        if (!Files.isRegularFile(source)) {
            source = Path.of("app/src/main/java/com/bydhud/app/ClusterProjectionService.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String controllerSource() throws Exception {
        Path source = Path.of("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.isRegularFile(source)) {
            source = Path.of("app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    @Test
    public void retainedResizeMustMatchRequestedGeometryBeforeMove() throws Exception {
        String source = serviceSource();
        String request = source.substring(
                source.indexOf("private void requestProjection("),
                source.indexOf("private void returnPackageToMain("));
        int resize = request.indexOf("boolean resizeSucceeded = resizeActiveProjection(");
        int initiallyHidden = request.indexOf(
                "projectionPlacementReady = preserveVisibleOwner;");
        int geometryGate = request.indexOf(
                "ProjectionLifecyclePolicy.requestedGeometrySucceeded(");
        int published = request.indexOf("projectionPlacementReady = true;", geometryGate);
        int failureTransition = request.indexOf("handleProjectionRequestResizeFailure(");
        int move = request.indexOf("movePackageToDisplay(", resize);
        assertTrue(initiallyHidden >= 0 && initiallyHidden < resize);
        assertTrue(geometryGate > resize);
        assertTrue(published > geometryGate && failureTransition > published);
        assertTrue(move > failureTransition);

        String displayQuery = source.substring(
                source.indexOf("private int currentProjectedDisplayId("),
                source.indexOf("private boolean recoverProjectedSurface("));
        assertTrue(displayQuery.contains("|| !projectionPlacementReady"));

        String reveal = source.substring(
                source.indexOf("private void confirmProjectionVisibleOnMain("),
                source.indexOf("private long currentProjectionToken("));
        assertTrue(reveal.contains("projectionPlacementReady,"));
    }

    @Test
    public void newDisplayPublishesOnlyAfterCurrentAllocationIsAccepted() throws Exception {
        String source = serviceSource();
        String allocation = source.substring(
                source.indexOf("private void createVirtualDisplayIfReady("),
                source.indexOf("private String applyDashboardProfileForWidgetBlocking("));
        int accepted = allocation.indexOf("accepted = projectionRequested");
        int published = allocation.indexOf("projectionPlacementReady = true;", accepted);
        int move = allocation.indexOf("movePackageToDisplay(", published);
        assertTrue(accepted >= 0 && published > accepted && move > published);
    }

    @Test
    public void outboundAndResizeRecoveryPassLivePredicatesToController() throws Exception {
        String source = serviceSource();
        String recovery = source.substring(
                source.indexOf("private void recoverProjectionAfterResizeFailure("),
                source.indexOf("private boolean isResizeRecoveryCurrent("));
        assertTrue(recovery.contains("recoveryCurrent"));
        assertTrue(recovery.contains("moveTaskToDisplayBlocking("));
        assertTrue(recovery.contains("\"cluster-projection profile-resize-recovery \" + reason,"));

        String outbound = source.substring(
                source.indexOf("private void movePackageToDisplay("),
                source.indexOf("private String staleMoveReason("));
        assertTrue(outbound.contains("moveTaskToDisplayBlocking("));
        assertTrue(outbound.contains("() -> staleMoveReason("));
    }

    @Test
    public void resizeRecoveryDoesNotReprojectAfterShutdownBegins() throws Exception {
        String source = serviceSource();
        String recovery = source.substring(
                source.indexOf("private void recoverProjectionAfterResizeFailure("),
                source.indexOf("private boolean isResizeRecoveryCurrent("));
        int intentGeneration = recovery.indexOf(
                "projectionGenerationForPackage(packageName)");
        int move = recovery.indexOf("moveTaskToDisplayBlocking(", intentGeneration);
        int confirmedMain = recovery.indexOf(
                "if (returned.taskId < 0 || returned.displayId != MAIN_DISPLAY_ID)", move);
        int shutdown = recovery.indexOf("if (HudPrefs.isUserShutdownActive(this))", confirmedMain);
        int clearIntent = recovery.indexOf("clearReturnedProjectionIntent(", shutdown);
        int retainIdle = recovery.indexOf("retainProjectionIdle(", clearIntent);
        int drainShutdown = recovery.indexOf(
                "releaseIdleProjectionForShutdownOnMain(", retainIdle);
        int shutdownReturn = recovery.indexOf("return;", drainShutdown);
        int reproject = recovery.indexOf("requestProjection(packageName, dashboardMode", shutdownReturn);
        assertTrue(intentGeneration >= 0 && move > intentGeneration);
        assertTrue(confirmedMain > move && shutdown > confirmedMain);
        assertTrue(clearIntent > shutdown && retainIdle > clearIntent);
        assertTrue(drainShutdown > retainIdle && shutdownReturn > drainShutdown);
        assertTrue(reproject > shutdownReturn);
    }

    @Test
    public void idleBlackWindowRendersInsideTheOwnedVirtualDisplay() throws Exception {
        String source = serviceSource();
        String physicalOverlay = source.substring(
                source.indexOf("private void ensureOverlay("),
                source.indexOf("private void createVirtualDisplayIfReady("));
        assertTrue(physicalOverlay.contains("root.addView(surfaceView"));
        assertTrue(!physicalOverlay.contains("root.addView(cover"));
        assertTrue(physicalOverlay.contains(
                "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"));

        String black = source.substring(
                source.indexOf("private String showBlackWindowForIdleOnMain("),
                source.indexOf("private void updateBlackWindowLayoutOnMain("));
        assertTrue(black.contains("createDisplayContext(display.getDisplay())"));
        assertTrue(black.contains(
                "WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null"));
        assertTrue(black.contains("blackView.setBackgroundColor(Color.BLACK)"));
        assertTrue(black.contains("blackView.setAlpha(1f)"));
        assertTrue(black.contains("manager.addView(blackView, params)"));

        String params = source.substring(
                source.indexOf("private static WindowManager.LayoutParams blackWindowParams("),
                source.indexOf("private void updateBlackWindowLayoutOnMain("));
        assertTrue(params.contains("WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION"));
        assertTrue(params.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"));
        assertTrue(params.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"));
        assertTrue(params.contains("PixelFormat.OPAQUE"));
        assertTrue(!params.contains("FLAG_DIM_BEHIND"));
    }

    @Test
    public void blackWindowIsHiddenAtTheFinalFencedMoveBoundary() throws Exception {
        String service = serviceSource();
        String prepare = service.substring(
                service.indexOf("static String prepareOutputForTaskMove("),
                service.indexOf("static void releaseIdleProjectionForShutdown("));
        assertTrue(prepare.contains("prepareOutputForTaskMoveBlocking("));

        String hide = service.substring(
                service.indexOf("private String hideBlackWindowForMoveOnMain("),
                service.indexOf("private void ensureOverlay("));
        assertTrue(hide.contains("ProjectionLifecyclePolicy.canPrepareOutputMove("));
        assertTrue(hide.contains("blackView.setVisibility(View.GONE)"));

        String controller = controllerSource();
        String move = controller.substring(
                controller.indexOf("synchronized NavAppDisplayState moveTaskToDisplayBlocking("),
                controller.indexOf("private NavAppDisplayState checkTaskId("));
        int finalFence = move.lastIndexOf("requestCurrent.getAsBoolean()",
                move.indexOf("runMutationCommandOnce("));
        int prepareOutput = move.indexOf("ClusterProjectionService.prepareOutputForTaskMove(", finalFence);
        int command = move.indexOf("runMutationCommandOnce(", prepareOutput);
        assertTrue(finalFence >= 0 && prepareOutput > finalFence && command > prepareOutput);
    }

    @Test
    public void confirmedReturnPublishesBlackBeforeClearingOwnerAndReportsPartialFailure()
            throws Exception {
        String source = serviceSource();
        String returned = source.substring(
                source.indexOf("private void returnPackageToMain("),
                source.indexOf("private boolean isReturnMoveCurrent("));
        int confirmed = returned.indexOf("returned.displayId != MAIN_DISPLAY_ID");
        int retained = returned.indexOf("retainProjectionIdle(", confirmed);
        int reported = returned.indexOf("recordProjectionOutputFailure(", retained);
        assertTrue(confirmed >= 0 && retained > confirmed && reported > retained);

        String idle = source.substring(
                source.indexOf("private String retainProjectionIdle("),
                source.indexOf("private String showBlackWindowForIdleOnMain("));
        int show = idle.indexOf("showBlackWindowForIdleOnMain(");
        int clearOwner = idle.indexOf("projectionOwnerToken = 0L", show);
        assertTrue(show >= 0 && clearOwner > show);
    }

    @Test
    public void fullReleaseRemovesBlackWindowBeforeVirtualDisplay() throws Exception {
        String source = serviceSource();
        String release = source.substring(
                source.indexOf("private void releaseProjection("),
                source.indexOf("private ProjectedSurface currentProjectedSurface("));
        int remove = release.indexOf("blackManager.removeViewImmediate(blackView)");
        int display = release.indexOf("display.release()", remove);
        assertTrue(remove >= 0 && display > remove);
    }

    @Test
    public void failedRebindRemovalKeepsTheOldWindowTrackedAndAbortsReplacement()
            throws Exception {
        String source = serviceSource();
        String show = source.substring(
                source.indexOf("private String showBlackWindowForIdleOnMain("),
                source.indexOf("private static WindowManager.LayoutParams blackWindowParams("));
        int remove = show.indexOf("removeBlackWindowOnMain(\"rebind-before-idle\")");
        int abort = show.indexOf("if (!removeFailure.isEmpty()) return removeFailure;", remove);
        int add = show.indexOf("manager.addView(blackView, params)", abort);
        assertTrue(remove >= 0 && abort > remove && add > abort);

        String transactionalRemove = source.substring(
                source.indexOf("private String removeBlackWindowOnMain("),
                source.indexOf("private void drainPendingShutdownRelease("));
        int detach = transactionalRemove.indexOf("manager.removeViewImmediate(blackView)");
        int detached = transactionalRemove.indexOf(
                "if (!blackView.isAttachedToWindow())", detach);
        int detachedClear = transactionalRemove.indexOf("blackWindowManager = null", detached);
        int preserveAttached = transactionalRemove.indexOf(
                "blackWindowView == blackView && blackWindowManager == manager && hidden",
                detachedClear);
        assertTrue(detach >= 0 && detached > detach && detachedClear > detached);
        assertTrue(preserveAttached > detachedClear);
    }

    @Test
    public void failedStaleAddCleanupHidesAndRetainsTheWindowWithoutOverwritingANewerOne()
            throws Exception {
        String source = serviceSource();
        String show = source.substring(
                source.indexOf("private String showBlackWindowForIdleOnMain("),
                source.indexOf("private static WindowManager.LayoutParams blackWindowParams("));
        int add = show.indexOf("manager.addView(blackView, params)");
        int hide = show.indexOf("blackView.setVisibility(View.GONE)", add);
        int remove = show.indexOf("manager.removeViewImmediate(blackView)", hide);
        int detached = show.indexOf("if (!blackView.isAttachedToWindow())", remove);
        int preserveCurrent = show.indexOf("if (blackWindowView == null)", detached);
        int trackView = show.indexOf("blackWindowView = blackView", preserveCurrent);
        assertTrue(add >= 0 && hide > add && remove > hide && detached > remove);
        assertTrue(preserveCurrent > remove && trackView > preserveCurrent);
    }
}
