package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class DashboardWidgetCommandContractTest {
    @Test
    public void widgetResizeCommitSurvivesTransientGeometryInvalidation() {
        // Geometry is deliberately false while the ready surface is being resized;
        // owner and cancellation fences still allow the final commit.
        assertTrue(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                true, true));
        assertFalse(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                false, true));
        assertFalse(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                true, false));
    }

    @Test
    public void profilePathIsOwnerBoundAndRecoversOnlyAfterRollbackFailure() throws Exception {
        String service = source("ClusterProjectionService.java");
        assertTrue(service.contains("applyDashboardProfileForWidget"));
        assertTrue(service.contains("expectedProjectionGeneration"));
        assertTrue(service.contains("BooleanSupplier stillCurrent"));
        assertTrue(service.contains("resizeActiveProjectionForWidget"));
        assertTrue(service.contains("identity_updated=true"));
        String resize = between(service, "private boolean resizeActiveProjection(",
                "private void recoverProjectionAfterResizeFailure(");
        assertFalse(resize.contains("widgetProfile"));
        String widgetResize = between(service, "private String resizeActiveProjectionForWidget(",
                "private boolean isCurrentWidgetRequestLocked(");
        int unpublished = widgetResize.indexOf("projectionPlacementReady = false;");
        int resized = widgetResize.indexOf("view.getHolder().setFixedSize(");
        int restored = widgetResize.indexOf(
                "projectionPlacementReady = oldPlacementReady;", resized);
        int rollbackFailure = widgetResize.indexOf("profile_resize_rollback_failed widget=true");
        int recovery = widgetResize.indexOf(
                "recoverProjectionAfterResizeFailure(", rollbackFailure);
        assertTrue(unpublished >= 0 && resized > unpublished && restored > resized);
        assertTrue(rollbackFailure >= 0 && recovery > rollbackFailure);
    }

    @Test
    public void shutdownCancellationAndDeferredReturnShareTheMoveGate() throws Exception {
        String source = source("NavAppDisplayController.java");
        assertTrue(source.contains("void cancelWidgetModeForShutdown()"));
        assertTrue(source.contains("widgetOperationCancelled = true"));
        assertTrue(source.contains("pendingShutdownReturnPackage"));
        assertTrue(source.contains("dashboard_return_main_queued"));
        assertTrue(source.contains("returnActiveDashboardToMain(deferredReturnReason);"));
        String shutdownReturn = between(source, "void returnActiveDashboardToMain(",
                "private static boolean isShutdownReturnReason(");
        assertFalse(shutdownReturn.contains("isMoveInProgress()"));
        int locked = shutdownReturn.indexOf("synchronized (lock)");
        int gateCheck = shutdownReturn.indexOf("if (moveInProgress)", locked);
        int pending = shutdownReturn.indexOf("pendingShutdownReturnPackage = active", gateCheck);
        assertTrue(locked >= 0 && gateCheck > locked && pending > gateCheck);

        String command = between(source, "private String sendWidgetDashboardLayout(",
                "private boolean isWidgetOperationCurrent(");
        int sent = command.indexOf("LocalAdbBridge.runAutoContainer(");
        int bookkeeping = command.indexOf("acquireAutoContainerLeaseIfSucceeded(", sent);
        assertTrue(sent >= 0 && bookkeeping > sent);
        assertFalse(command.substring(sent).contains("widgetCancellationReason()"));
        assertTrue(command.contains("projectionGenerationForPackage(normalizedOwner) == leaseGeneration"));
    }

    private static String source(String fileName) throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
