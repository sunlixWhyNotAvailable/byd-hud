package com.bydhud.app;

import java.util.Objects;

/** Persisted editor state for the experimental map calibration UI. */
public final class HudMapSettings {
    public static final int OFF = 0;
    public static final int NATIVE = 1;
    public static final int EXPERIMENTAL = 2;

    public static final int PRESET_CUSTOM = 0;
    public static final int PRESET_LARGER_RIGHT = 1;
    public static final int PRESET_SMALLER_CENTER = 2;

    public static final int CONTROL_MAP_X = 0;
    public static final int CONTROL_MAP_Y = 1;
    public static final int CONTROL_MAP_SCALE = 2;
    public static final int CONTROL_LANE_X = 3;
    public static final int CONTROL_LANE_Y = 4;
    public static final int CONTROL_LANE_SCALE = 5;

    public final int mode;
    public final int preset;
    public final Geometry custom;

    public HudMapSettings(int mode, int preset, Geometry custom) {
        this.mode = normalizeMode(mode);
        this.preset = normalizePreset(preset);
        this.custom = custom == null ? Geometry.defaults() : custom;
    }

    public static HudMapSettings defaults() {
        return new HudMapSettings(OFF, PRESET_CUSTOM, Geometry.defaults());
    }

    public Geometry geometry() {
        switch (preset) {
            case PRESET_LARGER_RIGHT:
                return new Geometry(145, 12, 110, 0, 30, 70);
            case PRESET_SMALLER_CENTER:
                return Geometry.defaults();
            default:
                return custom;
        }
    }

    public HudMapSettings withMode(int value) {
        return new HudMapSettings(value, preset, custom);
    }

    public HudMapSettings withPreset(int value) {
        return new HudMapSettings(mode, value, custom);
    }

    /** Editing a preset first copies its visible geometry into Custom. */
    public HudMapSettings withValue(int control, int value) {
        return new HudMapSettings(mode, PRESET_CUSTOM, geometry().withValue(control, value));
    }

    public String diagnostics() {
        return "map(mode=" + mode + ",preset=" + preset + ",custom=" + custom + ")";
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HudMapSettings)) return false;
        HudMapSettings other = (HudMapSettings) value;
        return mode == other.mode && preset == other.preset && custom.equals(other.custom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, preset, custom);
    }

    private static int normalizeMode(int value) {
        return Math.max(OFF, Math.min(EXPERIMENTAL, value));
    }

    private static int normalizePreset(int value) {
        return Math.max(PRESET_CUSTOM, Math.min(PRESET_SMALLER_CENTER, value));
    }

    public static final class Geometry {
        public final int mapX;
        public final int mapY;
        public final int mapScale;
        public final int laneX;
        public final int laneY;
        public final int laneScale;

        public Geometry(int mapX, int mapY, int mapScale, int laneX, int laneY, int laneScale) {
            this.mapX = clamp(mapX, -250, 250);
            this.mapY = clamp(mapY, -60, 60);
            this.mapScale = clamp(mapScale, 40, 150);
            this.laneX = clamp(laneX, -50, 50);
            this.laneY = clamp(laneY, -50, 50);
            this.laneScale = clamp(laneScale, 40, 100);
        }

        static Geometry defaults() {
            return new Geometry(0, -8, 85, 0, 30, 70);
        }

        public int value(int control) {
            switch (control) {
                case CONTROL_MAP_X: return mapX;
                case CONTROL_MAP_Y: return mapY;
                case CONTROL_MAP_SCALE: return mapScale;
                case CONTROL_LANE_X: return laneX;
                case CONTROL_LANE_Y: return laneY;
                case CONTROL_LANE_SCALE: return laneScale;
                default: throw new IllegalArgumentException("Unknown map geometry control: " + control);
            }
        }

        Geometry withValue(int control, int value) {
            switch (control) {
                case CONTROL_MAP_X: return new Geometry(value, mapY, mapScale, laneX, laneY, laneScale);
                case CONTROL_MAP_Y: return new Geometry(mapX, value, mapScale, laneX, laneY, laneScale);
                case CONTROL_MAP_SCALE: return new Geometry(mapX, mapY, value, laneX, laneY, laneScale);
                case CONTROL_LANE_X: return new Geometry(mapX, mapY, mapScale, value, laneY, laneScale);
                case CONTROL_LANE_Y: return new Geometry(mapX, mapY, mapScale, laneX, value, laneScale);
                case CONTROL_LANE_SCALE: return new Geometry(mapX, mapY, mapScale, laneX, laneY, value);
                default: throw new IllegalArgumentException("Unknown map geometry control: " + control);
            }
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Geometry)) return false;
            Geometry other = (Geometry) value;
            return mapX == other.mapX && mapY == other.mapY && mapScale == other.mapScale
                    && laneX == other.laneX && laneY == other.laneY && laneScale == other.laneScale;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mapX, mapY, mapScale, laneX, laneY, laneScale);
        }

        @Override
        public String toString() {
            return mapX + "," + mapY + "," + mapScale + "," + laneX + "," + laneY + "," + laneScale;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
