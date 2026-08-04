package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class DirectChannelContractTest {
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
    public void producerLossQueuesOneClearAndLeaseExpiresOnlyAtDeadline() {
        assertTrue(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, false, false));
        assertFalse(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, true, false));
        assertFalse(HudOutputCoordinator.shouldQueueDirectLossClear(true, true, false, true));
        assertFalse(HudOutputCoordinator.isDirectLeaseExpired(15_000L, 14_999L));
        assertTrue(HudOutputCoordinator.isDirectLeaseExpired(15_000L, 15_000L));
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
