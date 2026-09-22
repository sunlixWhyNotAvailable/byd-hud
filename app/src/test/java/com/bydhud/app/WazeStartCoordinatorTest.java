package com.bydhud.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class WazeStartCoordinatorTest {
    @Test public void onlyAnActualActiveOrFocusedWazeApplicationWindowQualifies() {
        int app = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION;
        int system = android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM;
        assertTrue(NavAccessibilityService.isWazeApplicationWindow(app, true, false, "com.waze", true));
        assertTrue(NavAccessibilityService.isWazeApplicationWindow(app, false, true, "com.waze", true));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(system, true, true, "com.waze", true));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(app, true, true, "com.android.systemui", true));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(app, true, true, "com.bydhud.app", true));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(app, true, true, null, true));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(app, true, true, "com.waze", false));
        assertFalse(NavAccessibilityService.isWazeApplicationWindow(app, false, false, "com.waze", true));
    }

    @Test public void pidOnlyRejectsInitialAndRetryAdmissionWithoutMoreProcessReads() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, false);
        for (long now : new long[]{1_000L, 2_000L, 10_000L, 3_600_000L}) {
            state.observedProcess(state.epoch(), true, now);
            for (boolean retry : new boolean[]{false, true}) {
                assertNull(WazeStartCoordinator.beforeBind(state, retry, () -> now,
                        epoch -> { throw new AssertionError("No PID read without window proof"); },
                        () -> { throw new AssertionError("No process read to refresh"); }));
            }
        }
    }

    @Test public void validWindowAndProcessAdmitButExpiredWindowSkipsRetryRead() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, false);
        state.observedWindow(state.epoch(), 1_000L);
        state.observedProcess(state.epoch(), true, 1_000L);
        assertEquals(WazeStartAdmission.Source.LEGACY_WINDOW,
                WazeStartCoordinator.beforeBind(state, false, () -> 1_001L,
                        epoch -> { throw new AssertionError("Initial proof already checked"); },
                        () -> {}).source);
        assertEquals(WazeStartAdmission.Source.LEGACY_WINDOW,
                WazeStartCoordinator.beforeBind(state, true, () -> 6_000L,
                        epoch -> new WazeStartCoordinator.ProcessObservation(
                                WazeStartCoordinator.Presence.RUNNING, epoch, 6_000L), () -> {}).source);
        assertNull(WazeStartCoordinator.beforeBind(state, true, () -> 6_001L,
                epoch -> { throw new AssertionError("Retry must not renew an expired window"); }, () -> {}));
    }

    @Test public void absenceSleepAndInvalidationDuringRetryRejectTheBind() {
        for (int failure = 0; failure < 3; failure++) {
            WazeStartAdmission state = new WazeStartAdmission();
            state.updateRuntime(true, 10L, false);
            state.observedWindow(state.epoch(), 1_000L);
            state.observedProcess(state.epoch(), true, 1_000L);
            long[] now = {1_001L};
            int scenario = failure;
            assertNull(WazeStartCoordinator.beforeBind(state, true, () -> now[0], epoch -> {
                if (scenario == 1) now[0] = 3_600_000L;
                if (scenario == 2) {
                    state.invalidate();
                    state.observedWindow(state.epoch(), now[0]);
                    state.observedProcess(state.epoch(), true, now[0]);
                }
                return new WazeStartCoordinator.ProcessObservation(scenario == 0
                        ? WazeStartCoordinator.Presence.ABSENT : WazeStartCoordinator.Presence.RUNNING,
                        epoch, 1_001L);
            }, () -> {}));
        }
    }

    @Test public void establishedRouteRecoveryDoesNotDependOnWindowOrPidRechecks() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, false);
        state.observedWindow(state.epoch(), 1_000L);
        state.observedProcess(state.epoch(), true, 1_000L);
        state.established(state.acquire(1_000L), true);
        state.updateRuntime(true, 10L, false); // HUD OFF, TBT still consumes the active route.
        assertEquals(WazeStartAdmission.Source.ACTIVE_RECOVERY,
                WazeStartCoordinator.beforeBind(state, true, () -> 3_600_000L,
                        epoch -> { throw new AssertionError("Active-route recovery must not probe"); },
                        () -> {}).source);
    }

    @Test public void processReadbackSeparatesAbsenceFromFailure() {
        assertEquals(WazeStartCoordinator.Presence.RUNNING,
                WazeStartCoordinator.parsePresence(0, "7253\n7315\n"));
        assertEquals(WazeStartCoordinator.Presence.ABSENT,
                WazeStartCoordinator.parsePresence(1, ""));
        assertEquals(WazeStartCoordinator.Presence.UNAVAILABLE,
                WazeStartCoordinator.parsePresence(126, ""));
        assertEquals(WazeStartCoordinator.Presence.UNAVAILABLE,
                WazeStartCoordinator.parsePresence(0, "Permission denied"));
        assertEquals(WazeStartCoordinator.Presence.UNAVAILABLE,
                WazeStartCoordinator.parsePresence(1, "7253"));
        assertEquals(WazeStartCoordinator.Presence.UNAVAILABLE,
                WazeStartCoordinator.parsePresence(0, "0"));
    }

    @Test public void runtimeAllowlistAddsOnlyTheFixedWazeRead() {
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("pidof com.waze"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("pidof other.package"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("pidof com.waze; am start com.waze"));
    }

    @Test public void parallelLaunchesCoalesceButLaunchDuringOldCheckIsNotLost() {
        WazeStartCoordinator.ReconcileGate gate = new WazeStartCoordinator.ReconcileGate();
        assertTrue(gate.request("boot"));
        assertFalse(gate.request("accessibility-connected"));
        assertEquals("accessibility-connected", gate.next());
        assertFalse(gate.request("waze-opened-during-check"));
        assertEquals("waze-opened-during-check", gate.next());
        assertNull(gate.next());
        assertTrue(gate.request("later-launch"));
        assertEquals("later-launch", gate.next());
        assertNull(gate.next());
    }

    @Test public void delayedSuccessfulReadIsNotFreshenedWhenItsConsumerResumes() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, false);
        long epoch = state.epoch();
        state.observedWindow(epoch, 1_000L);
        WazeStartCoordinator.ProcessObservation read = new WazeStartCoordinator.ProcessObservation(
                WazeStartCoordinator.Presence.RUNNING, epoch, 1_000L);
        assertTrue(read.canAdmit(epoch, 6_000L));
        assertFalse(read.canAdmit(epoch, 6_001L));
        assertFalse(read.canAdmit(epoch, 999L));
        long resumed = 3_600_000L;
        assertFalse(state.observedProcess(epoch, read.canAdmit(epoch, resumed), read.startedElapsedMs));
        assertNull(state.acquire(resumed));
    }

    @Test public void queryFromBeforeInvalidationCannotBeReusedByANewEpoch() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, false);
        long oldEpoch = state.epoch();
        state.observedWindow(oldEpoch, 1_000L);
        WazeStartCoordinator.ProcessObservation old = new WazeStartCoordinator.ProcessObservation(
                WazeStartCoordinator.Presence.RUNNING, oldEpoch, 1_000L);
        state.invalidate();
        long nextEpoch = state.epoch();
        assertFalse(old.canAdmit(nextEpoch, 1_001L));
        assertFalse(state.observedProcess(nextEpoch, old.canAdmit(nextEpoch, 1_001L), old.startedElapsedMs));
        assertNull(state.acquire(1_001L));
        state.observedWindow(nextEpoch, 1_002L);
        WazeStartCoordinator.ProcessObservation current = new WazeStartCoordinator.ProcessObservation(
                WazeStartCoordinator.Presence.RUNNING, nextEpoch, 1_002L);
        assertTrue(state.observedProcess(nextEpoch, current.canAdmit(nextEpoch, 1_002L), current.startedElapsedMs));
        assertNotNull(state.acquire(1_002L));
    }

    @Test public void entrypointsUseAdmissionAndKeepSafeStateQueryAndEventObservation() throws Exception {
        String sender = source("NavHudLiveSender.java");
        assertFalse(sender.contains("return !bridgeSupported || routeActive"));
        assertTrue(sender.contains("startWazeWhenAdmitted(\"active-restart:"));
        assertTrue(sender.contains("startWazeWhenAdmitted(reason, false)"));
        String channel = source("WazeDirectChannel.java");
        int connect = channel.indexOf("private void connectWaze(");
        int bind = channel.indexOf("context.bindService(", connect);
        assertTrue(bind > connect);
        assertTrue(channel.substring(connect, bind).contains("WazeStartCoordinator.beforeBind"));
        assertTrue(channel.substring(connect, bind).contains("if (connectionPermit == null)"));
        assertTrue(channel.substring(connect, bind).contains("waitForAdmission(startReason);\n            return;"));
        assertTrue(channel.contains("WazeStartAdmission.PROCESS.isCurrent(source.permit)"));
        String receiver = source("WazeRouteLifecycleReceiver.java");
        int accepted = receiver.indexOf("if (result.accepted)");
        assertTrue(receiver.indexOf("WazeStartCoordinator.acceptedRoute", accepted)
                < receiver.indexOf("dispatchAccepted(context", accepted));
        assertTrue(source("WazeRouteLifecycleV2Receiver.java").contains("FLAG_RECEIVER_REGISTERED_ONLY"));
        String coordinator = source("WazeStartCoordinator.java");
        assertTrue(coordinator.contains("pendingProcessCheck.epoch != epoch"));
        assertTrue(coordinator.contains("observation.canAdmit(epoch, SystemClock.elapsedRealtime())"));
        int reconcile = coordinator.indexOf("static void requestLegacyReconcile(");
        int windowRead = coordinator.indexOf("observeWazeApplicationWindow()", reconcile);
        int windowGuard = coordinator.indexOf("hasFreshLegacyWindow(", windowRead);
        int processRead = coordinator.indexOf("readProcess(appContext, epoch)", windowGuard);
        assertTrue(windowRead > reconcile && windowGuard > windowRead && processRead > windowGuard);
        assertTrue(coordinator.substring(windowGuard, processRead).contains("continue;"));
        assertFalse(coordinator.contains("NavAppTaskScanner"));
        String accessibility = source("NavAccessibilityService.java");
        assertTrue(accessibility.contains("windows = service.getWindows()"));
        assertTrue(accessibility.contains("activeService == service"));
        assertTrue(accessibility.contains("service.windowObservationGeneration.get() == generation"));
        assertTrue(accessibility.contains("root.getPackageName(), root.isVisibleToUser()"));
        int event = accessibility.indexOf("public void onAccessibilityEvent(");
        assertTrue(accessibility.indexOf("\"waze-window-opened\"", event)
                < accessibility.indexOf("NavCaptureIngressPolicy.mode(packageName)", event));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("src/main/java/com/bydhud/app", file);
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
