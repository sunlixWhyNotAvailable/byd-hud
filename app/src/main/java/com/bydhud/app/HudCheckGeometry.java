package com.bydhud.app;

/** Fixed approved N9 diagnostic fixtures; never writes Android location. */
final class HudCheckGeometry {
    static final double LONGITUDE = 121.47832472222223;
    static final double LATITUDE = 31.228410277777776;
    static final double HEADING = 57.50603485107422;
    private static final String STRAIGHT =
            "[[121.47832416666667,31.228410277777776,0],[121.47840472222222,31.228449722222223,0],[121.47875502158523,31.22862124811722,0],[121.4790872958414,31.22878394792543,0],[121.47932299551454,31.22889935948952,0],[121.48005148939569,31.22925607028652,0],[121.4806928905369,31.22957013567292,0]]";
    private static final String[] LINES = {STRAIGHT, STRAIGHT, STRAIGHT, STRAIGHT, "[]"};
    private static final double[] HEADINGS = {
            HEADING, 147.50603485107422, HEADING, 327.5060348510742, HEADING
    };
    private HudCheckGeometry() { }
    static String line(int extendedIndex) { return LINES[extendedIndex - 25]; }
    static double heading(int extendedIndex) { return HEADINGS[extendedIndex - 25]; }
    static String display(int extendedIndex) {
        String line = line(extendedIndex);
        return line.equals("[]") ? line : line.substring(1, line.length() - 1)
                .replace("],[", "]\n[").replace(",", ", ");
    }
}
