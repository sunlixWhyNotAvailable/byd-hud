package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Pure reason checks plus source wiring contracts, not vehicle/runtime proof. */
public final class SteeringReturnContractTest {
    @Test
    public void steeringReasonMarksOnlyTheReturnDirection() {
        String reason = "keycode=305";
        String outbound = NavAppDisplayController.steeringMoveReason(true, reason);
        String returning = NavAppDisplayController.steeringMoveReason(false, reason);

        assertEquals("steering-key keycode=305", outbound);
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest(outbound));
        assertEquals("steering-key user-return keycode=305", returning);
        assertTrue(NavAppDisplayController.isUserRequestedReturnForTest(returning));
    }

    @Test
    public void nullSteeringReasonPreservesTheDirection() {
        String outbound = NavAppDisplayController.steeringMoveReason(true, null);
        String returning = NavAppDisplayController.steeringMoveReason(false, null);

        assertEquals("steering-key ", outbound);
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest(outbound));
        assertEquals("steering-key user-return ", returning);
        assertTrue(NavAppDisplayController.isUserRequestedReturnForTest(returning));
    }

    @Test
    public void implicitShutdownAndReplacementReasonsRemainNonUserReturns() {
        for (String reason : new String[] {
                null, "", "shutdown", "user-shutdown", "runtime-stop",
                "restore:boot", "hud-switch-to-gmaps", "replaced-by-com.waze",
                "replaced-by-" + GMapsDirectChannel.PACKAGE_NAME,
                "dashboard-confirmation-failed:keycode=305", "steering-key keycode=305"
        }) {
            assertFalse(String.valueOf(reason),
                    NavAppDisplayController.isUserRequestedReturnForTest(reason));
        }
    }

    @Test
    public void steeringDispatchUsesSharedDirectionalReasonForAnySelectedApp() throws Exception {
        String steering = between(source(),
                "void requestSteeringToggle(",
                "private void moveIndependentDashboardApp(");

        assertTrue(steering.contains(
                "boolean toDashboard = observed == DashboardProjectionPolicy.ObservedDisplay.MAIN;"));
        assertTrue(steering.contains(
                "moveIndependentDashboardAppBlocking( normalized, toDashboard, dashboardMode, "
                        + "steeringMoveReason(toDashboard, reason),"));
        assertFalse(steering.contains("\"steering-key \" +"));
        assertFalse(steering.contains("com.waze"));
        assertFalse(steering.contains("GMapsDirectChannel"));
        assertFalse(steering.contains("runAutoContainer("));
    }

    @Test
    public void sharedReturnKeepsProjectionConfirmationAndExactLeaseGuards() throws Exception {
        String controller = source();
        String returning = between(controller, "if (!toDashboard) {", "boolean alreadyProjected");
        assertTrue(returning.contains(
                "long returnGeneration = projectionGenerationForPackage(packageName);"));
        assertTrue(returning.contains(
                "boolean onMain = confirmed.taskId >= 0 && confirmed.displayId == MAIN_DISPLAY_ID;"));
        assertTrue(returning.contains("boolean projectionReleased = false; if (onMain) {"));
        assertTrue(returning.contains(
                "projectionReleased = waitForProjectionRelease( packageName, \"independent-return-release\");"));
        assertTrue(returning.contains(
                "releaseAutoContainerLeaseIfRequested( packageName, returnGeneration, projectionReleased, reason); "
                        + "requestTbtAfterReturnIfRequested(packageName, projectionReleased, reason);"));

        String requestedRelease = between(controller,
                "private void releaseAutoContainerLeaseIfRequested(",
                "private void releaseAutoContainerLease(");
        assertTrue(requestedRelease.contains(
                "if (!projectionReleased || !isUserRequestedReturnForTest(reason)) return;"));
        assertTrue(requestedRelease.contains(
                "releaseAutoContainerLease(packageName, generation, \"return-release\", reason);"));

        String release = between(controller,
                "private void releaseAutoContainerLease(",
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(");
        assertTrue(release.contains(
                "if (!normalized.equals(leasePackage) || generation <= 0L || leaseGeneration != generation) {"));
        assertTrue(release.contains("return; } String failure = sendAutoContainerIfRequested("));
        assertTrue(release.contains("normalized, AUTO_CONTAINER_RELEASE, true, operation);"));
        assertTrue(release.contains(
                "if (failure == null || failure.isEmpty()) { if (clearAutoContainerLeaseIfExact( "
                        + "normalized, generation, operation + \":\" + safe(reason))) {"));
        String retained = release.substring(release.indexOf("} else {"));
        assertTrue(retained.contains("dashboard_autocontainer_lease_retained"));
        assertFalse(retained.contains("clearAutoContainerLease"));
        assertFalse(retained.contains(".remove("));
    }

    @Test
    public void sharedNavigatorNotificationKeepsExplicitReturnAndPackageGuards() throws Exception {
        String notification = between(source(),
                "private void requestTbtAfterReturnIfRequested(",
                "private String returnPreviousDashboardApp(");
        assertTrue(notification.contains(
                "if (!onMain || !isUserRequestedReturnForTest(reason)) return;"));
        assertTrue(notification.contains(
                "if (!\"com.waze\".equals(normalized) "
                        + "&& !GMapsDirectChannel.PACKAGE_NAME.equals(normalized)) return;"));
        assertTrue(notification.contains(
                "NavHudLiveSender.get(context).onDashboardReturnConfirmed(normalized, reason);"));
    }

    @Test
    public void cancelledSuccessorRetiresOnlyThePreviousProjectionBeforeReleasingTheGate() throws Exception {
        String controller = source();
        String move = between(controller, "private void moveIndependentDashboardAppBlocking(",
                "private String completionErrorForState(");
        String beforeReturn = between(move,
                "if (requestCurrent != null && (!requestCurrent.getAsBoolean()",
                "if (current.taskId < 0)");
        assertTrue(beforeReturn.contains("return;"));
        assertFalse(beforeReturn.contains("returnToMain("));
        String replacement = between(move, "boolean alreadyProjected =", "if (alreadyProjected) {");
        assertTrue(replacement.contains("String returnedPrevious = alreadyProjected ? \"\" "
                + ": returnPreviousDashboardApp(packageName, reason, requestCurrent);"));
        assertTrue(replacement.contains("if (returnedPrevious == null) {"));
        String failedReturn = between(replacement, "if (returnedPrevious == null) {",
                "if (requestCurrent != null && !requestCurrent.getAsBoolean())");
        assertTrue(failedReturn.contains("return;"));
        assertFalse(failedReturn.contains("returnToMain("));
        String cancelled = replacement.substring(replacement.indexOf(
                "if (requestCurrent != null && !requestCurrent.getAsBoolean())"));
        assertTrue(cancelled.contains("if (!returnedPrevious.isEmpty()) {"));
        int teardown = cancelled.indexOf(
                "ClusterProjectionService.returnToMain(context, returnedPrevious, cancelledReason);");
        int released = cancelled.indexOf(
                "if (waitForProjectionRelease(returnedPrevious, cancelledReason)) {");
        int lease = cancelled.indexOf(
                "releaseAutoContainerLeaseAfterFailedSuccessor(packageName, cancelledReason);");
        int aborted = cancelled.indexOf("steering transfer blocked: request changed");
        assertTrue(teardown >= 0 && released > teardown && lease > released && aborted > lease);
        assertTrue(cancelled.contains("return;"));
        assertFalse(cancelled.contains("startProjection("));
        assertFalse(cancelled.contains("returnToMain(context, packageName"));
        assertFalse(cancelled.contains("isDirectNavigatorReplacement"));
        assertTrue(move.indexOf("steering-successor-cancelled:") < move.indexOf("endMove(packageName);"));
    }

    @Test
    public void cancellationCleanupUsesConfirmedPriorReturnAndUnchangedExactLeaseGuards() throws Exception {
        String controller = source();
        String prior = between(controller, "private String returnPreviousDashboardApp(",
                "synchronized NavAppDisplayState moveTaskToDisplayBlocking(");
        assertTrue(prior.contains("if (previous.isEmpty() || previous.equals(nextPackageName)) { return \"\"; }"));
        int confirmed = prior.indexOf("if (onMain) {");
        int pending = prior.indexOf("prepareAutoContainerLeaseTransfer(previous, nextPackageName);");
        int surface = prior.indexOf("ensureWazeSurfaceOnDisplay(");
        int returned = prior.indexOf("return previous;");
        assertTrue(confirmed >= 0 && pending > confirmed && surface > pending && returned > surface);
        assertTrue(prior.contains("clearDashboardProjection(\"return-previous-dashboard:\" + safe(reason)); return previous;"));

        String cleanup = between(controller, "private void releaseAutoContainerLeaseAfterFailedSuccessor(",
                "private boolean isOnMainDisplay(");
        assertTrue(cleanup.contains("!previousPackage.equals(leasePackage)"));
        assertTrue(cleanup.contains("leaseGeneration != pendingGeneration"));
        assertTrue(cleanup.contains("!isDirectNavigatorReplacement(previousPackage, successor)"));
        assertTrue(cleanup.contains("previousOnMain, successorOnMain, noProjectionOwner"));
        assertTrue(cleanup.indexOf("waitForProjectionRelease(") < cleanup.indexOf("releaseAutoContainerLease("));
        assertTrue(cleanup.contains("previousPackage, pendingGeneration, \"failed-successor-release\", reason"));
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.exists(path)) {
            path = Path.of("app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue("missing start marker " + start, from >= 0);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
