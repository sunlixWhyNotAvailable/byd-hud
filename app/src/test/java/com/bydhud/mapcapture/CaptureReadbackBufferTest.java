package com.bydhud.mapcapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.nio.ByteBuffer;
import org.junit.Test;

public final class CaptureReadbackBufferTest {
    @Test public void reusesMatchingSizeStorageAndReplacesOnResize() {
        CaptureReadbackBuffer pool = new CaptureReadbackBuffer();
        ByteBuffer first = pool.acquire(4, 2);
        assertEquals(32, first.capacity());
        first.position(12);
        assertSame(first, pool.acquire(4, 2));
        assertEquals(0, first.position());

        ByteBuffer resized = pool.acquire(2, 2);
        assertNotSame(first, resized);
        assertEquals(16, resized.capacity());
        pool.release();
        assertNotSame(resized, pool.acquire(2, 2));
    }
}
