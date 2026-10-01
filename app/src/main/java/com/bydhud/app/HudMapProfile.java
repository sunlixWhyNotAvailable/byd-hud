package com.bydhud.app;

/** Source-specific crop settings for the fixed HUD map image. */
public final class HudMapProfile {
    public enum Source {
        GOOGLE_MAPS(GMapsDirectChannel.PACKAGE_NAME),
        WAZE(NavigatorMapCapture.WAZE_PACKAGE),
        WAZE_SURFACE(NavigatorMapCapture.WAZE_PACKAGE);

        private final String packageName;

        Source(String packageName) {
            this.packageName = packageName;
        }

        public String packageName() {
            return packageName;
        }
    }

    public final Source source;
    public final float x;
    public final float y;
    public final float scale;

    public HudMapProfile(Source source, float x, float y, float scale) {
        if (source == null) throw new IllegalArgumentException("source is required");
        this.source = source;
        this.x = clamp(x, -100, 100);
        this.y = clamp(y, -100, 100);
        this.scale = clamp(scale, 20, 300);
    }

    public static HudMapProfile defaults(Source source) {
        switch (source) {
            case GOOGLE_MAPS: return new HudMapProfile(source, 75, -100, 35);
            case WAZE: return new HudMapProfile(source, 85, -35, 35);
            case WAZE_SURFACE: return new HudMapProfile(source, 0, -80, 35);
            default: throw new IllegalArgumentException("Unknown map source: " + source);
        }
    }

    public HudMapProfile withX(float value) {
        return new HudMapProfile(source, value, y, scale);
    }

    public HudMapProfile withY(float value) {
        return new HudMapProfile(source, x, value, scale);
    }

    public HudMapProfile withScale(float value) {
        return new HudMapProfile(source, x, y, value);
    }

    public HudMapProfile withSource(Source value) {
        return new HudMapProfile(value, x, y, scale);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HudMapProfile)) return false;
        HudMapProfile value = (HudMapProfile) other;
        return source == value.source && x == value.x && y == value.y && scale == value.scale;
    }

    @Override
    public int hashCode() {
        int result = source.hashCode();
        result = 31 * result + Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        return 31 * result + Float.floatToIntBits(scale);
    }

    private static float clamp(float value, int minimum, int maximum) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite profile value");
        return Math.round(Math.max(minimum, Math.min(maximum, value)) * 10f) / 10f;
    }
}
