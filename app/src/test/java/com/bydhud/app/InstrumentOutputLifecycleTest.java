package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Exercises the production timer, with virtual time instead of sleeping for 30 seconds. */
public final class InstrumentOutputLifecycleTest {
    @Test
    public void twoStartupFailuresKeepOneImmediateRetryThenOneThirtySecondTimer() {
        ManualScheduler scheduler = new ManualScheduler();
        InstrumentProxyManager.OutputRetry recovery = new InstrumentProxyManager.OutputRetry();
        List<Long> attempts = new ArrayList<>();
        assertTrue(recovery.setActive(true));
        assertTrue(InstrumentProxyManager.shouldRetryStart(true, true, 1));
        assertFalse(InstrumentProxyManager.shouldRetryStart(true, true, 2));
        assertTrue(recovery.schedule(scheduler, 30_000L, 42L, attempts::add));
        assertFalse(recovery.schedule(scheduler, 30_000L, 42L, attempts::add));
        assertEquals(1, scheduler.tasks.size());
        scheduler.advance(29_999L);
        assertTrue(attempts.isEmpty());
        scheduler.advance(1L);
        assertEquals(List.of(42L), attempts);
        assertTrue(recovery.schedule(scheduler, 30_000L, 43L, attempts::add));
        scheduler.advance(29_999L);
        assertEquals(1, attempts.size());
        scheduler.advance(1L);
        assertEquals(List.of(42L, 43L), attempts);
    }

    @Test
    public void idleColdRuntimeDoesNotCreatePeriodicRecovery() {
        ManualScheduler scheduler = new ManualScheduler();
        InstrumentProxyManager.OutputRetry recovery = new InstrumentProxyManager.OutputRetry();
        assertFalse(recovery.schedule(scheduler, 30_000L, 1L, ignored -> {
            throw new AssertionError("idle runtime retried");
        }));
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void ownerHandoffKeepsDemandAndDoesNotDuplicateOrPostponeRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        InstrumentProxyManager.OutputRetry recovery = new InstrumentProxyManager.OutputRetry();
        List<Long> attempts = new ArrayList<>();
        recovery.setActive(true); // Accepted Waze route.
        recovery.schedule(scheduler, 30_000L, 10L, attempts::add);
        scheduler.advance(15_000L);
        assertFalse(recovery.setActive(true)); // Accepted GMaps successor.
        assertFalse(recovery.schedule(scheduler, 30_000L, 10L, attempts::add));
        scheduler.advance(15_000L);
        assertEquals(List.of(10L), attempts);
    }

    @Test
    public void stopCancelsTimerAndDequeuedStaleWorkCannotConsumeNewDemand() {
        ManualScheduler scheduler = new ManualScheduler();
        InstrumentProxyManager.OutputRetry recovery = new InstrumentProxyManager.OutputRetry();
        List<Long> attempts = new ArrayList<>();
        recovery.setActive(true);
        recovery.schedule(scheduler, 30_000L, 10L, attempts::add);
        Task old = scheduler.tasks.get(0);
        assertTrue(recovery.setActive(false));
        assertTrue(old.isCancelled());
        assertFalse(recovery.isActive());
        recovery.setActive(true); // Explicit output after Shutdown.
        recovery.schedule(scheduler, 30_000L, 12L, attempts::add);
        old.command.run(); // Even a task dequeued before cancellation is harmless.
        assertTrue(attempts.isEmpty());
        assertFalse(recovery.schedule(scheduler, 1L, 12L, attempts::add));
        scheduler.advance(30_000L);
        assertEquals(List.of(12L), attempts);
    }

    @Test
    public void readyOrCapabilityBlockCancelsTimerWithoutDroppingDemand() {
        ManualScheduler scheduler = new ManualScheduler();
        InstrumentProxyManager.OutputRetry recovery = new InstrumentProxyManager.OutputRetry();
        List<Long> attempts = new ArrayList<>();
        recovery.setActive(true);
        recovery.schedule(scheduler, 30_000L, 10L, attempts::add);
        recovery.cancel();
        assertTrue(recovery.isActive());
        assertTrue(scheduler.tasks.get(0).isCancelled());
        scheduler.tasks.get(0).command.run();
        scheduler.advance(30_000L);
        assertTrue(attempts.isEmpty());
    }

