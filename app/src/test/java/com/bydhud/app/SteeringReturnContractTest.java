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
    public void shutdownWaitsForAnUnconfirmedMoveAndThenResolvesItsNewOwner() throws Exception {
        String controller = source();
        String shutdown = between(controller, "void shutdownDashboardProjection(",
                "void returnActiveDashboardToMain(");
        int busy = shutdown.indexOf("if (moveInProgress)");
        int queued = shutdown.indexOf("pendingShutdownReturnReason = shutdownReason;");
        int returned = shutdown.indexOf("returnActiveDashboardToMain(shutdownReason);");
        assertTrue(busy >= 0 && queued > busy && returned > queued);
        assertFalse(shutdown.contains("active.isEmpty()"));
        String finished = between(controller, "void endMove(",
                "private long widgetProjectionGenerationForPackage(");
        assertTrue(finished.contains("if (!deferredReturnReason.isEmpty())"));
        assertTrue(finished.contains("returnActiveDashboardToMain(deferredReturnReason);"));
        assertFalse(finished.contains("moveIndependentDashboardApp( deferredReturnPackage"));
    }

    @Test
    public void idleShutdownReleasesResourcesButResumedRuntimeCancelsOldShutdown() throws Exception {
        String controller = source();
        String returning = between(controller, "void returnActiveDashboardToMain(",
                "private static boolean isShutdownReturnReason(");
        assertTrue(returning.indexOf("!HudPrefs.isUserShutdownActive(context)")
                < returning.indexOf("String active = activeDashboardPackage();"));
        assertTrue(returning.contains("releaseRetainedProjectionAfterShutdown(reason);"));
        String release = between(controller, "private void releaseRetainedProjectionAfterShutdown(",
                "private void moveIndependentDashboardAppBlocking(");
        assertTrue(release.contains("if (!projectionShutdownRequested || moveInProgress) return;"));
        assertTrue(release.indexOf("if (HudPrefs.isUserShutdownActive(context))")
                < release.indexOf("ClusterProjectionService.releaseIdleProjectionForShutdown(context, reason);"));
        assertFalse(release.contains(".release()"));
        Path activity = Path.of("src/main/java/com/bydhud/app/MainActivity.java");
        if (!Files.exists(activity)) activity = Path.of("app").resolve(activity);
        String ui = new String(Files.readAllBytes(activity), StandardCharsets.UTF_8);
        assertTrue(ui.contains("NavAppDisplayController.get(this).shutdownDashboardProjection(safeReason);"));
    }

    @Test
    public void steeringDispatchUsesSharedDirectionalReasonForAnySelectedApp() throws Exception {
        String steering = between(source(),
                "void requestSteeringToggle(",
                "private void moveIndependentDashboardApp(");

        assertTrue(steering.contains(
                "boolean toDashboard = observed == DashboardProjectionPolicy.ObservedDisplay.MAIN;"));
        assertTrue(steering.contains(
                "moveIndependentDashboardAppBlocking( normalized, toDashboard, dashboardMode, formatMethod, "
                        + "steeringMoveReason(toDashboard, reason),"));
        assertTrue(steering.indexOf("HudPrefs.dashboardFormatMethod(context, dashboardMode)")
                < steering.indexOf("if (!beginMove(normalized,"));
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
                "String releaseFailure = releaseAutoContainerLeaseIfRequested( "
                        + "packageName, returnGeneration, onMain, reason);"));
        assertTrue(returning.contains("autoContainerStatus(returnStatus, releaseFailure)"));
        assertFalse(returning.contains("requestTbt"));
        assertFalse(returning.contains("PROTOCOL_NATIVE"));
        assertFalse(returning.contains("PROTOCOL_TBT"));

        String requestedRelease = between(controller,
                "private String releaseAutoContainerLeaseIfRequested(",
                "private String releaseAutoContainerLease(");
        assertTrue(requestedRelease.contains(
                "if (!projectionReleased || !isUserRequestedReturnForTest(reason)) return \"\";"));
        assertTrue(requestedRelease.contains(
                "return releaseAutoContainerLease(packageName, generation, \"return-release\", reason);"));

        String release = between(controller,
                "private String releaseAutoContainerLease(",
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(");
        assertTrue(release.contains(
                "if (!normalized.equals(leasePackage) || generation <= 0L || leaseGeneration != generation) {"));
        assertTrue(release.contains("return \"\"; } String failure = sendAutoContainerIfRequested("));
        assertTrue(release.contains(
                "normalized, DashboardLayoutPolicy.AUTOCONTAINER_RELEASE, true, operation);"));
        assertTrue(release.contains(
                "if (failure == null || failure.isEmpty()) { if (clearAutoContainerLeaseIfExact( "
                        + "normalized, generation, operation + \":\" + safe(reason))) {"));
        String retained = release.substring(release.indexOf("} else {"));
        assertTrue(retained.contains("dashboard_autocontainer_lease_retained"));
        assertFalse(retained.contains("clearAutoContainerLease"));
        assertFalse(retained.contains(".remove("));
    }

    @Test
    public void sharedReturnHasNoAutomaticTbtCallback() throws Exception {
        String controller = source();
        assertFalse(controller.contains("requestTbtAfterReturnIfRequested"));
        assertFalse(controller.contains("onDashboardReturnConfirmed"));
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
                + ": returnPreviousDashboardApp( packageName, layoutCommand, reason, requestCurrent);"));
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
        int pending = prior.indexOf("prepareAutoContainerLeaseTransfer( previous, nextPackageName, "
                + "nextLayoutCommand);");
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

    @Test
    public void shutdownReservationQueuesAtomicallyAndWorkerKeepsItsRuntimeToken() throws Exception {
        String controller = source();
        String reserve = between(controller, "private boolean reserveMove(String shutdownReason)",
                "private void persistDashboardProjection(");
        int locked = reserve.indexOf("synchronized (lock)");
        int busy = reserve.indexOf("if (moveInProgress)");
        int queue = reserve.indexOf("pendingShutdownReturnReason = shutdownReason;");
        int decline = reserve.indexOf("return false;");
        assertTrue(locked >= 0 && busy > locked && queue > busy && decline > queue);
        String worker = between(controller, "private void moveIndependentDashboardApp(",
                "void requestWidgetMode(");
        assertTrue(worker.indexOf("UserRuntimeSession.PROCESS.shutdownToken()")
                < worker.indexOf("Thread worker ="));
        assertTrue(worker.contains("reason, completion, null, shutdownToken)"));
        String returning = between(controller, "if (!toDashboard)",
                "boolean alreadyProjected =");
        assertTrue(returning.contains("\"independent-dashboard return-main \" + safe(reason), shutdownToken"));
        assertTrue(returning.contains("() -> isShutdownReturnCurrent(shutdownToken)"));
        assertTrue(returning.contains(
                "if (!isShutdownReturnCurrent(shutdownToken)) return; "
                        + "String releaseFailure = releaseAutoContainerLeaseIfRequested("));
    }

    @Test
    public void returnIntentAndCommandKeepExactProjectionAndShutdownIdentity() throws Exception {
        String service = source("ClusterProjectionService.java");
        String intent = between(service,
                "static void returnToMain( Context context, String packageName, String reason, long shutdownToken)",
                "static void applyDashboardProfile(");
        assertTrue(intent.contains("EXTRA_RETURN_GENERATION, service.projectionGeneration"));
        assertTrue(intent.contains("EXTRA_RETURN_OWNER_TOKEN, service.projectionOwnerToken"));
        assertTrue(intent.contains("EXTRA_SHUTDOWN_TOKEN, shutdownToken"));
        String returning = between(service, "private void returnPackageToMain(",
                "private boolean isReturnMoveCurrent(");
        assertTrue(returning.contains("BooleanSupplier requestCurrent = () -> isReturnMoveCurrent("));
        assertTrue(returning.contains("\"cluster-projection return-main \" + reason, requestCurrent"));
        assertTrue(returning.contains("if (!isReturnOwnerCurrent(targetPackage, returnGeneration, returnOwnerToken) || !shouldRetainAfterReturn("));
        String command = between(source(),
                "synchronized NavAppDisplayState moveTaskToDisplayBlocking( String packageName, int targetDisplay, String reason, BooleanSupplier requestCurrent)",
                "private boolean ensureWazeSurfaceOnDisplay(");
        int issue = command.indexOf("LocalAdbBridge.ShellResult move = runCommand(");
        assertTrue(command.lastIndexOf("!requestCurrent.getAsBoolean()", issue)
                > command.indexOf("if (current.displayId == targetDisplay)"));
    }

    @Test
    public void deferredShutdownReleaseSurvivesActiveReturnAndDrainsAtIdle() throws Exception {
        String service = source("ClusterProjectionService.java");
        String release = between(service, "private void releaseIdleProjectionForShutdownOnMain(",
                "private void releaseProjection(");
        assertTrue(release.indexOf("pendingShutdownReleaseToken = shutdownToken;")
                < release.indexOf("if (!ProjectionLifecyclePolicy.canReleaseIdleForShutdown("));
        assertTrue(release.contains("isShutdownReturnCurrent(shutdownToken)"));
        assertTrue(release.contains("shutdown_release_deferred"));
        String idle = between(service, "private String retainProjectionIdle(",
                "private void releaseIdleProjectionForShutdownOnMain(");
        assertTrue(idle.contains("drainPendingShutdownRelease(reason);"));
        assertTrue(idle.contains("releaseIdleProjectionForShutdownOnMain(pendingShutdownReleaseToken, reason)"));
    }

    @Test
    public void admittedReturnReconcilesExactOwnerAfterResumeWithoutShutdownEffects() throws Exception {
        String service = source("ClusterProjectionService.java");
        String returning = between(service, "private void returnPackageToMain(",
                "private boolean isReturnMoveCurrent(");
        String completion = returning.substring(returning.indexOf("mainHandler.post(() ->"));
        assertTrue(completion.contains("isReturnOwnerCurrent(targetPackage, returnGeneration, returnOwnerToken)"));
        assertFalse(completion.contains("requestCurrent.getAsBoolean()"));
        assertFalse(completion.contains("isShutdownReturnCurrent"));
        assertTrue(completion.contains("controller.clearReturnedProjectionIntent(targetPackage, intentGeneration, reason);"));
        assertTrue(completion.contains("retainProjectionIdle("));
        assertFalse(completion.contains("releaseProjection("));
        String identity = between(service, "private boolean isReturnOwnerCurrent(",
                "private void ensureOverlay(");
        assertTrue(identity.contains("projectionGeneration == expectedGeneration"));
        assertTrue(identity.contains("projectionOwnerToken == expectedOwnerToken"));
        String cleanup = between(source(), "void clearReturnedProjectionIntent(",
                "private void clearDashboardProjection(");
        assertTrue(cleanup.contains("projectionGenerationForPackage(packageName) != expectedGeneration"));
        assertTrue(cleanup.contains("!packageName.equals(persistedDashboardPackage())"));
        assertFalse(cleanup.contains("releaseAutoContainer"));
        assertFalse(cleanup.contains("requestTbt"));
    }

    @Test
    public void coldShutdownCanStopOnlyAServiceWithoutAllocationOrOwnership() throws Exception {
        String service = source("ClusterProjectionService.java");
        String cold = between(service, "private boolean stopColdIdleServiceAfterShutdown()",
                "private void releaseProjection(");
        assertTrue(service.contains("if (stopColdIdleServiceAfterShutdown()) return START_NOT_STICKY;"));
        assertTrue(cold.contains("!HudPrefs.isUserShutdownActive(this)"));
        assertTrue(cold.contains("UserRuntimeSession.PROCESS.shutdownToken() != 0L"));
        assertTrue(cold.contains("hasAllocatedResourcesLocked()"));
        assertTrue(cold.contains("ProjectionLifecyclePolicy.canReleaseIdleForShutdown("));
        assertTrue(cold.contains("stopForegroundCompat(); stopSelf();"));
        assertFalse(cold.contains("releaseProjection("));
    }

    private static String source() throws Exception {
        return source("NavAppDisplayController.java");
    }

    private static String source(String fileName) throws Exception {
        Path path = Path.of("src/main/java/com/bydhud/app/" + fileName);
        if (!Files.exists(path)) {
            path = Path.of("app/src/main/java/com/bydhud/app/" + fileName);
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
