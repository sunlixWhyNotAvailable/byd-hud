package com.bydhud.app;

final class HudLaneGeometry {
    static final int OEM_ICON_WIDTH = 37;
    static final int OEM_ICON_HEIGHT = 60;

    private HudLaneGeometry() {
    }

    static Geometry calculate(HudState state, int laneCount) {
        int count = Math.max(1, Math.min(HudLaneModel.MAX_LANES, laneCount));
        int canvasScale = clamp(state.laneCanvasScalePercent, 30, 150);
        int iconScale = clamp(state.laneIconScalePercent, 80, 220);
        int gap = gapPx(state);

        int slotWidth = Math.max(1, Math.round(OEM_ICON_WIDTH * canvasScale / 100f));
        int slotHeight = Math.max(1, Math.round(OEM_ICON_HEIGHT * canvasScale / 100f));
        int iconWidth = Math.max(1, Math.round(slotWidth * iconScale / 100f));
        int iconHeight = Math.max(1, Math.round(slotHeight * iconScale / 100f));
        int bitmapWidth = count * slotWidth + Math.max(0, count - 1) * gap;
        return new Geometry(slotWidth, slotHeight, iconWidth, iconHeight, gap, bitmapWidth, slotHeight);
    }

    private static int gapPx(HudState state) {
        return clamp(state.laneGapPx, 0, 80);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Geometry {
        final int slotWidth;
        final int slotHeight;
        final int iconWidth;
        final int iconHeight;
        final int gapPx;
        final int bitmapWidth;
        final int bitmapHeight;

        Geometry(int slotWidth, int slotHeight, int iconWidth, int iconHeight,
                int gapPx, int bitmapWidth, int bitmapHeight) {
            this.slotWidth = slotWidth;
            this.slotHeight = slotHeight;
            this.iconWidth = iconWidth;
            this.iconHeight = iconHeight;
            this.gapPx = gapPx;
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
        }
    }
}
