package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import androidx.car.app.navigation.model.Lane;
import androidx.car.app.navigation.model.LaneDirection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class DirectChannelContractTest {
    @Test
    public void producerEpochChangeIsAcceptedOnlyThroughHello() {
        assertTrue(GMapsDirectChannel.acceptsProducerEpochForTest(
                1, 4L, 5L));
        assertFalse(GMapsDirectChannel.acceptsProducerEpochForTest(
                3, 4L, 5L));
        assertTrue(GMapsDirectChannel.acceptsProducerEpochForTest(
                3, 4L, 4L));
        assertFalse(GMapsDirectChannel.acceptsProducerEpochForTest(
                3, 4L, -1L));
        assertTrue(GMapsDirectChannel.acceptsProducerEpochForTest(
                1, 4L, 6L));
        assertFalse(GMapsDirectChannel.acceptsProducerEpochForTest(
                1, 4L, 3L));
        assertFalse(GMapsDirectChannel.acceptsProducerEpochForTest(
                1, 4L, -1L));
    }

    @Test
    public void helloLivenessRequiresCurrentActiveRoute() {
        assertTrue(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, false, true, true, 9L, 9L));
        assertTrue(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, false, true, true, -1L, 9L));
        assertFalse(GMapsDirectChannel.acceptsHelloLivenessForTest(
                true, false, false, true, 9L, 9L));
        assertFalse(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, true, true, true, 8L, 9L));
        assertFalse(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, false, false, true, 9L, 9L));
        assertFalse(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, false, true, false, 9L, 9L));
        assertFalse(GMapsDirectChannel.acceptsHelloLivenessForTest(
                false, false, true, true, 10L, 9L));
    }

    @Test
    public void routeGenerationRejectsStaleTerminalEventsButAllowsMissedStartFrame() {
        assertFalse(GMapsDirectChannel.acceptsRouteGenerationForTest(
                4, 9L, 8L, true));
        assertTrue(GMapsDirectChannel.acceptsRouteGenerationForTest(
                4, 9L, 10L, true));
        assertFalse(GMapsDirectChannel.acceptsRouteGenerationForTest(
                6, 9L, 8L, true));
        assertTrue(GMapsDirectChannel.acceptsRouteGenerationForTest(
                3, 9L, 10L, true));
        assertTrue(GMapsDirectChannel.acceptsRouteGenerationForTest(
                2, -1L, 3L, false));
        assertFalse(GMapsDirectChannel.acceptsRouteGenerationForTest(
                4, -1L, 3L, false));
    }

    @Test
    public void newerTerminalStopCanAdvanceObservedRouteFence() {
        assertTrue(GMapsDirectChannel.acceptsRouteGenerationForTest(
                4, 9L, 10L, true));
        assertTrue(GMapsDirectChannel.acceptsRouteGenerationForTest(
                2, 10L, 10L, true));
    }

    @Test
    public void terminalOrLossGenerationSupersedesOwnerAndRejectsOldCallbacks() {
        assertEquals(HudOutputCoordinator.DirectOwnerDecision.ADVANCE,
                HudOutputCoordinator.directOwnerDecision("com.waze", 7L, "com.waze", 8L));
        assertEquals(HudOutputCoordinator.DirectOwnerDecision.REJECT,
                HudOutputCoordinator.directOwnerDecision("com.waze", 8L, "com.waze", 7L));
        assertEquals(HudOutputCoordinator.DirectOwnerDecision.REJECT,
                HudOutputCoordinator.directOwnerDecision(
                        "com.waze", 8L, GMapsDirectChannel.OWNER_PACKAGE, 9L));
    }

    @Test
    public void promotionReplacesOnlyDormantCrossNavigatorOwnership() {
        assertEquals(HudOutputCoordinator.DirectPromotionDecision.REPLACE_DORMANT,
                HudOutputCoordinator.directPromotionDecision(
                        false, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                        "com.waze", 8L));
        assertEquals(HudOutputCoordinator.DirectPromotionDecision.REPLACE_DORMANT,
                HudOutputCoordinator.directPromotionDecision(
                        false, "com.waze", 8L,
                        GMapsDirectChannel.OWNER_PACKAGE, 9L));
        assertEquals(HudOutputCoordinator.DirectPromotionDecision.REJECT,
                HudOutputCoordinator.directPromotionDecision(
                        true, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                        "com.waze", 8L));
        assertEquals(HudOutputCoordinator.DirectPromotionDecision.CLAIM,
                HudOutputCoordinator.directPromotionDecision(
                        false, "", Long.MIN_VALUE, "com.waze", 8L));
    }

    @Test
    public void healthSignalsRequireExactOwnerAndSwitchClearsDormantPackage() {
        assertFalse(HudOutputCoordinator.matchesDirectOwnerForTest(
                "", Long.MIN_VALUE, GMapsDirectChannel.OWNER_PACKAGE, 7L));
        assertFalse(HudOutputCoordinator.matchesDirectOwnerForTest(
                GMapsDirectChannel.OWNER_PACKAGE, 7L, "com.waze", 8L));
        assertTrue(HudOutputCoordinator.matchesDirectOwnerForTest(
                GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 7L));

        assertTrue(HudOutputCoordinator.shouldInvalidateDormantDirectOwnerForTest(
                false, GMapsDirectChannel.OWNER_PACKAGE,
                GMapsDirectChannel.OWNER_PACKAGE));
        assertFalse(HudOutputCoordinator.shouldInvalidateDormantDirectOwnerForTest(
                true, GMapsDirectChannel.OWNER_PACKAGE,
                GMapsDirectChannel.OWNER_PACKAGE));
        assertFalse(HudOutputCoordinator.shouldInvalidateDormantDirectOwnerForTest(
                false, GMapsDirectChannel.OWNER_PACKAGE, "com.waze"));
    }

    @Test
    public void producerLossQueuesOneClearAndLeaseExpiresOnlyAtDeadline() {
        assertTrue(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, false, false));
        assertFalse(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, true, false));
        assertFalse(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, false, true));
        assertFalse(HudOutputCoordinator.isDirectLeaseExpired(15_000L, 14_999L));
        assertTrue(HudOutputCoordinator.isDirectLeaseExpired(15_000L, 15_000L));
    }

    @Test
    public void producerReplacementClearsOnlyTheOlderSelectedOwnerSession() {
        assertTrue(HudOutputCoordinator.shouldClearForSupersedingDirectSession(
                true, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldClearForSupersedingDirectSession(
                false, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldClearForSupersedingDirectSession(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldClearForSupersedingDirectSession(
                true, "com.waze", 7L, GMapsDirectChannel.OWNER_PACKAGE, 8L));

        assertTrue(HudOutputCoordinator.shouldPreservePendingLossClearOnAdvance(
                true, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldPreservePendingLossClearOnAdvance(
                false, GMapsDirectChannel.OWNER_PACKAGE, 7L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldPreservePendingLossClearOnAdvance(
                true, GMapsDirectChannel.OWNER_PACKAGE, 8L,
                GMapsDirectChannel.OWNER_PACKAGE, 8L));
        assertFalse(HudOutputCoordinator.shouldPreservePendingLossClearOnAdvance(
                true, "com.waze", 7L, GMapsDirectChannel.OWNER_PACKAGE, 8L));
    }

    @Test
    public void gmapsMapsSupportedDirectLanes() {
        List<Object> raw = Arrays.asList(
                lane(arrow(1, 0, true)),
                lane(arrow(3, 1, true)),
                lane(arrow(5, 2, false)));

        List<DirectTbtFrame.Lane> lanes = GMapsDirectChannel.mapLanesForTest(raw);

        assertEquals(3, lanes.size());
        assertEquals(9, lanes.get(0).getDirection());
        assertEquals(2, lanes.get(1).getDirection());
        assertEquals(3, lanes.get(2).getDirection());
        assertTrue(lanes.get(0).isRecommended());
        assertFalse(lanes.get(2).isRecommended());
    }

    @Test
    public void wazeLaneProjectionComposesDirectionsAndRecommendations() {
        assertArrayEquals(new int[]{4, 0},
                wazeLaneCodes(
                        new int[]{2, 6}, new boolean[]{true, false}));
        assertArrayEquals(new int[]{7, 7},
                wazeLaneCodes(
                        new int[]{2, 5, 6}, new boolean[]{true, true, true}));
        assertArrayEquals(new int[]{11, 1},
                wazeLaneCodes(
                        new int[]{9, 5}, new boolean[]{false, true}));
        assertArrayEquals(new int[]{11, 11},
                wazeLaneCodes(
                        new int[]{5, 9}, new boolean[]{true, true}));
        assertArrayEquals(new int[]{0, 0},
                wazeLaneCodes(
                        new int[]{Integer.MIN_VALUE}, new boolean[]{true}));
    }

    @Test
    public void capturedWazeTripleLaneKeepsUnselectedUturnAndSelectedLeft() {
        // SL06 2026-08-26: 5:true|2:false|9:false. The PNG is not the input contract.
        assertArrayEquals(new int[]{16, 1},
                wazeLaneCodes(
                        new int[]{5, 2, 9}, new boolean[]{true, false, false}));
        assertArrayEquals(new int[]{16, 1},
                wazeLaneCodes(
                        new int[]{9, 2, 5}, new boolean[]{false, false, true}));
        assertArrayEquals(new int[]{16, 255},
                wazeLaneCodes(
                        new int[]{5, 2, 9}, new boolean[]{false, false, false}));
        assertArrayEquals(new int[]{16, 11},
                wazeLaneCodes(
                        new int[]{5, 2, 9}, new boolean[]{true, false, true}));
    }

    @Test
    public void wazeLaneVocabularyPreservesEveryRepresentableSelectedSubset() {
        // Indexed by S=1, L=2, R=4, UL=8, UR=16; not by AndroidX shape IDs.
        int[] codes = {-1, 0, 1, 2, 3, 4, 6, 7, 5, 9, 11, 16, 17, 19, 18, -1,
                8, 10, 20, -1, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        int[] shapes = {2, 5, 6, 9, 10};
        for (int mask = 1; mask < codes.length; mask++) {
            assertEquals("mask=" + mask, codes[mask],
                    DirectTbtFrame.Lane.instrumentCodeForMask(mask));
            int[] laneShapes = new int[Integer.bitCount(mask)];
            for (int selected = 0; selected <= mask; selected++) {
                if ((selected & mask) != selected) continue;
                boolean[] recommended = new boolean[laneShapes.length];
                int index = 0;
                for (int bit = 0; bit < shapes.length; bit++) {
                    if ((mask & (1 << bit)) == 0) continue;
                    laneShapes[index] = shapes[bit];
                    recommended[index++] = (selected & (1 << bit)) != 0;
                }
                int recommendation = codes[mask] < 0 || codes[selected] < 0
                        ? 255 : codes[selected];
                assertArrayEquals("mask=" + mask + ", selected=" + selected,
                        new int[]{codes[mask], recommendation},
                        wazeLaneCodes(laneShapes, recommended));
            }
        }
        assertEquals(-1, DirectTbtFrame.Lane.instrumentCodeForMask(0));
        assertArrayEquals(new int[]{11, 1},
                wazeLaneCodes(new int[]{LaneDirection.SHAPE_SLIGHT_LEFT,
                        LaneDirection.SHAPE_SHARP_LEFT, LaneDirection.SHAPE_U_TURN_LEFT},
                        new boolean[]{false, true, false}));
    }

    @Test
    public void capturedWazeLanesReachRoadInfoAndInstrumentWithoutChangingPng() {
        List<DirectTbtFrame.Lane> lanes = WazeDirectChannel.mapLanes(Arrays.asList(
                wazeLane(new int[]{5, 2, 9}, new boolean[]{true, false, false}),
                wazeLane(new int[]{2}, new boolean[]{false})));
        assertEquals("5:true|2:false|9:false", lanes.get(0).getRawDirections());

        VehicleTbtPublisher.LanePayload instrument = VehicleTbtPublisher.lanePayloadForTest(lanes);
        assertArrayEquals(new int[]{16, 0}, instrument.directions);
        assertArrayEquals(new int[]{1, 255}, instrument.recommendations);
        assertTrue(InstrumentProxyContract.validGuidance(3, 420, "Road",
                instrument.directions, instrument.recommendations));
        assertEquals(71, InstrumentNavigationProxyService.laneGuideValueForTest(
                instrument.directions[0], instrument.recommendations[0]));

        DirectTbtFrame frame = new DirectTbtFrame(11, 3, 9, 420, "Road", "Turn right", "Road",
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, lanes,
                DirectTbtFrame.AlertOverlay.inactive());
        assertArrayEquals(new byte[]{
                0x0a, 0x30, 0x10, 0x07, 0x28, 0x02, 0x30, 0x06,
                0x3a, 0x03, 0x04, 0x05, 0x06, 0x42, 0x03, 0x01, 0x02, 0x03,
                0x48, (byte) 0xa4, 0x03, 0x52, 0x04, 0x52, 0x6f, 0x61, 0x64,
                (byte) 0x80, 0x01, 0x02, (byte) 0xd2, 0x01, 0x00, (byte) 0xe0, 0x01, 0x09,
                (byte) 0xea, 0x01, 0x0b, 0x31, 0x36, 0x2c, 0x31, 0x7c,
                0x30, 0x2c, 0x32, 0x35, 0x35, 0x7c},
                DirectTbtPayload.build(frame, 7, DirectTbtPayload.Options.ALL));
    }

    @Test
    public void unsupportedWazeLaneDoesNotRenumberItsNeighbors() {
        assertTrue(WazeDirectChannel.mapLanes(Arrays.asList(
                wazeLane(new int[]{2}, new boolean[]{true}),
                wazeLane(new int[]{9, 10}, new boolean[]{false, true}),
                wazeLane(new int[]{6}, new boolean[]{false}))).isEmpty());
    }

    @Test
    public void gmapsBitmapCacheIsAccessOrderedAndBoundedTo64() {
        Map<String, GMapsDirectChannel.ManeuverBitmap> cache =
                GMapsDirectChannel.newManeuverBitmapCache();
        for (int index = 0; index <= GMapsDirectChannel.MAX_MANEUVER_BITMAP_CACHE; index++) {
            cache.put("maneuver-" + index, new GMapsDirectChannel.ManeuverBitmap(
                    "maneuver-" + index, "view", new byte[]{(byte) index}, 1, 1, index));
        }

        assertEquals(GMapsDirectChannel.MAX_MANEUVER_BITMAP_CACHE, cache.size());
        assertFalse(cache.containsKey("maneuver-0"));
        assertTrue(cache.containsKey("maneuver-64"));
    }

    @Test
    public void malformedBundleWorkCannotEscapeMessageBoundary() {
        AtomicReference<Throwable> captured = new AtomicReference<>();

        boolean handled = GMapsDirectChannel.runMessageBoundary(
                () -> { throw new ClassCastException("malformed bundle"); }, captured::set);

        assertTrue(handled);
        assertNotNull(captured.get());
    }

    @Test
    public void maneuverBitmapIsRoadInfoVisualOnly() {
        assertFalse(NavHudLiveSender.shouldDispatchSemanticTbtForDirectReason(
                "maneuver-bitmap"));
        assertFalse(NavHudLiveSender.shouldDispatchSemanticTbtForDirectReason(
                " MANEUVER-BITMAP "));
        assertTrue(NavHudLiveSender.shouldDispatchSemanticTbtForDirectReason(
                "frame"));
    }

    @Test
    public void speedBeforeSyntheticFirstFrameSurvivesUntilExplicitStart() {
        String owner = GMapsDirectChannel.PACKAGE_NAME;
        DirectSpeedLimitStore.clear(owner);
        DirectSpeedLimitStore.update(owner, 50, 50, "km/h", 1L);

        if (NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart("first-frame")) {
            DirectSpeedLimitStore.clear(owner);
        }
        assertTrue(DirectSpeedLimitStore.snapshot(owner).isActive());

        if (NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart("start")) {
            DirectSpeedLimitStore.clear(owner);
        }
        assertFalse(DirectSpeedLimitStore.snapshot(owner).isActive());
    }

    private static Lane wazeLane(int[] shapes, boolean[] selected) {
        Lane.Builder builder = new Lane.Builder();
        for (int i = 0; i < shapes.length; i++) {
            builder.addDirection(LaneDirection.create(shapes[i], selected[i]));
        }
        return builder.build();
    }

    private static int[] wazeLaneCodes(int[] shapes, boolean[] selected) {
        List<DirectTbtFrame.Lane> lanes = WazeDirectChannel.mapLanes(
                Arrays.asList(wazeLane(shapes, selected)));
        return lanes.isEmpty() ? new int[]{-1, 255}
                : new int[]{lanes.get(0).getAmapCode(), lanes.get(0).getAmapRecommendationCode()};
    }

    private static Map<String, Object> lane(Map<String, Object> arrow) {
        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("arrows", new ArrayList<>(Arrays.asList(arrow)));
        return lane;
    }

    private static Map<String, Object> arrow(int shape, int side, boolean recommended) {
        Map<String, Object> arrow = new LinkedHashMap<>();
        arrow.put("shapeEnum", shape);
        arrow.put("shape", "shape-" + shape);
        arrow.put("sideEnum", side);
        arrow.put("side", side == 1 ? "LEFT" : side == 2 ? "RIGHT" : "UNSPECIFIED");
        arrow.put("recommended", recommended);
        return arrow;
    }
}
