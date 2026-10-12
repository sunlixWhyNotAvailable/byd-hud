package com.bydhud.mapcapture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public final class CapturePayloadPolicyTest {
    @Test public void pixelConversionPreservesOrientationScalingAndNoUpscale() {
        ByteBuffer pixels = ByteBuffer.wrap(new byte[]{
                (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255,
                (byte) 255, (byte) 255});
        assertArrayEquals(new int[]{0xff0000ff, 0xffffffff, 0xffff0000, 0xff00ff00},
                PixelMath.rgba(pixels, 2, 2, 2, 2));
        assertArrayEquals(new int[]{320, 180}, PixelMath.size(1920, 1080, 320));
        assertArrayEquals(new int[]{100, 50}, PixelMath.size(100, 50, 320));
        assertArrayEquals(new int[]{1920, 990}, PixelMath.size(1920, 990, 1920));
        assertArrayEquals(new int[]{800, 480}, PixelMath.size(800, 480, 1920));
    }

    @Test public void captureLeaseExpiresRenewsAndStops() {
        CaptureLease lease = new CaptureLease();
        assertFalse(lease.active(0));
        lease.update(true, 100, 6000);
        assertTrue(lease.active(6099));
        assertFalse(lease.active(6100));
        lease.update(true, 5000, 6000);
        assertTrue(lease.active(10999));
        lease.update(false, 5001, 6000);
        assertFalse(lease.active(5001));
    }

    @Test public void capturePollingBoundsPayloadAndKeepsControlHeartbeat() {
        assertEquals(1280, CapturePollPolicy.outputEdge(1280, true));
        assertEquals(1920, CapturePollPolicy.outputEdge(99999, true));
        assertEquals(6000L, CapturePollPolicy.bounded(-1, 6000, 1000, 30000));
        assertEquals(200L, CapturePollPolicy.interval(true, 200L));
        assertEquals(1000L, CapturePollPolicy.interval(true, CapturePollPolicy.DEFAULT_MS));
        assertEquals(1000L, CapturePollPolicy.interval(false, 100L));
        for (long invalid : new long[]{Long.MIN_VALUE, -1L, 0L}) {
            assertEquals(1000L, CapturePollPolicy.interval(true, invalid));
        }
        for (long request : new long[]{1L, 200L, 1000L}) {
            assertEquals(request, CapturePollPolicy.interval(true, request));
        }
        assertEquals(1000L, CapturePollPolicy.interval(true, 5000L));
        assertEquals(1000L, CapturePollPolicy.interval(true, Long.MAX_VALUE));
    }

    @Test public void carCapturePolicyDoesNotReviveVisibleOrDisposedSurfaces() {
        assertTrue(CarCapturePolicy.mayAttach(true, true, false, true));
        assertFalse(CarCapturePolicy.mayAttach(true, true, true, true));
        assertTrue(CarCapturePolicy.mayAttach(false, true, false, false));
        assertFalse(CarCapturePolicy.mayAttach(true, true, false, false));
        assertFalse(CarCapturePolicy.mayAttach(false, false, false, false));
        assertNull(CarCapturePolicy.size(0, 0, null));
        int[] last = {1920, 1080};
        assertSame(last, CarCapturePolicy.size(0, 0, last));
        assertArrayEquals(new int[]{1280, 720}, CarCapturePolicy.size(1280, 720, last));
    }
}
