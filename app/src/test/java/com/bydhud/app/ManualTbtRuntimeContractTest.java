package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the real manual TBT ownership and lifecycle path. */
public final class ManualTbtRuntimeContractTest {
    @Test
    public void mainActivityRoutesManualOutputThroughTheNavigationArbiter() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/MainActivity.java");

        assertTrue(source.contains("NavHudLiveSender.get(this).startManual(state"));
        assertTrue(source.contains("NavHudLiveSender.get(this).publishManual(state"));
        assertTrue(source.contains("NavHudLiveSender.get(this).stopManual("));
        assertFalse(source.contains("start blocked: Connect first"));
        assertFalse(source.contains("send blocked: service not connected"));
    }

    @Test
    public void manualOwnerDeclaresNavigationAndRequestsTbtOncePerGeneration()
            throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");

        assertTrue(source.contains("manualTbtGeneration++"));
        assertTrue(source.contains("MANUAL_TBT_OWNER, generation, true, true"));
        assertFalse(source.contains("manualTbtDashboardPending"));
        assertFalse(source.contains("manual-frame:dashboard-ready"));
        assertTrue(source.contains("pendingManualPublishState = copy"));
        assertTrue(source.contains("drainManualPublish"));
        assertTrue(source.contains("tbtPublisher.publishManualFrame("));
        assertTrue(source.contains("effectiveManualState(state)"));
        assertTrue(source.contains("hudOutput.ensureBound(\"manual-start\")"));
    }

    @Test
    public void manualOffRestoresCachedDirectOrPublishesTeardown() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");

        assertTrue(source.contains("endManualRoute("));
        assertTrue(source.contains("selectRemainingTbtRoute(MANUAL_TBT_OWNER"));
        assertTrue(source.contains("if (!tbtPublisher.isRouteActive()) tbtPublisher.sendTeardownStatus()"));
        assertTrue(source.contains("latestWazeClusterFrame"));
        assertTrue(source.contains("latestRestorableWazeFrame()"));
        assertTrue(source.contains("latestWazeSurfaceFrame"));
        assertTrue(source.contains("latestGMapsDirectFrame"));
    }

    @Test
    public void directFramesRemainCachedButCannotReplaceActiveManualOwner() throws IOException {
        String sender = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        String publisher = source("app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");

        assertTrue(sender.contains("if (!manualTbtActive && shouldClaimTbtOwnerForFrameForTest("));
        assertTrue(sender.contains("latestGMapsDirectFrame = frame"));
        assertTrue(sender.contains("latestWazeClusterFrame = frame"));
        assertTrue(publisher.contains("MANUAL_OWNER.equals(ownerPackage)"));
        assertTrue(publisher.contains("ignored manual_owner"));
    }

    @Test
    public void diagnosticsCoverAllTbtPlanes() throws IOException {
        String publisher = source("app/src/main/java/com/bydhud/app/VehicleTbtPublisher.java");
        String proxy = source(
                "app/src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");
        String sender = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");

        assertTrue(publisher.contains("\"dashboard_30011\""));
        assertTrue(publisher.contains("\"instrument_fid\""));
        assertTrue(proxy.contains("\"instrument_sdk:sendAutoNaviStatus\""));
        assertTrue(publisher.contains("\"instrument_proxy\""));
        assertTrue(publisher.contains("\"amap_broadcast\""));
        assertTrue(publisher.contains("TbtTxLog.record"));
        assertTrue(sender.contains("recordDeferredLifecycle("));
        assertTrue(sender.contains("recordDeferredFrame("));
    }

    @Test
    public void shutdownWaitsForQueuedManualTeardown() throws IOException {
        String main = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        String sender = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");

        assertTrue(main.contains("sender.stopManual(stopReason, false, () ->"));
        assertTrue(main.contains("if (!sender.isRunning()) hudOutput.shutdown(reason)"));
        assertTrue(sender.contains(
                "active || stopInProgress || runtimeReinitInProgress || manualTbtActive"));
    }

    @Test
    public void manualEditsUseCanonicalRoadAndRejectIncompleteNumbers() throws IOException {
        String main = source("app/src/main/java/com/bydhud/app/MainActivity.java");

        assertTrue(main.contains("state.roadName = combo.roadLabel()"));
        assertTrue(main.contains("parseIntOrNull"));
        assertTrue(main.contains("distanceEdit == null\n"
                + "                ? clamp(state.distanceToIntersection, 0, 99999)"));
        assertTrue(main.contains("laneCountEdit == null\n"
                + "                ? clamp(state.numOfLanes, 0, 8)"));
        assertTrue(main.contains("numeric fields are incomplete"));
        assertTrue(main.contains("raw apply waiting: numeric fields are incomplete"));
    }

    @Test
    public void hudStopUsesSenderGenerationGuardBeforeCompletingDemotion() throws IOException {
        String main = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        String sender = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");

        assertTrue(main.contains("demoteHudToTbtObserver("));
        assertTrue(main.contains("demoteHudToObserver"));
        assertFalse(main.contains("stop(normalized, \"ui-log-only\", true)"));
        assertTrue(sender.contains("pendingHudDemotionObserverPackage"));
        assertTrue(sender.contains("completeHudDemotionObserverRefresh()"));
        assertTrue(sender.contains("nextObserverLifecycleTokenForTest"));
        assertTrue(sender.contains("acceptsHudStopCallbackForTest"));
        assertTrue(sender.contains("stale waze HUD clear callback ignored"));
        assertTrue(sender.contains("stale gmaps HUD clear callback ignored"));
        assertTrue(sender.contains("manualPublishScheduled"));
    }

    @Test
    public void tbtRetentionUsesLiveChannelsInsteadOfCachedFrames() throws IOException {
        String sender = source("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        int start = sender.indexOf("private boolean shouldRetainRouteForTbt");
        int end = sender.indexOf("static boolean shouldRetainTbtRouteOnHudSwitchForTest", start);
        String policy = sender.substring(start, end);

        assertTrue(policy.contains("wazeDirectChannel.isActive()"));
        assertTrue(policy.contains("gmapsDirectChannel.isRunning()"));
        assertFalse(policy.contains("latestWazeClusterFrame"));
        assertFalse(policy.contains("latestGMapsDirectFrame"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
