package com.bydhud.app;

import java.util.Base64;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic goldens emitted by the unmodified buildPayload/helpers of Denza Apps
 * HudSomeIpClient.java at 854bc915 (source SHA256
 * 5b7b5119a43b587018eb49ae4551a93ac94d4a1d8ebb2a4f04ea80af46359d75).
 * Reproducer: denza-lab/tools/maps25-compat/denza_packet_reference.py.
 * No owner coordinates, trip or images are included.
 */
public final class DenzaRoadParityTest {
    @Test public void compactPacketMatchesDenzaAppsByteForByte() {
        assertArrayEquals(Base64.getDecoder().decode(
                "CjcQAoABAhiIJyDYBEIDAQIDSFRSCVRlc3Qgcm9hZNIBCDUsMCDQutC82gEJMTAg0LzQuNC94AEC"),
                DirectTbtPayload.prepareDenza(frame(84)).build(2));
    }

    @Test public void completeArPacketMatchesDenzaAppsByteForByte() {
        DenzaArGeometry geometry = new DenzaArGeometry(20, 10, 100, 8, 0,
                "[[10,20,0],[10,20.001,0],[10.001,20.001,0]]", "10,20.001,0", 0);
        assertArrayEquals(Base64.getDecoder().decode(
                "CqEBEAKAAQIYiCcg2ARCAwECA0hUUglUZXN0IHJvYWTSAQg1LDAg0LrQvNoBCTEwINC80LjQveABApkBAAAAAAAAJEChAQAAAAAAADRAqAEIsAFk8gErW1sxMCwyMCwwXSxbMTAsMjAuMDAxLDBdLFsxMC4wMDEsMjAuMDAxLDBdXfoBCzEwLDIwLjAwMSwwgQIAAAAAAAAAAIkCAAAAAAAAAAA="),
                DirectTbtPayload.prepareDenza(frame(84)).build(2, DenzaArFields.encode(geometry)));
    }

    @Test public void clearMatchesDenzaAppsByteForByte() {
        assertArrayEquals(Base64.getDecoder().decode("CgUQAoABAQ=="), DirectTbtPayload.buildDenzaClear());
    }

    @Test public void rawDistanceIsNeverClampedOrReplacedByPresentation() {
        assertEquals(5, DirectTbtPayload.prepareDenza(frame(5)).distanceMeters());
        assertEquals(0, DirectTbtPayload.prepareDenza(frame(0)).distanceMeters());
    }

    @Test public void heldStepDistanceIsWhatField9Carries() {
        DirectTbtPayload.Prepared held = DirectTbtPayload.prepareDenza(frame(84), 70);
        assertEquals(70, held.distanceMeters());
        assertEquals(70, varintField(held.build(2), 9));
        assertEquals(84, varintField(DirectTbtPayload.prepareDenza(frame(84)).build(2), 9));
    }

    /** The last varint value of {@code field} inside the outer RoadInfo message, or -1. */
    static long varintField(byte[] payload, int field) {
        assertEquals(0x0a, payload[0]);
        int[] at = {1};
        long end = varint(payload, at);
        end += at[0];
        long found = -1;
        while (at[0] < end) {
            long tag = varint(payload, at);
            int wireType = (int) (tag & 7);
            if (wireType == 0) {
                long value = varint(payload, at);
                if ((tag >>> 3) == field) found = value;
            } else if (wireType == 1) {
                at[0] += 8;
            } else if (wireType == 2) {
                long length = varint(payload, at);
                at[0] += (int) length;
            } else if (wireType == 5) {
                at[0] += 4;
            } else {
                throw new AssertionError("wire type " + wireType);
            }
        }
        return found;
    }

    private static long varint(byte[] bytes, int[] at) {
        long value = 0;
        int shift = 0;
        while (true) {
            int next = bytes[at[0]++] & 255;
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) return value;
            shift += 7;
        }
    }

    static DirectTbtFrame frame(int distance) {
        return new DirectTbtFrame(8, 3, 99, distance, "Test road", "Right", "Different UI text",
                new byte[]{1, 2, 3}, null, Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive(),
                DirectTbtFrame.TripMetrics.nextStopOnly(new DirectTbtFrame.TravelMetrics(-1, 600, 5000)));
    }
}
