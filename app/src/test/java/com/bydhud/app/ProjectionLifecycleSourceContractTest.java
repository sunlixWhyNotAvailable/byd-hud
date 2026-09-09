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
                source.indexOf("private void setProjectionCoverVisible("));
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
        int retainIdle = recovery.indexOf("retainProjectionIdle(shutdownReason)", clearIntent);
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
}
