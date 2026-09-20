package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public final class DashboardMoveContractTest {
    @Test
    public void dashboardModePreferenceMigratesTheLegacyFullscreenBoolean() throws Exception {
        assertEquals(HudPrefs.DASHBOARD_MODE_NONE,
                HudPrefs.normalizeDashboardScreenMode(-1));
        assertEquals(HudPrefs.DASHBOARD_MODE_PARTIAL,
                HudPrefs.normalizeDashboardScreenMode(HudPrefs.DASHBOARD_MODE_PARTIAL));
        assertEquals(HudPrefs.DASHBOARD_MODE_FULL,
                HudPrefs.normalizeDashboardScreenMode(3));

        java.nio.file.Path file = Paths.get(
                "app/src/main/java/com/bydhud/app/HudPrefs.java");
        if (!Files.exists(file)) {
            file = Paths.get("src/main/java/com/bydhud/app/HudPrefs.java");
        }
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(source.contains("if (!preferences.contains(KEY_DASHBOARD_SCREEN_MODE))"));
        assertTrue(source.contains("preferences.getBoolean(KEY_FULLSCREEN_DASHBOARD, true)"));
        assertTrue(source.contains("? DASHBOARD_MODE_FULL : DASHBOARD_MODE_NONE"));
        assertTrue(source.contains("putInt(KEY_DASHBOARD_SCREEN_MODE, migrated)"));
    }

    @Test
    public void dashboardFormatMethodDefaultsAndNormalizesPerMode() {
        assertEquals(HudPrefs.DASHBOARD_FORMAT_NATIVE,
                HudPrefs.normalizeDashboardFormatMethod(
                        HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_NATIVE));
        assertEquals(HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE,
                HudPrefs.normalizeDashboardFormatMethod(
                        HudPrefs.DASHBOARD_MODE_PARTIAL, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE));
        assertEquals(HudPrefs.DASHBOARD_FORMAT_NATIVE,
                HudPrefs.normalizeDashboardFormatMethod(HudPrefs.DASHBOARD_MODE_FULL, -1));
        assertEquals(HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE,
                HudPrefs.normalizeDashboardFormatMethod(HudPrefs.DASHBOARD_MODE_PARTIAL, 99));
    }

    @Test
    public void transferCapturesFormatMethodBeforeAdmissionAndWorkerLaunch() throws Exception {
        String source = source("NavAppDisplayController.java");
        String entry = between(source,
                "void moveIndependentDashboardApp(\n            String packageName,\n            boolean toDashboard,\n            int dashboardMode,\n            String reason)",
                "//toggles a configured app");
        assertTrue(entry.contains("HudPrefs.dashboardFormatMethod(context, dashboardMode)"));
        String launch = between(source,
                "private void moveIndependentDashboardApp(\n            String packageName,",
                "//runs explicit widget vehicle commands");
        int admitted = launch.indexOf("beginMove(normalized,");
        int worker = launch.indexOf("Thread worker = new Thread(", admitted);
        assertTrue(admitted >= 0 && worker > admitted);
        assertTrue(launch.substring(worker).contains(
                "normalizedDashboardMode,\n                        formatMethod,"));
        assertFalse(launch.substring(admitted).contains("dashboardFormatMethod(context"));
    }

    @Test
    public void projectionPersistsTheExplicitModeForStickyRecovery() throws Exception {
        java.nio.file.Path controller = Paths.get(
                "app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.exists(controller)) {
            controller = Paths.get("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        String controllerSource = new String(
                Files.readAllBytes(controller), StandardCharsets.UTF_8);
        assertTrue(controllerSource.contains("KEY_ACTIVE_MODE"));
        assertTrue(controllerSource.contains("persistedDashboardMode()"));
        assertTrue(controllerSource.contains(
                "persistDashboardProjection(normalized, normalizedMode, reason)"));

        java.nio.file.Path service = Paths.get(
                "app/src/main/java/com/bydhud/app/ClusterProjectionService.java");
        if (!Files.exists(service)) {
            service = Paths.get("src/main/java/com/bydhud/app/ClusterProjectionService.java");
        }
        String serviceSource = new String(
                Files.readAllBytes(service), StandardCharsets.UTF_8);
        assertTrue(serviceSource.contains("EXTRA_MODE"));
        assertTrue(serviceSource.contains(
                "requestProjection(packageName, dashboardMode, reason, taskState, transferToken)"));
        assertTrue(serviceSource.contains(
                "requestProjection(packageName, dashboardMode, \"restore:\""));
        int resizeStart = serviceSource.indexOf("private boolean resizeActiveProjection(");
        int resizeEnd = serviceSource.indexOf(
                "private void recoverProjectionAfterResizeFailure(", resizeStart);
        String resize = serviceSource.substring(resizeStart, resizeEnd);
        assertTrue(resize.contains("projectionGeometryValid = false;"));
        assertTrue(resize.contains("surfaceGeneration++;"));
        assertTrue(resize.contains("projectionGeometryValid = true;"));
        assertTrue(resize.contains("|| !projectionGeometryValid"));
        assertTrue(resize.contains("view.getHolder().setFixedSize(oldBufferWidth, oldBufferHeight)"));
        assertTrue(resize.contains("display.resize(oldBufferWidth, oldBufferHeight"));
        assertTrue(resize.contains("params.width = oldWidth;"));
        assertTrue(resize.contains("params.x = oldLeft;"));
        int requestStart = serviceSource.indexOf("private void requestProjection(");
        int requestEnd = serviceSource.indexOf("private void returnPackageToMain(", requestStart);
        String request = serviceSource.substring(requestStart, requestEnd);
        int resizeResult = request.indexOf(
                "boolean resizeSucceeded = resizeActiveProjection(");
        int initiallyHidden = request.indexOf(
                "projectionPlacementReady = preserveVisibleOwner;");
        int geometryGate = request.indexOf(
                "ProjectionLifecyclePolicy.requestedGeometrySucceeded(", resizeResult);
        int published = request.indexOf("projectionPlacementReady = true;", geometryGate);
        int failureTransition = request.indexOf(
                "handleProjectionRequestResizeFailure(", geometryGate);
        int moveAfterGate = request.indexOf("movePackageToDisplay(", failureTransition);
        assertTrue(initiallyHidden >= 0 && initiallyHidden < resizeResult);
        assertTrue(geometryGate > resizeResult);
        assertTrue(published > geometryGate && failureTransition > published);
        assertTrue(moveAfterGate > failureTransition);
    }

    @Test
    public void navigationSenderCannotResurrectDashboardProjection() throws Exception {
        java.nio.file.Path file = Paths.get(
                "app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        if (!Files.exists(file)) {
            file = Paths.get("src/main/java/com/bydhud/app/NavHudLiveSender.java");
        }
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertFalse(source.contains("DASHBOARD_WATCHDOG_INTERVAL_MS"));
        assertFalse(source.contains("lastDashboardWatchdogMs"));
        assertFalse(source.contains("maybeRepairDashboardProjection"));
        assertFalse(source.contains("\"watchdog:\""));
    }

    @Test
    public void autoContainerPolicyOnlySelectsExplicitTransitions() {
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_NATIVE, true));
        assertEquals(16, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_FULL,
                HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE, true));
        assertEquals(17, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_PARTIAL,
                HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_PARTIAL, HudPrefs.DASHBOARD_FORMAT_NATIVE, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_NONE, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE, false));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                false, HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE, true));
        assertTrue(NavAppDisplayController.isUserRequestedReturnForTest(
                "ui-independent-dashboard-explicit"));
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest("shutdown"));
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest("hud-switch-to-gmaps"));
        assertTrue(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME));
        assertTrue(NavAppDisplayController.isDirectNavigatorReplacement(
                GMapsDirectChannel.PACKAGE_NAME, "com.waze"));
        assertFalse(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", "com.waze"));
        assertFalse(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", "com.example.navigation"));
        assertTrue(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 0L));
    }

    @Test
    public void failedSuccessorReleaseRequiresBothTasksNoOwnerAndExactLease() {
        assertTrue(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                false, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, false, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, false,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, GMapsDirectChannel.PACKAGE_NAME, 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 8L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", "com.example.navigation",
                "com.waze", 7L, "com.waze", 7L));
    }

    @Test
    public void autoContainerAllowlistAcceptsOnlyDashboardModesAndRelease() {
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("id"));
        String command = LocalAdbBridge.autoContainerCommandForTest("auto_container", 16);
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(command));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                LocalAdbBridge.autoContainerCommandForTest("auto_container", 17)));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                LocalAdbBridge.autoContainerCommandForTest("AutoContainer", 18)));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "service call auto_container 2 i32 1000 i32 35 s16 '\"\"'"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                command + "; id"));
        assertTrue(LocalAdbBridge.isSuccessfulAutoContainerResponse(
                0, "Result: Parcel(00000000 00000001)"));
        assertFalse(LocalAdbBridge.isSuccessfulAutoContainerResponse(
                0, "Exception: unknown service"));
    }

    @Test
    public void secureSettingWriterQuotesDollarInnerClassAndKeepsFixedKeys() {
        String command = NavPermissionGrantPlan.secureSettingPutCommandForTest(
                NavPermissionGrantPlan.ACCESSIBILITY_SERVICES,
                ":other/com.example.Outer$Inner:com.bydhud.app/com.bydhud.app.NavAccessibilityService");
        assertEquals(
                "settings put secure enabled_accessibility_services "
                        + "':other/com.example.Outer$Inner:com.bydhud.app/com.bydhud.app.NavAccessibilityService'",
                command);
    }

    @Test
    public void purePlanPreservesExistingInnerClassServices() {
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                "com.bydhud.app",
                "com.example/com.example.Outer$Inner",
                "com.example/.Outer$Inner",
                false,
                true,
                false);
        assertTrue(plan.isValid());
        assertTrue(plan.accessibilityServicesValue.contains("com.example/.Outer$Inner"));
        assertTrue(plan.accessibilityServicesValue.contains("com.bydhud.app/com.bydhud.app.NavAccessibilityService"));
    }

    @Test
    public void dashboardMoveUsesTaskQueryAsAdbProofAndAcceptedNativeLayoutTransitions() throws Exception {
        java.nio.file.Path file = Paths.get(
                "app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.exists(file)) {
            file = Paths.get("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        String source = new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("preflightAuthorizedAdb("));
        String ordinaryMove = between(source,
                "private void moveIndependentDashboardAppBlocking(",
                "private String sendAutoContainerIfRequested(");
        assertTrue(ordinaryMove.contains("checkDisplay(packageName, toDashboard"));
        assertTrue(ordinaryMove.contains("applyDashboardLayout("));
        assertTrue(source.contains("dashboard_autocontainer_failed"));
        assertTrue(source.contains("sendAutoContainerIfRequested"));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
        assertFalse(source.contains("onDashboardReturnConfirmed"));
        assertFalse(source.contains("requestTbtAfterReturnIfRequested"));
        int returnRelease = source.indexOf("projectionReleased = waitForProjectionRelease(");
        int compositorRelease = source.indexOf(
                "releaseAutoContainerLeaseIfRequested(", returnRelease);
        assertTrue(returnRelease >= 0 && compositorRelease > returnRelease);
        assertTrue(source.contains("KEY_AUTOCONTAINER_LEASE_GENERATION"));
        assertTrue(source.contains("dashboard_autocontainer_lease_transferred"));
        assertTrue(source.contains("dashboard_autocontainer_lease_retained"));
        assertTrue(source.contains("releaseAutoContainerLeaseAfterFailedSuccessor"));
        assertTrue(source.contains("pendingAutoContainerLeaseTransferGeneration"));
        assertTrue(source.contains("ClusterProjectionService.hasProjectionOwner()"));
        assertTrue(source.contains("dashboard_autocontainer_lease_acquire_skipped_existing="));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
        int senderStart = source.indexOf("private String sendAutoContainerIfRequested");
        int senderEnd = source.indexOf("private String applyDashboardLayout", senderStart);
        assertTrue(senderStart >= 0 && senderEnd > senderStart);
        String sender = source.substring(senderStart, senderEnd);
        assertFalse(sender.contains("returnToMain"));
        assertTrue(sender.indexOf("if (DashboardLayoutPolicy.isAutoContainerCommand(value))")
                < sender.indexOf("LocalAdbBridge.runAutoContainer(context, value)"));
        assertTrue(sender.contains("existing AutoContainer lease retained"));

        String layout = between(source, "private String applyDashboardLayout(",
                "private int autoContainerOwnership(");
        int release = layout.indexOf("releasePersistedAutoContainerOwnership(");
        int releaseGate = layout.indexOf("if (!releaseFailure.isEmpty()) return releaseFailure;");
        int dispatch = layout.indexOf("StockMapProtocol30011.dispatch(", releaseGate);
        assertTrue(release >= 0 && releaseGate > release && dispatch > releaseGate);
        assertTrue(layout.contains("DashboardLayoutPolicy.isAutoContainerCommand(command)"));
        assertFalse(layout.contains("LEGACY_AUTO_CONTAINER_FULLSCREEN"));
        assertFalse(layout.contains("returnToMain"));

        int failedBranch = source.indexOf(
                "if (!isConfirmedProjectedDashboardDisplay(packageName, confirmed)");
        int failedReturn = source.indexOf("ClusterProjectionService.returnToMain(", failedBranch);
        int failedRelease = source.indexOf(
                "releaseAutoContainerLeaseAfterFailedSuccessor(", failedReturn);
        int failedRemember = source.indexOf(
                "independent dashboard projection not confirmed", failedRelease);
        assertTrue(failedReturn >= 0 && failedRelease > failedReturn && failedRemember > failedRelease);
        int failedHelper = source.indexOf(
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(", failedRelease);
        int noOwner = source.indexOf("waitForProjectionRelease(", failedHelper);
        int failedLeaseRelease = source.indexOf("releaseAutoContainerLease(", noOwner);
        assertTrue(failedHelper > failedRelease
                && noOwner > failedHelper
                && failedLeaseRelease > noOwner);

        int leaseReleaseStart = source.indexOf("private String releaseAutoContainerLease(");
        int leaseReleaseEnd = source.indexOf(
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(", leaseReleaseStart);
        String leaseRelease = source.substring(leaseReleaseStart, leaseReleaseEnd);
        int release18 = leaseRelease.indexOf(
                "DashboardLayoutPolicy.AUTOCONTAINER_RELEASE, true");
        int clearAfterRelease = leaseRelease.indexOf("clearAutoContainerLeaseIfExact(", release18);
        int retainAfterFailure = leaseRelease.indexOf(
                "dashboard_autocontainer_lease_retained", clearAfterRelease);
        assertTrue(release18 >= 0
                && clearAfterRelease > release18
                && retainAfterFailure > clearAfterRelease);
        assertEquals(clearAfterRelease, leaseRelease.lastIndexOf("clearAutoContainerLeaseIfExact("));

        int transferStart = source.indexOf("private void transferAutoContainerLeaseIfReplaced(");
        int transferEnd = source.indexOf("private void prepareAutoContainerLeaseTransfer(", transferStart);
        String transfer = source.substring(transferStart, transferEnd);
        assertTrue(transfer.contains("leaseGeneration != pendingAutoContainerLeaseTransferGeneration"));
        assertTrue(transfer.contains("putString(KEY_AUTOCONTAINER_LEASE_PACKAGE, packageName)"));
        assertTrue(transfer.contains("putLong(KEY_AUTOCONTAINER_LEASE_GENERATION, generation)"));
        assertTrue(transfer.contains("pendingAutoContainerLeaseTransferGeneration = 0L"));

        int reconcileStart = source.indexOf("private boolean reconcileConfirmedDashboardOwnership(");
        int reconcileEnd = source.indexOf("return true;", reconcileStart);
        assertTrue(source.indexOf("transferAutoContainerLeaseIfReplaced", reconcileStart) < reconcileEnd);
        assertEquals(-1, source.substring(reconcileStart, reconcileEnd)
                .indexOf("AUTO_CONTAINER_RELEASE"));
        int endMoveStart = source.indexOf("private void endMove(String packageName)");
        int endMoveEnd = source.indexOf("private long widgetProjectionGenerationForPackage", endMoveStart);
        String endMove = source.substring(endMoveStart, endMoveEnd);
        assertTrue(endMove.contains("pendingAutoContainerLeaseTransferFrom = \"\";"));
        assertTrue(endMove.contains("pendingAutoContainerLeaseTransferGeneration = 0L;"));
        assertTrue(endMove.contains("automatic_tbt_draining"));
    }

    @Test
    public void automaticTbtKeepsOneLatestRouteBoundRequestBehindTheMoveGate() throws Exception {
        String source = source("NavAppDisplayController.java");
        String request = between(source, "void requestAutomaticTbt(",
                "private void startAutomaticTbt(");
        assertTrue(request.contains("synchronized (lock)"));
        assertTrue(request.contains("previous = pendingAutomaticTbt"));
        assertTrue(request.contains("pendingAutomaticTbt = request"));
        assertTrue(request.contains("automatic TBT superseded by newer route"));

        String run = between(source, "private void runAutomaticTbt(",
                "private String dispatchAutomaticTbt(");
        int ownership = run.indexOf("autoContainerOwnership()");
        int release = run.indexOf("releasePersistedAutoContainerOwnership(", ownership);
        int typeOne = run.indexOf("DashboardLayoutPolicy.PROTOCOL_NATIVE", release);
        int typeTwo = run.indexOf("DashboardLayoutPolicy.PROTOCOL_TBT", typeOne);
        assertTrue(ownership >= 0 && release > ownership && typeOne > release && typeTwo > typeOne);
        assertTrue(run.contains("automatic TBT cancelled: route ended"));

        String toggle = between(source, "private void requestFreshToggle(",
                "private void moveIndependentDashboardApp(");
        int invalidate = toggle.indexOf("invalidatePendingAutomaticTbt(steering");
        assertTrue(invalidate >= 0 && invalidate < toggle.indexOf("beginMove("));
        assertTrue(toggle.contains("? \"explicit steering move\" : \"explicit ui move\""));
        assertTrue(source.contains("invalidatePendingAutomaticTbt(\"explicit display move\")"));
        assertTrue(source.contains("invalidatePendingAutomaticTbt(\"explicit widget command\")"));
        String endMove = between(source, "private void endMove(String packageName)",
                "private long widgetProjectionGenerationForPackage");
        assertTrue(endMove.contains("pendingAutomaticTbt = null"));
        assertTrue(endMove.contains("startAutomaticTbt(automaticToStart)"));
    }

    @Test
    public void actualAndLegacyAutoContainerProofAreCleanedOnlyAfterSuccessfulRelease() throws Exception {
        String source = source("NavAppDisplayController.java");
        String ownership = between(source, "private int autoContainerOwnership(",
                "private String releasePersistedAutoContainerOwnership(");
        assertTrue(ownership.contains("leaseGeneration > 0L"));
        assertTrue(ownership.contains("DashboardLayoutPolicy.ownershipKind("));
        assertFalse(ownership.contains("persistedDashboardMode()"));
        String release = between(source, "private String releasePersistedAutoContainerOwnership(",
                "private static String autoContainerStatus(");
        int sent = release.indexOf("DashboardLayoutPolicy.AUTOCONTAINER_RELEASE");
        int failed = release.indexOf("if (!failure.isEmpty()) return failure;", sent);
        int clear = release.indexOf("clearAutoContainerLeaseIfExact", failed);
        assertTrue(sent >= 0 && failed > sent && clear > failed);
        String sender = between(source, "private String sendAutoContainerIfRequested(",
                "private String applyDashboardLayout(");
        assertTrue(sender.contains("DashboardLayoutPolicy.isAutoContainerCommand(value)"));
        assertTrue(sender.contains("value == DashboardLayoutPolicy.AUTOCONTAINER_RELEASE"));
    }

    @Test
    public void taskMovePreparesBlackOutputAtTheLastCommandFence() throws Exception {
        String move = between(source("NavAppDisplayController.java"),
                "synchronized NavAppDisplayState moveTaskToDisplayBlocking(",
                "private boolean ensureWazeSurfaceOnDisplay(");
        int current = move.lastIndexOf("!requestCurrent.getAsBoolean()");
        int prepare = move.indexOf("ClusterProjectionService.prepareOutputForTaskMove(", current);
        int command = move.indexOf("TaskMoveSequencer.execute(", prepare);
        assertTrue(current >= 0 && prepare > current && command > prepare);
        assertTrue(move.contains("label + \" failed: \" + outputFailure"));
        String failure = between(source("NavAppDisplayController.java"),
                "void recordProjectionOutputFailure(",
                "private String sendAutoContainerIfRequested(");
        assertTrue(failure.contains("projection output failed: "));
        assertTrue(failure.contains("Unable to prepare dashboard black output"));
        assertTrue(failure.contains("Не вдалося підготувати чорне тло панелі приладів"));
        assertFalse(failure.contains("returnToMain"));
    }

    @Test
    public void moveStatusPublishesCachedStateAndScansOnceOnIdleTransition() throws Exception {
        String activity = source("MainActivity.java");
        String listener = between(activity,
                "NavAppDisplayController displayController = NavAppDisplayController.get(this);",
                "requestInitialUiStateRefresh(this, \"activity-create\")");
        assertTrue(listener.contains(
                "dashboardMoveInProgress = displayController.setListener(moveInProgress ->"));
        assertFalse(listener.contains("dashboardMoveInProgress = displayController.isMoveInProgress();"));
        assertTrue(listener.contains(
                "boolean moveFinished = dashboardMoveInProgress && !moveInProgress;"));
        assertTrue(listener.contains("if (moveInProgress || moveFinished)"));
        assertTrue(listener.contains("publishSharedUiStateChange();"));
        assertTrue(listener.contains("if (moveFinished)"));
        assertEquals(1, occurrences(listener, "scheduleAppScan();"));
        assertFalse(listener.contains("refreshControls();"));
        assertFalse(activity.contains("refreshAppsSoon("));

        String controller = source("NavAppDisplayController.java");
        assertTrue(controller.contains("boolean setListener(Listener listener)"));
        assertTrue(controller.contains("this.listener = listener;\n            return moveInProgress;"));
        assertTrue(controller.contains("void onNavAppDisplayChanged(boolean moveInProgress)"));
        assertTrue(controller.contains("moving = moveInProgress;"));
        assertTrue(controller.contains("callback.onNavAppDisplayChanged(moving);"));
    }

    @Test
    public void retainedProjectionRevealsOnlyAVisibleTaskWithTheObservedOwnerToken() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String outbound = between(controller,
                "CountDownLatch projectionCompleted = new CountDownLatch(1);",
                "} catch (SecurityException e)");
        int validated = outbound.indexOf("|| confirmed.taskId < 0 || !confirmed.visible");
        int captured = outbound.indexOf("projectedGenerationTokenForWidget(packageName)");
        int revealed = outbound.indexOf("ClusterProjectionService.confirmProjectionVisible(");
        assertTrue(validated >= 0 && captured > validated && revealed > captured);
        assertTrue(outbound.contains("packageName, confirmed.displayId, confirmedOwnerToken"));
        String waiting = between(controller,
                "private static NavAppDisplayState awaitTransferCompletion(",
                "private boolean isConfirmedProjectedDashboardDisplay(");
        assertTrue(waiting.contains("completed.await("));
        assertFalse(waiting.contains("checkDisplay("));
        assertFalse(waiting.contains("moveTaskToDisplayBlocking("));
    }

    private static String source(String fileName) throws Exception {
        java.nio.file.Path path = Paths.get(
                "app/src/main/java/com/bydhud/app/" + fileName);
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

    private static int occurrences(String source, String value) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }
}
