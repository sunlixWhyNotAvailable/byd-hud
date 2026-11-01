package com.bydhud.app;

import com.bydhud.mapcapture.MapPalette;
import org.json.JSONException;
import org.json.JSONObject;

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
    public final MapPalette palette;

    public HudMapProfile(Source source, float x, float y, float scale) {
        this(source, x, y, scale, MapPalette.DEFAULT);
    }

    public HudMapProfile(Source source, float x, float y, float scale, MapPalette palette) {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (palette == null) throw new IllegalArgumentException("palette is required");
        this.source = source;
        this.x = clamp(x, -100, 100);
        this.y = clamp(y, -100, 100);
        this.scale = clamp(scale, 20, 300);
        this.palette = palette;
    }

    public static HudMapProfile defaults(Source source) {
        switch (source) {
            case GOOGLE_MAPS: return new HudMapProfile(source, -3, -10, 60);
            case WAZE: return new HudMapProfile(source, 0, -20, 75);
            case WAZE_SURFACE: return new HudMapProfile(source, 0, -20, 75);
            default: throw new IllegalArgumentException("Unknown map source: " + source);
        }
    }

    public HudMapProfile withX(float value) {
        return new HudMapProfile(source, value, y, scale, palette);
    }

    public HudMapProfile withY(float value) {
        return new HudMapProfile(source, x, value, scale, palette);
    }

    public HudMapProfile withScale(float value) {
        return new HudMapProfile(source, x, y, value, palette);
    }

    public HudMapProfile withSource(Source value) {
        return new HudMapProfile(value, x, y, scale, palette);
    }

    public HudMapProfile withPalette(MapPalette value) { return new HudMapProfile(source, x, y, scale, value); }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("source", source.name()).put("x", x).put("y", y).put("scale", scale)
                .put("roadColor", palette.road).put("routeColor", palette.route)
                .put("markerColor", palette.marker).put("backgroundColor", palette.background);
    }

    public static HudMapProfile fromJson(JSONObject value) throws JSONException {
        MapPalette defaults = MapPalette.DEFAULT;
        return new HudMapProfile(Source.valueOf(value.getString("source")),
                (float) value.getDouble("x"), (float) value.getDouble("y"), (float) value.getDouble("scale"),
                new MapPalette(value.optInt("roadColor", defaults.road), value.optInt("routeColor", defaults.route),
                        value.optInt("markerColor", defaults.marker), value.optInt("backgroundColor", defaults.background)));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HudMapProfile)) return false;
        HudMapProfile value = (HudMapProfile) other;
        return source == value.source && x == value.x && y == value.y && scale == value.scale && palette.equals(value.palette);
    }

    @Override
    public int hashCode() {
        int result = source.hashCode();
        result = 31 * result + Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        return 31 * (31 * result + Float.floatToIntBits(scale)) + palette.hashCode();
    }

    private static float clamp(float value, int minimum, int maximum) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite profile value");
        return Math.round(Math.max(minimum, Math.min(maximum, value)) * 10f) / 10f;
    }
}
