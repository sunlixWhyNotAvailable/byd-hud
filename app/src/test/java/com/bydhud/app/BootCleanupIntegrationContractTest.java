package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class BootCleanupIntegrationContractTest {
    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/com/bydhud/app");
        if (!Files.isDirectory(root)) root = Path.of("app/src/main/java/com/bydhud/app");
        return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
    }

    @Test public void applicationBootReceiverAndDirectRuntimeStartsUseTheSameGate()
            throws Exception {
        String application = source("BydHudApplication.java");
        int processGuard = application.indexOf("Application.getProcessName()");
        int bootInit = application.indexOf("BootCleanupGate.initialize(this)");
        int updaterInit = application.indexOf("AppUpdateManager.initialize(this)");
        assertTrue(processGuard >= 0 && bootInit > processGuard && updaterInit > bootInit);

        String receiver = source("BootReceiver.java");
        assertTrue(receiver.contains("BootCleanupGate.handleBootAction(context, action"));
        assertTrue(receiver.contains("if (admitted)"));
        assertTrue(receiver.contains("PendingResult pendingResult = goAsync()"));
        assertTrue(receiver.contains("pendingResult.finish()"));
        assertEquals(2, receiver.split("goAsync\\(\\)", -1).length - 1);
        assertTrue(receiver.contains("pendingResult::finish"));
        String gate = source("BootCleanupGate.java");
        assertTrue(gate.contains("enqueue(() -> runWithCompletion(() -> {"));
        assertTrue(gate.contains("}, completion));"));
        assertFalse(receiver.contains("clearStaleProjectionIntentForBoot(action)"));

        String runtime = source("HudRuntimeService.java");
        assertTrue(runtime.contains("BootCleanupGate.runWhenReady(appContext"));
        assertTrue(runtime.contains("private static void startPersistentAfterBootGate"));
        assertTrue(runtime.contains("completeStartAfterBootGate(reason)"));

        String activity = source("MainActivity.java");
        assertTrue(activity.contains("BootCleanupGate.runWhenReady(this,"));
        assertTrue(activity.contains("HudRuntimeSupervisor.ensureStarted(this, \"activity-create\")"));
        assertTrue(activity.contains("HudRuntimeSupervisor.ensureStarted(this, \"activity-start\")"));
    }

    @Test public void asynchronousCompletionRunsForAdmittedFencedAndFailedWork() {
        AtomicInteger recovered = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        BootCleanupGate.runWithCompletion(recovered::incrementAndGet, finished::incrementAndGet);
        assertEquals(1, recovered.get());
        assertEquals(1, finished.get());

        // A fenced gate returns before invoking recovery, but must release the receiver.
        BootCleanupGate.runWithCompletion(() -> { }, finished::incrementAndGet);
        assertEquals(1, recovered.get());
        assertEquals(2, finished.get());
        try {
            BootCleanupGate.runWithCompletion(() -> {
                throw new IllegalStateException("failed gate or consumer");
            }, finished::incrementAndGet);
            fail("expected the original failure");
        } catch (IllegalStateException expected) {
            assertEquals("failed gate or consumer", expected.getMessage());
        }
        assertEquals(3, finished.get());
    }

    @Test public void bothStoresReportDurableBootClearAndWazeConsumersAwaitGate()
            throws Exception {
        String projection = source("NavAppDisplayController.java");
        String projectionClear = projection.substring(
                projection.indexOf("boolean clearStaleProjectionIntentForBoot"),
                projection.indexOf("NavAppDisplayState lastState"));
        assertTrue(projectionClear.contains(".clear().commit()"));
        assertFalse(projectionClear.contains(".apply()"));

        String waze = source("WazeRouteLifecycleStore.java");
        String wazeClear = waze.substring(waze.indexOf("static boolean clearForBoot"),
                waze.indexOf("static String eventDecision"));
        assertTrue(wazeClear.contains(".clear().commit()"));
        assertTrue(waze.contains("BootCleanupGate.awaitReady(context)"));
        String gate = source("BootCleanupGate.java");
        assertTrue(gate.contains("Looper.myLooper() == Looper.getMainLooper()"));
        assertTrue(gate.contains("if (!ready)"));
        assertTrue(gate.contains("boot_cleanup consumer_fenced"));
        assertFalse(waze.contains("if (rebooted || packageChanged)"));
    }
}
