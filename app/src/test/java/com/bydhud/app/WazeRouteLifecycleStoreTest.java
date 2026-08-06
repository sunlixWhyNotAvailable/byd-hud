package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WazeRouteLifecycleStoreTest {
    @Test
    public void acceptsOnlyNewDeviceElapsedTimestamps() {
        assertEquals("accept", WazeRouteLifecycleStore.eventDecision(0L, 100L, 200L));
        assertEquals("accept", WazeRouteLifecycleStore.eventDecision(100L, 101L, 200L));
        assertEquals("stale_timestamp",
                WazeRouteLifecycleStore.eventDecision(100L, 100L, 200L));
        assertEquals("stale_timestamp",
                WazeRouteLifecycleStore.eventDecision(100L, 99L, 200L));
        assertEquals("invalid_timestamp",
                WazeRouteLifecycleStore.eventDecision(0L, 0L, 200L));
        assertEquals("future_timestamp",
                WazeRouteLifecycleStore.eventDecision(0L, 201L, 200L));
    }

    @Test
    public void bootCountInvalidatesPersistedRouteEvenAfterLongUptime() {
        assertEquals(true, WazeRouteLifecycleStore.shouldInvalidateForBoot(
                8, 9, 10_000L, 20_000L));
        assertEquals(false, WazeRouteLifecycleStore.shouldInvalidateForBoot(
                9, 9, 10_000L, 20_000L));
        assertEquals(true, WazeRouteLifecycleStore.shouldInvalidateForBoot(
                -1, -1, 20_000L, 10_000L));
    }

    @Test
    public void onlyExplicitTerminalReasonsEndAnActiveBridgeRoute() {
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 4));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 8));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 0));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 2));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 3));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 9));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, false, WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertEquals(false, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 1));
        assertEquals(false, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 5));
        assertEquals(false, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 6));
        assertEquals(false, WazeRouteLifecycleStore.resolveBridgeActive(
                true, false, true, 7));
        assertEquals(true, WazeRouteLifecycleStore.resolveBridgeActive(
                false, true, true, 6));
    }
}
