package com.bydhud.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class SpeedLimitPng {
    static final int STANDALONE_SIZE_PX = 96;
    private static final int MAX_SIGN_CACHE_ENTRIES = 256;
    private static final Map<Integer, byte[]> CACHE =
            new LinkedHashMap<Integer, byte[]>(MAX_SIGN_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                    return size() > MAX_SIGN_CACHE_ENTRIES;
                }
            };
    private static byte[] lastCompositeBase;
    private static int lastCompositeSpeed;
    private static int lastCompositeSize;
    private static boolean lastCompositeLaneField;
    private static byte[] lastCompositeResult;

    private SpeedLimitPng() {
    }

    static synchronized byte[] get(int speedLimit) {
        return get(speedLimit, STANDALONE_SIZE_PX);
    }

    static synchronized byte[] get(int speedLimit, int size) {
        if (speedLimit <= 0 || speedLimit > 300 || size <= 0) return new byte[0];
        int cacheKey = speedLimit * 1000 + size;
        byte[] cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return new byte[0];
        }
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setColor(Color.rgb(208, 0, 0));
            float center = size / 2f;
            canvas.drawCircle(center, center, size * 0.48f, red);
            Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(Color.WHITE);
            canvas.drawCircle(center, center, size * 0.34f, white);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setTextSize(size * (speedLimit >= 100 ? 0.36f : 0.44f));
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(String.valueOf(speedLimit), center,
                    center - (metrics.ascent + metrics.descent) / 2f, text);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return new byte[0];
            byte[] png = output.toByteArray();
            CACHE.put(cacheKey, png);
            return png;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return new byte[0];
        } finally {
            bitmap.recycle();
        }
    }

    /** Returns null when the base/sign cannot be safely decoded or encoded. */
    static synchronized byte[] composite(
            int speedLimit, byte[] basePng, int size, boolean laneField) {
        if (basePng == null || basePng.length == 0) return null;
        if (lastCompositeResult != null && isSameCompositeKey(
                lastCompositeBase, lastCompositeSpeed, lastCompositeSize,
                lastCompositeLaneField, basePng, speedLimit, size, laneField)) {
            return lastCompositeResult;
        }
        byte[] signPng = get(speedLimit, size);
        if (signPng.length == 0) return null;
        Bitmap base = null;
        Bitmap sign = null;
        Bitmap output = null;
        try {
            base = BitmapFactory.decodeByteArray(basePng, 0, basePng.length);
            sign = BitmapFactory.decodeByteArray(signPng, 0, signPng.length);
            if (base == null || sign == null) return null;
            int width = compositeCanvasSize(base.getWidth(), size);
            int height = compositeCanvasSize(base.getHeight(), size);
            int y = compositeBottomY(height, size);
            int x;
            if (laneField) {
                int[] runs = occupiedColumnRuns(base);
                if (runs.length < 6) {
                    x = rightX(width, size);
                } else {
                    int[] overlaps = new int[runs.length / 2 - 1];
                    for (int gap = 0; gap < overlaps.length; gap++) {
                        int candidate = laneCandidateX(width, size, runs, gap);
                        overlaps[gap] = opaqueOverlap(base, candidate, y, size);
                    }
                    x = chooseLaneX(width, size, runs, overlaps);
                }
            } else {
                int left = 1;
                int right = rightX(width, size);
                x = chooseManeuverX(width, size,
                        opaqueOverlap(base, left, y, size),
                        opaqueOverlap(base, right, y, size));
            }
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(base, 0f, 0f, null);
            canvas.drawBitmap(sign, x, y, null);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!output.compress(Bitmap.CompressFormat.PNG, 100, bytes)) return null;
            byte[] result = bytes.toByteArray();
            lastCompositeBase = basePng.clone();
            lastCompositeSpeed = speedLimit;
            lastCompositeSize = size;
            lastCompositeLaneField = laneField;
            lastCompositeResult = result;
            return result;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (output != null) output.recycle();
            if (sign != null) sign.recycle();
            if (base != null) base.recycle();
        }
    }

    static boolean isSameCompositeKey(
            byte[] cachedBase, int cachedSpeed, int cachedSize, boolean cachedLaneField,
            byte[] base, int speed, int size, boolean laneField) {
        return cachedSpeed == speed && cachedSize == size
                && cachedLaneField == laneField && Arrays.equals(cachedBase, base);
    }

    static int chooseManeuverX(
            int canvasWidth, int size, int leftOverlap, int rightOverlap) {
        return leftOverlap < rightOverlap ? 1 : rightX(canvasWidth, size);
    }

    static int compositeCanvasSize(int baseSize, int signSize) {
        return Math.max(baseSize, signSize + 2);
    }

    static int compositeBottomY(int canvasHeight, int signSize) {
        return canvasHeight - signSize - 1;
    }

    static int[] occupiedColumnRuns(boolean[] occupied) {
        if (occupied == null || occupied.length == 0) return new int[0];
        int runCount = 0;
        for (int column = 0; column < occupied.length; column++) {
            if (occupied[column] && (column == 0 || !occupied[column - 1])) runCount++;
        }
        int[] runs = new int[runCount * 2];
        int next = 0;
        for (int column = 0; column < occupied.length; column++) {
            if (occupied[column] && (column == 0 || !occupied[column - 1])) {
                runs[next++] = column;
            }
            if (occupied[column]
                    && (column == occupied.length - 1 || !occupied[column + 1])) {
                runs[next++] = column;
            }
        }
        return runs;
    }

    static int chooseLaneX(int canvasWidth, int size, int[] runs, int[] overlaps) {
        if (runs == null || runs.length < 6 || overlaps == null
                || overlaps.length < runs.length / 2 - 1) {
            return rightX(canvasWidth, size);
        }
        int bestX = rightX(canvasWidth, size);
        int bestOverlap = Integer.MAX_VALUE;
        for (int gap = 0; gap < runs.length / 2 - 1; gap++) {
            int candidate = laneCandidateX(canvasWidth, size, runs, gap);
            if (overlaps[gap] < bestOverlap
                    || overlaps[gap] == bestOverlap && candidate >= bestX) {
                bestOverlap = overlaps[gap];
                bestX = candidate;
            }
        }
        return bestX;
    }

    private static int[] occupiedColumnRuns(Bitmap bitmap) {
        boolean[] occupied = new boolean[bitmap.getWidth()];
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 16) {
                    occupied[x] = true;
                    break;
                }
            }
        }
        return occupiedColumnRuns(occupied);
    }

    private static int laneCandidateX(int canvasWidth, int size, int[] runs, int gap) {
        int midpoint = (runs[gap * 2 + 1] + runs[gap * 2 + 2]) / 2;
        return Math.max(1, Math.min(rightX(canvasWidth, size), midpoint - size / 2));
    }

    private static int opaqueOverlap(Bitmap base, int x, int y, int size) {
        int overlap = 0;
        float center = size / 2f;
        float radiusSquared = size * size * 0.48f * 0.48f;
        for (int localY = 0; localY < size; localY++) {
            int baseY = y + localY;
            if (baseY < 0 || baseY >= base.getHeight()) continue;
            float dy = localY + 0.5f - center;
            for (int localX = 0; localX < size; localX++) {
                int baseX = x + localX;
                if (baseX < 0 || baseX >= base.getWidth()) continue;
                float dx = localX + 0.5f - center;
                if (dx * dx + dy * dy <= radiusSquared
                        && Color.alpha(base.getPixel(baseX, baseY)) > 16) {
                    overlap++;
                }
            }
        }
        return overlap;
    }

    private static int rightX(int canvasWidth, int size) {
        return Math.max(1, canvasWidth - size - 1);
    }
}