    @Test
    public void bootServiceStopRetainsActiveHelperButShutdownOrUpgradeAlwaysWins() {
        assertFalse(InstrumentProxyManager.shouldShutdownForRuntimeStop(true, false));
        assertTrue(InstrumentProxyManager.shouldShutdownForRuntimeStop(false, false));
        assertTrue(InstrumentProxyManager.shouldShutdownForRuntimeStop(true, true));
        assertTrue(InstrumentProxyManager.shouldShutdownForRuntimeStop(false, true));
    }

    @Test
    public void sharedPublisherWiresOnlyAcceptedLifecycleAndKeepsIndependentPlanes() throws Exception {
        String publisher = source("VehicleTbtPublisher.java");
        String begin = between(publisher,
                "void beginRoute(String packageName, long generation,\n"
                        + "            boolean switchDashboard, boolean hasHudPriority,\n"
                        + "            String reason, Runnable dashboardCompletion)",
                "    /**");
        assertTrue(begin.indexOf("ignored manual_owner") < begin.indexOf("setOutputDemand(true"));
        assertTrue(begin.indexOf("ignored stale_generation") < begin.indexOf("setOutputDemand(true"));
        assertTrue(begin.indexOf("routeActive = true") < begin.lastIndexOf("setOutputDemand(true"));
        String manualFrame = between(publisher, "void publishManualFrame(", "void recordDeferredLifecycle(");
        assertFalse(manualFrame.contains("setOutputDemand"));
        assertFalse(manualFrame.contains("ensureStarted"));
        assertTrue(manualFrame.contains("sendInstrumentGuidance("));
        assertTrue(manualFrame.contains("sendAmapFrame("));
        String end = between(publisher, "private void endRoute(", "void updateOwnerHudPriority(");
        assertTrue(end.indexOf("sendTerminalGuidanceClear") < end.indexOf("setOutputDemand(false"));
        assertTrue(end.indexOf("sendStatus(STATUS_IDLE") < end.indexOf("setOutputDemand(false"));
        assertTrue(end.indexOf("routeActive = false") < end.indexOf("setOutputDemand(false"));
    }

