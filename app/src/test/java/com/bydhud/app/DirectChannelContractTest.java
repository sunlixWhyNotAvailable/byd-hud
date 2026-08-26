package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

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
                WazeDirectChannel.laneCodesForTest(
                        new int[]{2, 6}, new boolean[]{true, false}));
        assertArrayEquals(new int[]{7, 7},
                WazeDirectChannel.laneCodesForTest(
                        new int[]{2, 5, 6}, new boolean[]{true, true, true}));
        assertArrayEquals(new int[]{5, 255},
                WazeDirectChannel.laneCodesForTest(
                        new int[]{9, 5}, new boolean[]{false, true}));
        assertArrayEquals(new int[]{5, 5},
                WazeDirectChannel.laneCodesForTest(
                        new int[]{5, 9}, new boolean[]{true, true}));
        assertArrayEquals(new int[]{0, 0},
                WazeDirectChannel.laneCodesForTest(
                        new int[]{Integer.MIN_VALUE}, new boolean[]{true}));
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
