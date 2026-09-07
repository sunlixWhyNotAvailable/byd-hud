package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class NavFallbackLifecycleBoundaryTest {
    @Before
    public void resetProcessLifecycle() {
        HudRuntimeState.clearServicePresent(null, "test-reset");
        HudRuntimeService.clearStartRequestForTest();
    }

    @After
    public void restoreProcessLifecycle() {
        HudRuntimeState.clearServicePresent(null, "test-restore");
        HudRuntimeService.clearStartRequestForTest();
    }

    @Test
    public void packageReplaceResetIsFencedWithoutProcessKill() throws IOException {
        String supervisor = source("HudRuntimeSupervisor.java");
        assertFalse(supervisor.contains("killProcess"));
        assertTrue(supervisor.contains("senderTeardownComplete"));
        assertTrue(supervisor.contains("package_replace_restart_scheduled"));
        assertTrue(supervisor.contains("NavHudLiveSender.get(appContext).stop"));
        String sender = source("NavHudLiveSender.java");
        assertTrue(sender.contains("hardStopDirectNavigatorsForPackageReplace"));
        assertTrue(sender.contains("wazeDirectChannel.hardStop(\"package-replace-hard-reset\")"));
        assertTrue(sender.contains("boolean forcedDirectTeardown = pendingForcedDirectTeardown"));
        assertTrue(sender.contains("if (forcedDirectTeardown)"));
        assertTrue(sender.contains("package reinit restart suppressed by forced teardown"));
    }

    @Test
    public void persistentStartDecisionIsAtomicAtTheSharedBoundary() throws IOException {
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, false, false, false) == HudRuntimeService.StartDecision.REQUEST);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, false, true, false) == HudRuntimeService.StartDecision.IN_FLIGHT);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, false, false) == HudRuntimeService.StartDecision.ALREADY_ALIVE);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, false, true) == HudRuntimeService.StartDecision.REQUEST);
        assertTrue(HudRuntimeService.startDecisionForTest(
                false, true, true, true, true) == HudRuntimeService.StartDecision.IN_FLIGHT);
        String service = source("HudRuntimeService.java");
        String supervisor = source("HudRuntimeSupervisor.java");
        assertTrue(service.contains("START_IN_FLIGHT.compareAndSet(false, true)"));
        assertTrue(service.contains("HudRuntimeState.isServicePresent()"));
        assertTrue(supervisor.contains("hardResetPending || !HudRuntimeState.isServicePresent()"));
        assertTrue(service.contains("startPersistent skipped start_in_flight"));
        assertTrue(service.contains("static void clearStartRequestForTest()"));
    }

    @Test
    public void liveServiceRemainsAdmittedAfterHeartbeatInterval() {
        assertTrue(HudRuntimeState.publishServicePresent(null, "onCreate"));
        assertFalse(HudRuntimeState.publishServicePresent(null, "duplicate-onCreate"));

        //A service can be healthy for many five-minute heartbeat periods. The
        //admission decision uses process presence, not heartbeat age.
        assertTrue(HudRuntimeState.isServicePresent());
        assertEquals(HudRuntimeService.StartDecision.ALREADY_ALIVE,
                HudRuntimeService.startDecision(
                        false, true, HudRuntimeState.isServicePresent(), false, false));
    }

    @Test
    public void freshProcessDoesNotAdmitPersistedHeartbeat() {
        //A recent persisted heartbeat is intentionally not represented by the
        //process marker after a process recreation.
        HudRuntimeState.clearServicePresent(null, "new-process");

        assertFalse(HudRuntimeState.isServicePresent());
        assertEquals(HudRuntimeService.StartDecision.REQUEST,
                HudRuntimeService.startDecision(false, true, false, false, false));
    }

    @Test
    public void serviceDeathClearsPresenceWhileProcessSurvives() {
        HudRuntimeState.publishServicePresent(null, "onCreate");
        assertTrue(HudRuntimeState.isServicePresent());

        HudRuntimeState.clearServicePresent(null, "onDestroy");

        assertFalse(HudRuntimeState.isServicePresent());
        assertEquals(HudRuntimeService.StartDecision.REQUEST,
                HudRuntimeService.startDecision(false, true, false, false, false));
    }

    @Test
    public void concurrentStartRequestsShareOneAtomicGate() throws Exception {
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger acquired = new AtomicInteger();
        Thread first = startGateAttempt(ready, go, acquired);
        Thread second = startGateAttempt(ready, go, acquired);
        first.start();
        second.start();
        ready.await();
        go.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertEquals(1, acquired.get());
        assertEquals(HudRuntimeService.StartDecision.IN_FLIGHT,
                HudRuntimeService.startDecision(false, true, false, true, false));
    }

    @Test
    public void publicationBetweenSnapshotAndAcquireSuppressesDuplicateStart() {
        //The first snapshot sees no service. A concurrent onCreate publishes
        //before this request acquires the gate, so the production recheck must
        // release the gate and suppress the duplicate start.
        assertEquals(HudRuntimeService.StartDecision.REQUEST,
                HudRuntimeService.startDecision(false, true, false, false, false));
        assertTrue(HudRuntimeService.tryAcquireStartRequest());
        HudRuntimeState.publishServicePresent(null, "concurrent-onCreate");

        assertTrue(HudRuntimeService.shouldSkipStartAfterGate(false));
        assertFalse(HudRuntimeService.shouldSkipStartAfterGate(true));
    }

    @Test
    public void stopRejectedStartAndPackageResetClearPresenceAtBoundary() throws IOException {
        HudRuntimeState.publishServicePresent(null, "onCreate");
        HudRuntimeState.clearServicePresent(null, "stop:explicit");
        assertFalse(HudRuntimeState.isServicePresent());
        assertEquals(HudRuntimeService.StartDecision.BOOT_DISABLED,
                HudRuntimeService.startDecision(false, false, true, false, false));
        assertEquals(HudRuntimeService.StartDecision.SHUTDOWN,
                HudRuntimeService.startDecision(true, true, true, false, false));

        String state = source("HudRuntimeState.java");
        String service = source("HudRuntimeService.java");
        String supervisor = source("HudRuntimeSupervisor.java");
        assertTrue(state.contains("clearServicePresent(context, reason)"));
        assertTrue(state.contains("clearServicePresent(context, \"package-replace-hard-reset:"));
        assertTrue(service.contains("start-rejected:shutdown-active:"));
        assertTrue(service.contains("start-rejected:boot-disabled:"));
        assertTrue(service.contains("HudRuntimeState.clearServicePresent(appContext, \"stop:\" + reason)"));
        assertTrue(service.contains("HudRuntimeState.markStopped(appContext, \"stop:\" + reason)"));
        assertTrue(service.contains("HudRuntimeState.clearServicePresent(this, \"destroyed\")"));
        assertTrue(service.contains("already_present_after_gate"));
        assertTrue(supervisor.contains("supervisor-rejected:shutdown-active:"));
        assertTrue(supervisor.contains("supervisor-rejected:boot-disabled:"));
        assertTrue(supervisor.contains("package-replace-hard-reset:"));
        assertTrue(supervisor.contains("HudRuntimeState.clearServicePresent(appContext"));
    }

    @Test
    public void lifecyclePublicationPrecedesStartGateReleaseAndSummaryKeepsDiagnosticsSeparate()
            throws IOException {
        String state = source("HudRuntimeState.java");
        String service = source("HudRuntimeService.java");
        assertFalse(state.contains("LIVE_TTL_MS"));
        assertTrue(state.contains("servicePresent="));
        assertTrue(state.contains("persistedRunning="));
        assertTrue(state.contains("heartbeatAgeMs="));
        assertFalse(state.contains("return \"stale"));
        int publication = service.indexOf("HudRuntimeState.publishServicePresent(this, \"onCreate\")");
        int gateRelease = service.indexOf("clearStartRequestGate();", publication);
        assertTrue(publication >= 0);
        assertTrue(gateRelease > publication);
        int postGateCheck = service.indexOf("if (shouldSkipStartAfterGate(");
        int postGateRelease = service.indexOf("clearStartRequestGate();", postGateCheck);
        assertTrue(postGateCheck >= 0);
        assertTrue(postGateRelease > postGateCheck);
        assertTrue(service.contains("START_REQUEST_TIMEOUT_MS = 15_000L"));
    }

    private static Thread startGateAttempt(
            CountDownLatch ready, CountDownLatch go, AtomicInteger acquired) {
        return new Thread(() -> {
            ready.countDown();
            try {
                go.await();
                if (HudRuntimeService.tryAcquireStartRequest()) {
                    acquired.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/").resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/").resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
