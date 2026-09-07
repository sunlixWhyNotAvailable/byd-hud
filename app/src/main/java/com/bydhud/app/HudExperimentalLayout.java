package com.bydhud.app;

/**
 * Pure geometry for the approved FullHud v15 zero-offset live composition.
 *
 * <p>The percentages below are calibrated anchors, not user-facing controls.
 * Keeping them in this small dependency-free class makes the compositor easy
 * to exercise without an Android raster or a vehicle connection.</p>
 */
public final class HudExperimentalLayout {
    public static final int F8_WIDTH_PX = 720;
    public static final int F8_HEIGHT_PX = 48;
    public static final int F7_WIDTH_PX = 960;
    public static final int F7_HEIGHT_PX = 48;
    public static final int F8_WIDTH = F8_WIDTH_PX;
    public static final int F7_WIDTH = F7_WIDTH_PX;

    /** Reference value used to keep the duration row's right edge stable. */
    public static final String DURATION_EDGE_REFERENCE = "12 хв";

    // FullHud v15 baseline anchors. Adjustment offsets are intentionally zero.
    public static final float BASE_ETA_X = 90f;
    public static final float BASE_ETA_DURATION_Y = -2f;
    public static final float BASE_F7_X = 87f;
    public static final float BASE_F7_Y = -1f;
    public static final float BASE_WARNING_X = 8.5f;
    public static final float BASE_WARNING_Y = 50f;
    public static final float BASE_WARNING_ICON_SCALE = 140f;
    public static final float BASE_WARNING_TEXT_SCALE = 160f;
    public static final float BASE_LANE_X = 50f;

    /** Logical source-space height used by the accepted three-row ETA raster. */
    public static final float F8_LOGICAL_HEIGHT = 100f;
    public static final float F7_LOGICAL_HEIGHT = 85f;
    // Names mirror the evidence layout while *_PX above denotes output planes.
    public static final float F8_HEIGHT = F8_LOGICAL_HEIGHT;
    public static final float F7_HEIGHT = F7_LOGICAL_HEIGHT;
    public static final float F7_LEFT = -200f;
    public static final float F7_TOP = 60f;
    public static final float ETA_LOGICAL_HEIGHT = F7_TOP + F7_LOGICAL_HEIGHT;
    public static final float HEIGHT = ETA_LOGICAL_HEIGHT;
    public static final int RASTER_SCALE = 4;
    public static final int TEXT_PADDING = 8;

    /** Bounds the intermediate text bitmap if malformed input is unexpectedly huge. */
    public static final int MAX_ETA_RASTER_WIDTH = 4_096;

    private HudExperimentalLayout() {
    }

    /** Returns the calibrated ETA center in the logical 100%-height space. */
    public static float etaCenterX(float fieldWidth, float fieldHeight) {
        requireField(fieldWidth, fieldHeight);
        return fieldWidth * F8_LOGICAL_HEIGHT / fieldHeight * BASE_ETA_X / 100f;
    }

    /** Returns the center of one of the three source ETA rows. */
    public static float rowCenter(int row) {
        if (row < 0 || row > 2) throw new IllegalArgumentException("row must be 0..2");
        return ETA_LOGICAL_HEIGHT * (row + 0.5f) / 3f;
    }

    /** Returns the internal v15 duration adjustment; public adjustment remains zero. */
    public static float effectiveDurationY() {
        return BASE_ETA_DURATION_Y;
    }

    /** Returns the x-coordinate after applying the field projection. */
    public static float x(boolean lower, float logicalX, float fieldHeight) {
        requirePositive(fieldHeight, "fieldHeight");
        if (!lower) return logicalX * fieldHeight / F8_LOGICAL_HEIGHT;
        float adjusted = logicalX + BASE_F7_X;
        return (adjusted - F7_LEFT) * fieldHeight / F7_LOGICAL_HEIGHT;
    }

    /** Returns the y-coordinate after applying the field projection. */
    public static float y(boolean lower, float logicalY, float fieldHeight) {
        requirePositive(fieldHeight, "fieldHeight");
        if (!lower) return logicalY * fieldHeight / F8_LOGICAL_HEIGHT;
        float adjusted = logicalY + BASE_F7_Y;
        return (adjusted - F7_TOP) * fieldHeight / F7_LOGICAL_HEIGHT;
    }

    /**
     * Projects one edge of the shared ETA raster. The raster is four times the
     * logical source space, matching the accepted v15 calibration.
     */
    public static float valueX(boolean lower, float centerX, float rasterX,
                               int rasterWidth, float fieldHeight) {
        if (rasterWidth <= 0) throw new IllegalArgumentException("rasterWidth must be positive");
        return x(lower, centerX + (rasterX - rasterWidth / 2f) / RASTER_SCALE,
                fieldHeight);
    }

