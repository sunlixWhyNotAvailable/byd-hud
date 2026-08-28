package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** The diagnostic UI must exercise the real owner and both lane encodings. */
public final class HudCheckIntegrationContractTest {
    @Test
    public void stockLanesReachInstrumentWithoutALanePng() {
        HudState stock = new HudCheckState().toHudState();
        assertFalse(stock.includeLaneBitmap);
        VehicleTbtPublisher.LanePayload lanes = VehicleTbtPublisher.lanePayloadForTest(stock);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, lanes.directions);
        assertArrayEquals(new int[]{0, 255, 0, 255, 0}, lanes.recommendations);

        HudState bitmap = new HudCheckState().withLaneBitmap(true).toHudState();
        assertTrue(bitmap.includeLaneBitmap);
        assertArrayEquals(lanes.directions,
                VehicleTbtPublisher.lanePayloadForTest(bitmap).directions);
        assertArrayEquals(lanes.recommendations,
                VehicleTbtPublisher.lanePayloadForTest(bitmap).recommendations);
        assertEquals(0, VehicleTbtPublisher.lanePayloadForTest(new HudState()).directions.length);
    }

    @Test
    public void diagnosticLanesIgnoreGlobalPreferenceWithoutChangingDirectPolicy() throws Exception {
        for (HudCheckState.Mode mode : HudCheckState.Mode.values()) {
            HudCheckState check = new HudCheckState().selectMode(mode);
            assertTrue(VehicleTbtPublisher.shouldPublishLanes(false, check.toHudState()));
            assertTrue(VehicleTbtPublisher.shouldPublishLanes(
                    false, check.withLaneBitmap(true).toHudState()));
        }
        assertFalse(VehicleTbtPublisher.shouldPublishLanes(false, null));
        assertFalse(VehicleTbtPublisher.shouldPublishLanes(false, new HudState()));
        assertTrue(VehicleTbtPublisher.shouldPublishLanes(true, null));
        assertTrue(VehicleTbtPublisher.shouldPublishLanes(true, new HudState()));
        assertTrue(source("VehicleTbtPublisher.java").contains(
                "if (shouldPublishLanes(HudPrefs.isLaneOutputEnabled(context), manualState))"));
    }

    @Test
    public void compoundLanesAndRecommendationsSurviveBothModes() {
        HudCheckState check = new HudCheckState();
        for (int index = 0; index < 8; index++) {
            VehicleTbtPublisher.LanePayload stock =
                    VehicleTbtPublisher.lanePayloadForTest(check.toHudState());
            VehicleTbtPublisher.LanePayload bitmap = VehicleTbtPublisher.lanePayloadForTest(
                    check.withLaneBitmap(true).toHudState());
            assertTrue(stock.directions.length > 0);
            assertArrayEquals(stock.directions, bitmap.directions);
            assertArrayEquals(stock.recommendations, bitmap.recommendations);
            check = check.step(HudCheckState.Field.LANES, 1);
        }
    }

    @Test
    public void checkProjectionBypassesPreferencesOnlyForDiagnosticFrames() throws Exception {
        String sender = source("NavHudLiveSender.java");
        String projection = sender.substring(sender.indexOf("private HudState effectiveManualState"),
                sender.indexOf("private final Context context"));
        assertTrue(projection.indexOf("state.hudCheck != null")
                < projection.indexOf("HudDisplayPolicy.apply"));
        assertTrue(projection.contains("HudOutputPreferences.apply(context, effective)"));
        String output = source("HudOutputCoordinator.java");
        assertTrue(output.contains("HudCheckPayload.buildRoadInfo(context, preparedHudCheck)"));
        assertTrue(output.contains("DirectTbtPayload.Options.from(context)"));
    }

    @Test
    public void clockAndStopBelongToTheExistingWorker() throws Exception {
        String sender = source("NavHudLiveSender.java");
        assertTrue(sender.contains("HUD_CHECK_INTERVAL_MS = 500L"));
        assertTrue(sender.contains("handler.postDelayed(hudCheckTick, hudCheckIntervalMs(next))"));
        assertTrue(sender.contains("handler.postDelayed(hudCheckTick, hudCheckIntervalMs(hudCheckState))"));
        int stop = sender.indexOf("private void stopManualOnWorker");
        String stopping = sender.substring(stop, sender.indexOf("private HudState effectiveManualState", stop));
        assertTrue(stopping.indexOf("handler.removeCallbacks(hudCheckTick)")
                < stopping.indexOf("endManualRoute("));
        assertTrue(stopping.contains("selectRemainingTbtRoute(MANUAL_TBT_OWNER"));
        String activity = source("MainActivity.java");
        assertTrue(activity.contains("if (!isChangingConfigurations())"));
        assertTrue(activity.contains("stopHudCheckIfRunning(\"hud-check-background\")"));
        int toggle = activity.indexOf("public void composeHudCheckToggleRunning()");
        String start = activity.substring(toggle,
                activity.indexOf("public void composeHudCheckSetAutomatic", toggle));
        assertTrue(start.indexOf("if (!activityResumed || destroyed || exitRequested) return;")
                < start.indexOf("updateHudCheck("));
    }

    @Test
    public void onlyAutomaticCaseChangesUseTheHalfSecondClock() {
        HudCheckState basic = new HudCheckState().toggleRun();
        assertEquals(1000L, NavHudLiveSender.hudCheckIntervalMs(basic));
        HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED).toggleRun();
        assertEquals(500L, NavHudLiveSender.hudCheckIntervalMs(extended));
        assertEquals(1000L, NavHudLiveSender.hudCheckIntervalMs(extended.withAutomatic(false)));
        assertEquals(500L, NavHudLiveSender.hudCheckIntervalMs(
                extended.withAutomatic(false).withAutomatic(true)));
    }

    @Test
    public void pausingAutoDoesNotClearOrRebuildTheHeldPacket() {
        HudCheckState running = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .toggleRun().tick();
        assertTrue(HudOutputCoordinator.sameHudCheckPayload(
                running, running.withAutomatic(false)));
        assertTrue(HudOutputCoordinator.sameHudCheckPayload(running, running.stop()));
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(running, running.tick()));
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(running, null));
    }

    @Test
    public void lostAuxiliaryTransportDropsServiceOwnershipButRetainsPayloadCleanup() throws Exception {
        String output = source("HudOutputCoordinator.java");
        int failureStart = output.indexOf("private void handleTransportFailure(");
        String failure = output.substring(failureStart,
                output.indexOf("private void handleProtocolResult(", failureStart));
        assertTrue(failure.contains("hudCheckOwnedServices.clear()"));
        assertTrue(failure.indexOf("hudCheckOwnedServices.clear()")
                < failure.indexOf("client.unbind()"));
        assertTrue(failure.contains("hudCheckReadyServices.clear()"));
        assertTrue(failure.contains("hudCheckAuxiliaryResult = -1"));
        assertTrue(failure.contains("MainActivity.publishSharedUiStateChange()"));
        assertFalse(failure.contains("hudCheckPendingClearTopics.clear()"));
        assertFalse(failure.contains("hudCheckActiveTopics.clear()"));

        int packetStart = output.indexOf("private boolean sendHudCheckPacket(");
        String packet = output.substring(packetStart,
                output.indexOf("private boolean flushHudCheckAuxiliaryClears(", packetStart));
        String invalidation = "hudCheckReadyServices.remove(packet.serviceId);\n"
                + "                hudCheckOwnedServices.remove(packet.serviceId);";
        assertTrue(packet.contains(invalidation));
        assertTrue(packet.substring(packet.indexOf("catch (RemoteException"))
                .contains("hudCheckOwnedServices.remove(packet.serviceId)"));
        assertTrue(output.contains("client.unbind();\n        hudCheckReadyServices.clear();\n"
                + "        hudCheckOwnedServices.clear();"));
        // Only a fresh successful start may reacquire ownership, never ALREADY_STARTED.
        assertTrue(output.contains("if (result == RESULT_OK) hudCheckOwnedServices.add(serviceId)"));
    }

    @Test
    public void unavailableInstrumentInvalidatesItsDiagnosticResultsBeforeFallbackReturns() throws Exception {
        String tbt = source("VehicleTbtPublisher.java");
        int unavailableStart = tbt.indexOf("private void handleInstrumentUnavailable(");
        String unavailable = tbt.substring(unavailableStart,
                tbt.indexOf("static boolean shouldPreserveAmapFallbackForTest(", unavailableStart));
        assertTrue(unavailable.contains("if (routeActive && MANUAL_OWNER.equals(ownerPackage))"));
        assertTrue(unavailable.contains("hudCheckInstrumentResult = -1"));
        assertTrue(unavailable.contains("if (hudCheckLightIndex >= 0) hudCheckLightResult = -1"));
        assertTrue(unavailable.indexOf("MainActivity.publishSharedUiStateChange()")
                < unavailable.indexOf("if (!hasInstrumentStatus && !hasInstrumentGuidance) return"));
        assertFalse(unavailable.contains("hudCheckAmapResult ="));
        assertTrue(unavailable.contains("shouldPreserveAmapFallbackForTest("));
    }

    @Test
    public void amapDispatchPublishesOnlyChangedDiagnosticFrameResults() throws Exception {
        String tbt = source("VehicleTbtPublisher.java");
        int sendStart = tbt.indexOf("private boolean sendAmap(");
        String send = tbt.substring(sendStart, tbt.indexOf("private Trace trace(", sendStart));
        assertTrue(send.contains("MANUAL_OWNER.equals(trace.owner) && \"frame\".equals(operation)\n"
                + "                    && hudCheckAmapResult != 1"));
        assertTrue(send.contains("MANUAL_OWNER.equals(trace.owner) && \"frame\".equals(operation)\n"
                + "                    && hudCheckAmapResult != -1"));
        assertTrue(send.contains("hudCheckAmapResult = 1;\n"
                + "                MainActivity.publishSharedUiStateChange();"));
        assertTrue(send.contains("hudCheckAmapResult = -1;\n"
                + "                MainActivity.publishSharedUiStateChange();"));
    }

    @Test
    public void auxiliaryTrafficUsesKnownTopicsAndReleasesOnlyOwnedServices() throws Exception {
        String output = source("HudOutputCoordinator.java");
        assertTrue(output.contains("client.sendToTopic(packet.topicId, packet.payload)"));
        assertTrue(output.contains("if (result == RESULT_OK) hudCheckOwnedServices.add(serviceId)"));
        assertTrue(output.contains("HudCheckPayload.clearAuxiliaryPackets()"));
        assertTrue(output.contains("hudCheckPendingClearTopics.addAll(hudCheckActiveTopics)"));
        assertTrue(output.contains("new ArrayList<>(hudCheckOwnedServices)"));
        assertTrue(output.contains("DEFAULT_INTERVAL_MS = 1000L"));
        assertTrue(output.contains("DIRECT_INTERVAL_MS = 50L"));
    }

    private static String source(String file) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/java/com/bydhud/app/" + file);
        if (!Files.isRegularFile(path)) path = root.resolve("src/main/java/com/bydhud/app/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
