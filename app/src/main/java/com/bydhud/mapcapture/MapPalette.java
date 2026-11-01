package com.bydhud.mapcapture;

/** Opaque, source-specific HUD colors; versioned wire input also supports older collectors. */
public final class MapPalette {
    public static final String KEY = "hudPalette";
    public static final MapPalette DEFAULT = new MapPalette(0xff41464c, 0xff66b5ff, 0xffffb81a, 0xff0a0c0e);
    public final int road, route, marker, background;

    public MapPalette(int road, int route, int marker, int background) {
        this.road = road | 0xff000000;
        this.route = route | 0xff000000;
        this.marker = marker | 0xff000000;
        this.background = background | 0xff000000;
    }

    public static MapPalette decode(int[] wire) {
        if (wire == null) return DEFAULT;
        if (wire.length != 5 || wire[0] != 1) throw new IllegalArgumentException("Unsupported HUD palette payload");
        return new MapPalette(wire[1], wire[2], wire[3], wire[4]);
    }

    public int[] encode() { return new int[]{1, road, route, marker, background}; }

    public int casing() {
        return 0xff000000 | (Math.min(255, ((road >>> 16) & 255) + 11) << 16)
                | (Math.min(255, ((road >>> 8) & 255) + 12) << 8) | Math.min(255, (road & 255) + 12);
    }

    @Override public boolean equals(Object value) {
        if (!(value instanceof MapPalette)) return false;
        MapPalette p = (MapPalette) value;
        return road == p.road && route == p.route && marker == p.marker && background == p.background;
    }
    @Override public int hashCode() { return ((road * 31 + route) * 31 + marker) * 31 + background; }
    @Override public String toString() {
        return Integer.toHexString(road) + "/" + Integer.toHexString(route) + "/"
                + Integer.toHexString(marker) + "/" + Integer.toHexString(background);
    }
}
