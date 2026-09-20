package com.bydhud.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class WazeStartCoordinatorTest {
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
        WazeStartCoordinator.ProcessObservation old = new WazeStartCoordinator.ProcessObservation(
                WazeStartCoordinator.Presence.RUNNING, oldEpoch, 1_000L);
        state.invalidate();
        long nextEpoch = state.epoch();
        assertFalse(old.canAdmit(nextEpoch, 1_001L));
        assertFalse(state.observedProcess(nextEpoch, old.canAdmit(nextEpoch, 1_001L), old.startedElapsedMs));
        assertNull(state.acquire(1_001L));
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
        assertTrue(channel.contains("WazeStartAdmission.PROCESS.isCurrent(source.permit)"));
        String receiver = source("WazeRouteLifecycleReceiver.java");
        int accepted = receiver.indexOf("if (result.accepted)");
        assertTrue(receiver.indexOf("WazeStartCoordinator.acceptedRoute", accepted)
                < receiver.indexOf("dispatchAccepted(context", accepted));
        assertTrue(source("WazeRouteLifecycleV2Receiver.java").contains("FLAG_RECEIVER_REGISTERED_ONLY"));
        String coordinator = source("WazeStartCoordinator.java");
        assertTrue(coordinator.contains("pendingProcessCheck.epoch != epoch"));
        assertTrue(coordinator.contains("observation.canAdmit(epoch, SystemClock.elapsedRealtime())"));
        String accessibility = source("NavAccessibilityService.java");
        int event = accessibility.indexOf("public void onAccessibilityEvent(");
        assertTrue(accessibility.indexOf("\"waze-window-opened\"", event)
                < accessibility.indexOf("NavCaptureIngressPolicy.mode(packageName)", event));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("src/main/java/com/bydhud/app", file);
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
