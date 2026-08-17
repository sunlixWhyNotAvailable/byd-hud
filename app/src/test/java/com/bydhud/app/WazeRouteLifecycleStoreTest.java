package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WazeRouteLifecycleStoreTest {
    @Test
    public void V2StructuralCapabilityDoesNotRequireLegacySignaturePermission() {
        assertEquals(true, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                WazeRouteLifecycleStore.V2_PROTOCOL_VERSION, false));
        assertEquals(true, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                WazeRouteLifecycleStore.V2_PROTOCOL_VERSION, true));
        assertEquals(true, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                0, true));
        assertEquals(false, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                0, false));
        assertEquals(true, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                1, true));
        assertEquals(false, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                1, false));
        assertEquals(false, WazeRouteLifecycleStore.isBridgeCapabilitySupportedForTest(
                3, true));
    }

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

    @Test
    public void speedEventsRequireActiveMatchingGenerationAndOwnWatermark() {
        assertEquals("inactive_route", WazeRouteLifecycleStore.speedEventDecision(
                false, 7L, 7L, 100L, 7L, 101L, 200L));
        assertEquals("generation_mismatch", WazeRouteLifecycleStore.speedEventDecision(
                true, 7L, 7L, 100L, 8L, 101L, 200L));
        assertEquals("stale_timestamp", WazeRouteLifecycleStore.speedEventDecision(
                true, 7L, 7L, 100L, 7L, 100L, 200L));
        assertEquals("accept", WazeRouteLifecycleStore.speedEventDecision(
                true, 7L, 6L, 500L, 7L, 101L, 200L));
        assertEquals("accept", WazeRouteLifecycleStore.speedEventDecision(
                true, 7L, 7L, 100L, 7L, 101L, 200L));
    }

    @Test
    public void terminalFenceSurvivesNonterminalInactiveEventsUntilFreshRoute() {
        WazeRouteLifecycleStore.Snapshot terminal = new WazeRouteLifecycleStore.Snapshot(
                false, 100L, 0L, 7L, 0, 7L);

        assertEquals("terminal_fence",
                WazeRouteLifecycleStore.snapshotDecision(terminal, true, 7L));
        assertEquals("generation_regression",
                WazeRouteLifecycleStore.snapshotDecision(terminal, true, 6L));
        assertEquals("generation_regression",
                WazeRouteLifecycleStore.snapshotDecision(terminal, true, 0L));
        assertEquals("generation_regression",
                WazeRouteLifecycleStore.bridgeGenerationDecision(terminal, 6L));
        assertEquals("accept",
                WazeRouteLifecycleStore.bridgeGenerationDecision(terminal, 8L));
        WazeRouteLifecycleStore.Snapshot active = new WazeRouteLifecycleStore.Snapshot(
                true, 100L, 0L, 7L, 0);
        assertEquals("generation_regression",
                WazeRouteLifecycleStore.bridgeGenerationDecision(active, 6L));
        assertEquals("accept",
                WazeRouteLifecycleStore.bridgeGenerationDecision(active, 0L));
        assertFalse(WazeRouteLifecycleStore.shouldSeedSnapshot(active, 6L));
        assertFalse(WazeRouteLifecycleStore.shouldSeedSnapshot(active, 0L));
        WazeRouteLifecycleStore.Snapshot unknown = new WazeRouteLifecycleStore.Snapshot(
                false, 0L, 0L, 0L, 0);
        assertTrue(WazeRouteLifecycleStore.shouldSeedSnapshot(unknown, 0L));
        WazeRouteLifecycleStore.Snapshot causalUnknown =
                new WazeRouteLifecycleStore.Snapshot(false, 100L, 0L, 0L, 0);
        assertFalse(WazeRouteLifecycleStore.shouldSeedSnapshot(causalUnknown, 0L));
        assertTrue(WazeRouteLifecycleStore.shouldSeedSnapshot(causalUnknown, 1L));
        assertTrue(WazeRouteLifecycleStore.terminalFenceBlocks(
                terminal, 6L, false, WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertTrue(WazeRouteLifecycleStore.terminalFenceBlocks(
                terminal, 0L, false, WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertEquals(7L, WazeRouteLifecycleStore.terminalFenceAfterEvent(
                terminal, false, false, 7L, 7L, false,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertEquals(7L, WazeRouteLifecycleStore.terminalFenceAfterEvent(
                terminal, true, false, 7L, 7L, false,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertEquals(Long.MIN_VALUE, WazeRouteLifecycleStore.terminalFenceAfterEvent(
                terminal, true, false, 7L, 7L, true, 4));
        assertEquals(Long.MIN_VALUE, WazeRouteLifecycleStore.terminalFenceAfterEvent(
                terminal, true, false, 8L, 8L, false,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE));
    }
}
