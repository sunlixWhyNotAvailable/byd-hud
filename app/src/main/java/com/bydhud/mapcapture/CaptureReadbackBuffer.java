package com.bydhud.mapcapture;

import java.nio.ByteBuffer;

/** Exclusive scratch storage for one in-flight Waze GL readback. */
final class CaptureReadbackBuffer {
    private ByteBuffer bytes;
    private int width;
    private int height;

    synchronized ByteBuffer acquire(int requestedWidth, int requestedHeight) {
        if (requestedWidth < 1 || requestedHeight < 1) {
            throw new IllegalArgumentException("dimensions");
        }
        long required = (long) requestedWidth * requestedHeight * 4L;
        if (required > Integer.MAX_VALUE) throw new IllegalArgumentException("capacity");
        if (bytes == null || width != requestedWidth || height != requestedHeight
                || bytes.capacity() != (int) required) {
            bytes = ByteBuffer.allocateDirect((int) required);
            width = requestedWidth;
            height = requestedHeight;
        }
        bytes.clear();
        return bytes;
    }

    synchronized void release() {
        bytes = null;
        width = 0;
        height = 0;
    }
}
