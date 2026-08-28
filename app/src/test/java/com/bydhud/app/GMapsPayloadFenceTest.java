package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class GMapsPayloadFenceTest {
    @Test
    public void exactEarlierPayloadIsRejectedAfterProgress() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();

        assertTrue(fence.accepts(0, digest("A0")));
        assertTrue(fence.accepts(1, digest("B1")));
        assertTrue(fence.accepts(0, digest("C0")));
        assertFalse(fence.accepts(0, digest("A0")));
    }

    @Test
    public void previousPayloadIsRejectedRegardlessOfReportedStepIndex() {
        for (int revisitIndex : new int[] {0, 1, 2}) {
            GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();
            String previous = digest("A0");

            assertTrue(fence.accepts(0, previous));
            assertTrue(fence.accepts(1, digest("B1")));
            assertFalse(fence.accepts(revisitIndex, previous));
        }
    }

    @Test
    public void immediateDuplicatePayloadIsAccepted() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();
        String payload = digest("A0");

        assertTrue(fence.accepts(1, payload));
        assertTrue(fence.accepts(0, payload));
        assertEquals(1, fence.size());
        assertEquals(1, fence.lastAcceptedCurrentStepIndex());
    }

    @Test
    public void differentLowerPayloadRemainsValidReroute() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();

        assertTrue(fence.accepts(1, digest("B1")));
        assertTrue(fence.accepts(0, digest("C0")));
        assertEquals(0, fence.lastAcceptedCurrentStepIndex());
    }

    @Test
    public void resetAllowsPayloadFromNewGeneration() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();
        String latest = digest("B1");

        assertTrue(fence.accepts(0, digest("A0")));
        assertTrue(fence.accepts(1, latest));
        fence.reset();

        assertEquals(0, fence.size());
        assertTrue(fence.accepts(1, latest));
        assertEquals(1, fence.size());
    }

    @Test
    public void historyEvictsOldestPayloadAtBound() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();
        String oldest = digest("payload-0");

        assertTrue(fence.accepts(0, oldest));
        for (int index = 1; index <= GMapsDirectChannel.MAX_ROUTE_PAYLOAD_HISTORY; index++) {
            assertTrue(fence.accepts(index, digest("payload-" + index)));
        }

        assertEquals(GMapsDirectChannel.MAX_ROUTE_PAYLOAD_HISTORY, fence.size());
        assertTrue(fence.accepts(0, oldest));
    }

    @Test
    public void missingIndexOrDigestFailsOpen() {
        GMapsDirectChannel.RoutePayloadResurrectionFence fence = fence();
        String oldPayload = digest("A0");

        assertTrue(fence.accepts(0, oldPayload));
        assertTrue(fence.accepts(1, digest("B1")));
        assertTrue(fence.accepts(null, oldPayload));
        assertTrue(fence.accepts(-1, oldPayload));
        assertTrue(fence.accepts(2, null));
        assertTrue(fence.accepts(2, ""));
        assertTrue(fence.accepts(2, "unavailable"));
        assertEquals(2, fence.size());
        assertEquals(1, fence.lastAcceptedCurrentStepIndex());
        assertFalse(fence.accepts(0, oldPayload));

        fence.reset();
        assertTrue(fence.accepts(0, oldPayload));
        assertTrue(fence.accepts(1, null));
        assertEquals(0, fence.lastAcceptedCurrentStepIndex());
        assertTrue(fence.accepts(0, oldPayload));

        assertTrue(fence.accepts(null, digest("B1")));
        assertEquals(0, fence.lastAcceptedCurrentStepIndex());
    }

    private static GMapsDirectChannel.RoutePayloadResurrectionFence fence() {
        return new GMapsDirectChannel.RoutePayloadResurrectionFence();
    }

    private static String digest(String payload) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02X", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
