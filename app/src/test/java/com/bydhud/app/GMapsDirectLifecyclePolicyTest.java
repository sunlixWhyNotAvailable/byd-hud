package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GMapsDirectLifecyclePolicyTest {
    @Test
    public void routeStateFencesRejectOlderSessionProducerAndRoute() {
        assertFalse(NavHudLiveSender.acceptsGMapsRouteStateFence(
                8L, 4L, 12L, 7L, 4L, 12L));
        assertFalse(NavHudLiveSender.acceptsGMapsRouteStateFence(
                8L, 4L, 12L, 8L, 3L, 12L));
        assertFalse(NavHudLiveSender.acceptsGMapsRouteStateFence(
                8L, 4L, 12L, 8L, 4L, 11L));
        assertTrue(NavHudLiveSender.acceptsGMapsRouteStateFence(
                8L, 4L, 12L, 8L, 5L, 0L));
        assertTrue(NavHudLiveSender.acceptsGMapsRouteStateFence(
                8L, 4L, 12L, 9L, 5L, 13L));
    }

}
