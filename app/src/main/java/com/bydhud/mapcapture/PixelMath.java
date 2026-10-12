package com.bydhud.mapcapture;

import java.nio.ByteBuffer;

/** Shared pure-Java pixel conversion, also exercised by the build self-check. */
public final class PixelMath {
    public static int[] size(int width, int height, int maxEdge) {
        if (maxEdge < 1) throw new IllegalArgumentException("maxEdge");
        if (width < 1 || height < 1) throw new IllegalArgumentException("dimensions");
        double scale = Math.min(1.0, (double) maxEdge / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }
    public static int[] rgba(ByteBuffer bytes, int width, int height, int outWidth, int outHeight) {
        int[] result = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) for (int x = 0; x < outWidth; x++) {
            int row = height - 1 - (int) ((long) y * height / outHeight);
            int i = (row * width + (int) ((long) x * width / outWidth)) * 4;
            result[y * outWidth + x] = ((bytes.get(i + 3) & 255) << 24) | ((bytes.get(i) & 255) << 16)
                    | ((bytes.get(i + 1) & 255) << 8) | (bytes.get(i + 2) & 255);
        }
        return result;
    }
}