    @Test
    public void currentOwnerReplayPreservesStatusGuidanceLanesAndRejectsStoppedRoute() throws Exception {
        String publisher = source("VehicleTbtPublisher.java");
        String replay = between(publisher, "private void replayInstrumentState()", "private void handleInstrumentUnavailable(");
        assertTrue(replay.indexOf("if (!routeActive) return") < replay.indexOf("sendNavigationStatus("));
        assertTrue(replay.indexOf("if (!routeActive) return") < replay.indexOf("sendGuidance("));
        assertTrue(replay.contains("trace.routeToken = routeToken"));
        assertTrue(replay.contains("lastInstrumentLaneDirections.clone()"));
        assertTrue(replay.contains("lastInstrumentLaneRecommendations.clone()"));
        String handoff = between(publisher, "boolean replaceDirectRoute(", "private static boolean isDirectOwner(");
        assertTrue(handoff.indexOf("hasInstrumentGuidance = false") < handoff.indexOf("sendInstrumentFrame(successorFrame"));
        assertFalse(handoff.contains("setOutputDemand(false"));
        assertFalse(handoff.contains("instrument.shutdown"));
        assertFalse(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                false, "com.waze", 1L, "com.waze", 1L, 1L, 1L));
        assertFalse(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                true, "com.waze", 1L, "com.google.android.apps.maps", 2L, 1L, 2L));
        assertTrue(VehicleTbtPublisher.shouldDispatchDashboardForTest(
                true, "com.google.android.apps.maps", 2L, "com.google.android.apps.maps", 2L, 2L, 2L));
    }

    @Test
    public void managerFencesGenerationPreservesTimeoutAndLogsStagesWithoutShellOutput() throws Exception {
        String manager = source("InstrumentProxyManager.java");
        String retry = between(manager, "private void scheduleOutputRetryLocked()", "void onAuthorizationVerified()");
        assertTrue(retry.contains("generation != requestGeneration"));
        assertTrue(retry.contains("state != State.IDLE"));
        String calls = between(manager, "private void executeCall(", "private void handleCallTimeout(");
        assertFalse(calls.contains("ensureStarted("));
        assertTrue(manager.contains("START_TIMEOUT_MS = 5_000L"));
        assertTrue(manager.contains("RETRY_DELAY_MS = 30_000L"));
        assertTrue(manager.contains("handoff timeout lastStage="));
        for (String stage : List.of("authorization-started", "authorization-ok", "cleanup-started",
                "cleanup-complete", "launch-started", "launch-complete", "handoff-accepted",
                "connect-requested", "connect-received")) {
            assertTrue(stage, manager.contains('"' + stage + '"'));
        }
        assertFalse(manager.contains(".shortDetail()"));
        String shutdown = between(manager, "void shutdown(String reason)", "private void launch(");
        assertTrue(shutdown.indexOf("shutdownCandidate(current, currentGeneration)")
                < shutdown.indexOf("cleanupHelper(\"shutdown\")"));
        assertTrue(shutdown.indexOf("outputRetry.setActive(false)")
                < shutdown.indexOf("shutdownCandidate(current, currentGeneration)"));
        String service = source("HudRuntimeService.java");
        assertTrue(service.contains("releaseInstrumentRuntime(appContext, \"runtime-stop:"));
        assertTrue(service.contains("releaseInstrumentRuntime(this, \"runtime-destroyed\")"));
        assertTrue(service.contains("HudPrefs.isUserShutdownActive(context)"));
        assertTrue(service.contains("HudRuntimeUpgradeGuard.hasPendingHardReset(context)"));
    }

    @Test
    public void lateCaseOutcomesKeepCapturedLabelsButCannotUpdateCurrentGuidanceOrLight() throws Exception {
        HudCheckDiagnostics diagnostics = new HudCheckDiagnostics();
        HudCheckState captured = new HudCheckState().toggleRun();
        HudCheckState current = captured.step(HudCheckState.Field.MANEUVER, 1)
                .step(HudCheckState.Field.TRAFFIC_LIGHT, 1);
        for (String plane : List.of("Instrument", "Instrument-light")) {
            String pending = diagnostics.changed(current, plane, 0, "pending");
            assertTrue(pending.contains("case=" + HudCheckDiagnostics.sampleKey(current)));
            String lateResult = diagnostics.changed(captured, plane, -1, "proxy-unavailable");
            assertTrue(lateResult.contains("case=" + HudCheckDiagnostics.sampleKey(captured)));
            assertFalse(lateResult.contains("case=" + HudCheckDiagnostics.sampleKey(current)));
            assertEquals(null, diagnostics.changed(captured, plane, -1, "proxy-unavailable"));
        }

        String publisher = source("VehicleTbtPublisher.java");
        String guidance = between(publisher, "private void recordInstrumentGuidanceResult(",
                "private static String checkResultReason(");
        int guidanceLog = guidance.indexOf("recordHudCheck(trace, \"Instrument\", outcome, outcomeReason)");
        int latestGuard = guidance.indexOf("if (MANUAL_OWNER.equals(trace.owner) && latestGuidance)");
        assertTrue(guidanceLog >= 0 && guidanceLog < latestGuard);
        assertTrue(guidance.indexOf("trace.routeToken != routeToken") < guidanceLog);
        assertTrue(latestGuard < guidance.indexOf("hudCheckInstrumentResult = outcome"));
        assertTrue(latestGuard < guidance.indexOf("hudCheckInstrumentReason = outcomeReason"));

        String light = between(publisher, "private void publishHudCheckLight(",
                "private static boolean successfulCheckResult(");
        int lightLog = light.indexOf("recordHudCheck(sample, \"Instrument-light\", outcome, outcomeReason)");
        int currentIndexGuard = light.indexOf("if (index != hudCheckLightIndex ||");
        assertTrue(lightLog >= 0 && lightLog < currentIndexGuard);
        assertTrue(light.indexOf("routeActive && token == routeToken && sample != null") < lightLog);
        assertTrue(currentIndexGuard < light.indexOf("hudCheckLightOwned = false"));
        assertTrue(currentIndexGuard < light.indexOf("hudCheckLightResult = outcome"));
        assertTrue(currentIndexGuard < light.indexOf("hudCheckLightReason = outcomeReason"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start " + start, from >= 0);
        assertTrue("missing end " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/" + name);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Task> tasks = new ArrayList<>();
        long now;

        ManualScheduler() { super(1); }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Task task = new Task(command, now + unit.toMillis(delay));
            tasks.add(task);
            return task;
        }

        void advance(long milliseconds) {
            now += milliseconds;
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.isDone() && task.dueAt <= now) task.run();
            }
        }
    }

    private static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable command;
        final long dueAt;

        Task(Runnable command, long dueAt) {
            super(command, null);
            this.command = command;
            this.dueAt = dueAt;
        }

        @Override public long getDelay(TimeUnit unit) { return unit.convert(dueAt, TimeUnit.MILLISECONDS); }
        @Override public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
