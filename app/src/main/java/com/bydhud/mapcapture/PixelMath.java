package com.bydhud.mapcapture;

import java.nio.ByteBuffer;

/** Shared pure-Java pixel conversion, also exercised by the build self-check. */
public final class PixelMath {
    public static int[] size(int width, int height) {
        return size(width, height, 320);
    }
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
    public static void main(String[] args) {
        ByteBuffer pixels = ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255, 0,(byte)255,0,(byte)255,
                0,0,(byte)255,(byte)255, (byte)255,(byte)255,(byte)255,(byte)255});
        int[] result = rgba(pixels, 2, 2, 2, 2);
        if (result[0] != 0xff0000ff || result[1] != 0xffffffff || result[2] != 0xffff0000 || result[3] != 0xff00ff00)
            throw new AssertionError("RGBA / vertical flip");
        int[] size = size(1920, 1080);
        if (size[0] != 320 || size[1] != 180 || size(100, 50)[0] != 100) throw new AssertionError("scaling");
        int[] full = size(1920, 990, 1920);
        if (full[0] != 1920 || full[1] != 990 || size(800, 480, 1920)[0] != 800)
            throw new AssertionError("full source viewport / no upscale");
        System.out.println("Pixel conversion, orientation, downscale and no-upscale: PASS");
    }
}
