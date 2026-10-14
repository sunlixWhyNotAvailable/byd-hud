package com.bydhud.app;

/** Normalized roundabout exit direction with its source and diagnostic inputs. */
final class RoundaboutExit {
    enum Direction { UNKNOWN, LEFT, STRAIGHT, RIGHT, U_TURN }

    enum Source { NONE, GMAPS_EXACT, GMAPS_CIRCLE_CONTEXT, WAZE_WITH_ANGLE, HUD_CHECK }

    private static final RoundaboutExit UNKNOWN = new RoundaboutExit(
            Direction.UNKNOWN, Source.NONE, -1, Double.NaN, "");
    private static final RoundaboutExit UNKNOWN_CLUSTER = new RoundaboutExit(
            Direction.UNKNOWN, Source.NONE, -1, Double.NaN, "CLUSTER");
    private static final RoundaboutExit UNKNOWN_MAIN_SURFACE = new RoundaboutExit(
            Direction.UNKNOWN, Source.NONE, -1, Double.NaN, "MAIN_SURFACE");

    private final Direction direction;
    private final Source source;
    private final int rawType;
    private final double rawAngleDegrees;
    private final String sourceMode;

    private RoundaboutExit(
            Direction direction, Source source, int rawType, double rawAngleDegrees,
            String sourceMode) {
        this.direction = direction;
        this.source = source;
        this.rawType = rawType;
        this.rawAngleDegrees = rawAngleDegrees;
        this.sourceMode = sourceMode;
    }

    static RoundaboutExit unknown() {
        return UNKNOWN;
    }

    static RoundaboutExit of(
            Direction direction, Source source, int rawType, double rawAngleDegrees) {
        Direction normalizedDirection = direction == null ? Direction.UNKNOWN : direction;
        Source normalizedSource = source == null ? Source.NONE : source;
        if (normalizedSource == Source.NONE) normalizedDirection = Direction.UNKNOWN;
        double normalizedAngle = Double.isFinite(rawAngleDegrees)
                && rawAngleDegrees >= 1.0 && rawAngleDegrees <= 360.0
                ? rawAngleDegrees : Double.NaN;
        if (normalizedDirection == Direction.UNKNOWN && normalizedSource == Source.NONE
                && rawType < 0 && Double.isNaN(normalizedAngle)) {
            return UNKNOWN;
        }
        return new RoundaboutExit(
                normalizedDirection, normalizedSource, rawType, normalizedAngle, "");
    }

    Direction getDirection() {
        return direction;
    }

    Source getSource() {
        return source;
    }

    int getRawType() {
        return rawType;
    }

    double getRawAngleDegrees() {
        return rawAngleDegrees;
    }

    String getSourceMode() {
        return sourceMode;
    }

    RoundaboutExit withSourceMode(String mode) {
        String normalizedMode = "MAIN_SURFACE".equals(mode) ? "MAIN_SURFACE"
                : ("CLUSTER".equals(mode) ? "CLUSTER" : "");
        if (normalizedMode.equals(sourceMode)) return this;
        if (source == Source.NONE && direction == Direction.UNKNOWN
                && rawType < 0 && Double.isNaN(rawAngleDegrees)) {
            if ("CLUSTER".equals(normalizedMode)) return UNKNOWN_CLUSTER;
            if ("MAIN_SURFACE".equals(normalizedMode)) return UNKNOWN_MAIN_SURFACE;
            return UNKNOWN;
        }
        return new RoundaboutExit(
                direction, source, rawType, rawAngleDegrees, normalizedMode);
    }

    boolean hasDirection() {
        return direction != Direction.UNKNOWN;
    }

    String diagnostics() {
        return "direction=" + direction + " source=" + source + " exitRawType=" + rawType
                + " exitRawAngleDegrees=" + (Double.isNaN(rawAngleDegrees)
                ? "none" : Double.toString(rawAngleDegrees))
                + (sourceMode.isEmpty() ? "" : " mode=" + sourceMode);
    }
}
