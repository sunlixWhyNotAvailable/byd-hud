package com.bydhud.app;

/** Physical calibration geometry copied from the SL07 transport probe. */
final class HudMapGeometry {
    static final int SOURCE_WIDTH = 300;
    static final int SOURCE_HEIGHT = 180;
    static final float SEAM_Y = 80f;
    static final float MAP_HEIGHT = 110f;
    static final float MAP_CENTER_Y = 60f;
    static final float MAP_X_ORIGIN_SHIFT = 50f;
    static final float STITCH_X = -50f;
    static final float STITCH_Y = 0f;
    static final float STITCH_SCALE = 106f;

    private final HudMapSettings.Geometry geometry;

    HudMapGeometry(HudMapSettings.Geometry geometry) {
        this.geometry = geometry == null ? HudMapSettings.defaults().geometry() : geometry;
    }

    float height() {
        return MAP_HEIGHT * geometry.mapScale / 100f;
    }

    float top() {
        return MAP_CENTER_Y + geometry.mapY - height() / 2f;
    }

    int splitRow() {
        return clamp(Math.round((SEAM_Y - top()) * SOURCE_HEIGHT / height()), 0, SOURCE_HEIGHT);
    }

    float sourceY(int row) {
        return top() + height() * row / SOURCE_HEIGHT;
    }

    float left(int fieldHeight) {
        return centerX(fieldHeight) - height() * SOURCE_WIDTH / SOURCE_HEIGHT / 2f;
    }

    float right(int fieldHeight) {
        return centerX(fieldHeight) + height() * SOURCE_WIDTH / SOURCE_HEIGHT / 2f;
    }

    float centerX(int fieldHeight) {
        float baselineLaneCenter = HudExperimentalLayout.F7_WIDTH_PX
                * HudExperimentalLayout.BASE_LANE_X / 100f;
        return baselineLaneCenter * HudExperimentalLayout.F7_LOGICAL_HEIGHT / fieldHeight
                + HudExperimentalLayout.F7_LEFT - HudExperimentalLayout.BASE_F7_X;
    }

    float x(boolean lower, float logicalX, int fieldHeight) {
        float translated = logicalX + geometry.mapX + MAP_X_ORIGIN_SHIFT;
        float adjusted = lower
                ? centerX(fieldHeight) + (translated - centerX(fieldHeight)) * STITCH_SCALE / 100f
                        + STITCH_X
                : translated;
        return HudExperimentalLayout.x(lower, adjusted, fieldHeight);
    }

    float y(boolean lower, float logicalY, int fieldHeight) {
        float adjusted = lower
                ? SEAM_Y + (logicalY - SEAM_Y) * STITCH_SCALE / 100f + STITCH_Y
                : logicalY;
        return HudExperimentalLayout.y(lower, adjusted, fieldHeight);
    }

    static Bounds laneBounds(int fieldWidth, int fieldHeight, int sourceWidth, int sourceHeight,
                             HudMapSettings.Geometry geometry) {
        if (fieldWidth <= 0 || fieldHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("lane dimensions must be positive");
        }
        HudMapSettings.Geometry settings = geometry == null
                ? HudMapSettings.defaults().geometry() : geometry;
        float height = fieldHeight * 0.88f * settings.laneScale / 100f;
        float width = height * sourceWidth / sourceHeight;
        if (width > fieldWidth) {
            width = fieldWidth;
            height = width * sourceHeight / sourceWidth;
        }
        float left = clamp(fieldWidth * (0.5f + settings.laneX / 100f) - width / 2f,
                0f, fieldWidth - width);
        float top = clamp(fieldHeight * (0.5f + settings.laneY / 100f) - height / 2f,
                0f, fieldHeight - height);
        return new Bounds(left, top, left + width, top + height);
    }

    static final class Bounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