    /** Projects one edge of a shared ETA source row. */
    public static float valueY(boolean lower, int row, float logicalY, float fieldHeight) {
        if (row < 0 || row > 2) throw new IllegalArgumentException("row must be 0..2");
        float adjusted = logicalY + (!lower && row == 1 ? effectiveDurationY() : 0f);
        return y(lower, adjusted, fieldHeight);
    }

    /** Returns the source top for the complete row owned by a plane. */
    public static int rowSourceTop(boolean lower, int row, int bitmapHeight) {
        requireRowAndBitmap(row, bitmapHeight);
        return lower == (row == 2) ? row * bitmapHeight / 3 : 0;
    }

    /** Returns the source bottom for the complete row owned by a plane. */
    public static int rowSourceBottom(boolean lower, int row, int bitmapHeight) {
        requireRowAndBitmap(row, bitmapHeight);
        return lower == (row == 2) ? (row + 1) * bitmapHeight / 3 : 0;
    }

    /** Computes a fixed-capacity width for the shared ETA raster. */
    public static int valueRasterWidth(float outerRowsWidth, float durationCapacity,
                                       float referenceWidth) {
        finite(outerRowsWidth, "outerRowsWidth");
        finite(durationCapacity, "durationCapacity");
        finite(referenceWidth, "referenceWidth");
        if (outerRowsWidth < 0f || durationCapacity < 0f || referenceWidth < 0f) {
            throw new IllegalArgumentException("raster widths must be non-negative");
        }
        float extent = Math.max(outerRowsWidth / 2f,
                Math.max(referenceWidth / 2f, durationCapacity - referenceWidth / 2f));
        float width = 2f * (float) Math.ceil(extent) + TEXT_PADDING;
        if (width > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(1, (int) width);
    }

    /** Returns the right anchor used by the minutes fragment of the duration row. */
    public static float rowRasterX(int row, int rasterWidth, float referenceWidth) {
        if (row < 0 || row > 2) throw new IllegalArgumentException("row must be 0..2");
        if (rasterWidth <= 0 || referenceWidth < 0f || !Float.isFinite(referenceWidth)) {
            throw new IllegalArgumentException("invalid raster anchor");
        }
        return rasterWidth / 2f + (row == 1 ? referenceWidth / 2f : 0f);
    }

    /** Returns the hours fragment's right edge from the fixed minutes anchor. */
    public static float hoursRightX(float minutesRightX, float minutesWidth,
                                    float spaceWidth) {
        finite(minutesRightX, "minutesRightX");
        finite(minutesWidth, "minutesWidth");
        finite(spaceWidth, "spaceWidth");
        return minutesRightX - minutesWidth - spaceWidth;
    }

    /** Returns the fixed calibration top for primary art. */
    public static float primaryArtTop(float fieldHeight) {
        requirePositive(fieldHeight, "fieldHeight");
        return fieldHeight * 0.06f;
    }

    /** Returns the fixed calibration bottom for primary art. */
    public static float primaryArtBottom(float fieldHeight) {
        requirePositive(fieldHeight, "fieldHeight");
        return fieldHeight * 0.94f;
    }

    /** Returns the center x-coordinate for the centered F7 lane art. */
    public static float laneCenterX(float fieldWidth) {
        requirePositive(fieldWidth, "fieldWidth");
        return fieldWidth * BASE_LANE_X / 100f;
    }

    /** Returns the center x-coordinate for the separate F7 warning group. */
    public static float warningCenterX(float fieldWidth) {
        requirePositive(fieldWidth, "fieldWidth");
        return fieldWidth * BASE_WARNING_X / 100f;
    }

    /** Returns an opaque ARGB color without depending on Android's Color class. */
    public static int opaqueColor(int argb) {
        return argb | 0xff000000;
    }

    public static boolean isCanonicalF8(int width, int height) {
        return width == F8_WIDTH_PX && height == F8_HEIGHT_PX;
    }

    public static boolean isCanonicalF7(int width, int height) {
        return width == F7_WIDTH_PX && height == F7_HEIGHT_PX;
    }

    private static void requireField(float width, float height) {
        requirePositive(width, "fieldWidth");
        requirePositive(height, "fieldHeight");
    }

    private static void requireRowAndBitmap(int row, int bitmapHeight) {
        if (row < 0 || row > 2) throw new IllegalArgumentException("row must be 0..2");
        if (bitmapHeight <= 0) throw new IllegalArgumentException("bitmapHeight must be positive");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
