package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source guards for passive exports; these do not claim Binder or vehicle runtime verification. */
public final class ConfigurationRuntimeSnapshotSourceContractTest {
    private static final String[] OWNERS = {
            "HudOutputCoordinator", "NavHudLiveSender", "NavAppDisplayController",
            "InstrumentProxyManager", "NavRuntimePermissionRepair"
    };

    @Test
    public void snapshotsNeverInitializeOwnersOrInvokeRuntimeActions() throws IOException {
        for (String owner : OWNERS) {
            String source = source(owner);
            String snapshot = snapshots(source);
            if (!owner.equals("NavRuntimePermissionRepair")) {
                assertTrue(owner, source.contains("private static volatile " + owner + " instance;"));
                assertTrue(owner, snapshot.contains(owner + " current = instance;"));
                assertTrue(owner, snapshot.contains("\"not_initialized\""));
                assertFalse(owner, snapshot.contains("new " + owner + "("));
            }
            for (String forbidden : new String[]{
                    "get(context", "getApplicationContext(", "new Thread(", "new Handler(",
                    "Executors.", "ensureStarted(", "ensureBound(", "bindService(",
                    "startService(", "isBinderAlive(", ".ping(", "checkAndRepair",
                    "getSharedPreferences(", "getHudConfig(", "getIntProperty(",
                    "requestWazeRouteStateSnapshot(", "refreshTbtObserversOnMain("}) {
                assertFalse(owner + ": " + forbidden, snapshot.contains(forbidden));
            }
            assertTrue(owner, snapshot.contains("\"process_memory\""));
            assertTrue(owner, snapshot.contains("\"capturedElapsedMs\""));
        }
    }

    @Test
    public void existingOwnerThreadCaptureIsBoundedAndDoesNotInterruptTheOwner() throws IOException {
        for (String owner : new String[]{"HudOutputCoordinator", "NavHudLiveSender"}) {
            String snapshot = snapshots(source(owner));
            assertTrue(owner, snapshot.contains("Looper.myLooper() == current."));
            assertTrue(owner, snapshot.contains("capture.get(1_500L, TimeUnit.MILLISECONDS)"));
            assertTrue(owner, snapshot.contains("\"owner_thread_busy\""));
            assertTrue(owner, snapshot.contains("\"owner_thread_stopped\""));
            assertTrue(owner, snapshot.contains("Thread.currentThread().interrupt()"));
            assertTrue(owner, snapshot.contains(".removeCallbacks(capture)"));
            assertTrue(owner, snapshot.contains("capture.cancel(false)"));
            assertFalse(owner, snapshot.contains("cancel(true)"));
            assertFalse(owner, snapshot.contains(".start()"));
        }
    }

    @Test
    public void missingHistoryAndPhysicalConfirmationRemainExplicitlyUnknown() throws IOException {
        String hud = snapshots(source("HudOutputCoordinator"));
        assertTrue(hud.contains("\"completedNonClearSendCount\", sendCount"));
        assertTrue(hud.contains("\"sendSuccessCount\", new JSONObject().put(\"status\", \"unsupported\")"));
        assertTrue(hud.contains("\"lastTransportResult\", new JSONObject().put(\"status\", \"unsupported\")"));
        assertTrue(hud.contains("\"physicalRenderAcknowledgement\", \"not_available\""));

        String sender = snapshots(source("NavHudLiveSender"));
        assertTrue(sender.contains(".put(\"waze\", waze).put(\"gmaps\", gmaps)"));
        assertTrue(sender.contains("\"clusterFramePresent\", latestWazeClusterFrame != null"));
        assertTrue(sender.contains("\"framePresent\", latestGMapsDirectFrame != null"));
        assertTrue(sender.contains("\"frameAgeMs\", new JSONObject().put(\"status\", \"unsupported\")"));

        String display = snapshots(source("NavAppDisplayController"));
        assertTrue(display.contains("synchronized (current.lock)"));
        assertTrue(display.contains("new TreeMap<>(current.states)"));
        assertTrue(display.contains("\"cached_app_observation_not_current_physical_confirmation\""));
        assertTrue(display.contains("\"complete_command_history_not_recorded\""));
        assertTrue(display.contains("\"autoContainerReadback\", \"not_queried\""));

        String instrument = snapshots(source("InstrumentProxyManager"));
        assertTrue(instrument.contains("synchronized (current.lock)"));
        assertTrue(instrument.contains("\"binderReferencePresent\", current.proxyBinder != null"));
        assertTrue(instrument.contains("\"binderLiveness\", \"not_queried\""));

        String repair = snapshots(source("NavRuntimePermissionRepair"));
        assertTrue(repair.contains("synchronized (LOCK)"));
        assertTrue(repair.contains("\"lastResult\", new JSONObject().put(\"status\", \"unsupported\")"));
    }

    @Test
    public void snapshotsExcludeNavigationContentsAndAuthenticationSecrets() throws IOException {
        for (String owner : OWNERS) {
            String snapshot = snapshots(source(owner));
            for (String forbidden : new String[]{
                    "directFrame.", "latestWazeClusterFrame.", "latestWazeSurfaceFrame.",
                    "latestGMapsDirectFrame.", "manualState.", "latestManualSourceState",
                    "current.nonce", "current.launchToken", "current.helperIdentity",
                    "pendingReason", "pendingStopStartReason", "pendingSourceSwitchReason",
                    "pendingShutdownReturnReason", "observation.status"}) {
                assertFalse(owner + ": " + forbidden, snapshot.contains(forbidden));
            }
        }
    }

    private static String snapshots(String source) {
        String result = method(source, "static JSONObject configurationSnapshot() throws Exception");
        for (String suffix : new String[]{"OnWorker", "OnOwner"}) {
            String signature = "private JSONObject configurationSnapshot" + suffix + "() throws Exception";
            if (source.contains(signature)) result += method(source, signature);
        }
        return result;
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        assertTrue("missing " + signature, from >= 0);
        int to = source.indexOf("\n    }", from);
        assertTrue("missing end of " + signature, to > from);
        return source.substring(from, to + 6);
    }

    private static String source(String owner) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + owner + ".java");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + owner + ".java");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
