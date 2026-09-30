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
    public final int x;
    public final int y;
    public final int scale;

    public HudMapProfile(Source source, int x, int y, int scale) {
        if (source == null) throw new IllegalArgumentException("source is required");
        this.source = source;
        this.x = clamp(x, -100, 100);
        this.y = clamp(y, -100, 100);
        this.scale = clamp(scale, 50, 300);
    }

    public static HudMapProfile defaults(Source source) {
        return new HudMapProfile(source, 0, 0, 100);
    }

    public HudMapProfile withX(int value) {
        return new HudMapProfile(source, value, y, scale);
    }

    public HudMapProfile withY(int value) {
        return new HudMapProfile(source, x, value, scale);
    }

    public HudMapProfile withScale(int value) {
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
        result = 31 * result + x;
        result = 31 * result + y;
        return 31 * result + scale;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
