package com.bydhud.app;

import android.content.Context;

import java.util.Objects;

/** Immutable diagnostic view of settings that alter navigation output. */
final class HudOutputPreferenceSnapshot {
    final boolean png;
    final boolean nativeManeuver;
    final boolean lanes;
    final boolean distance;
    final boolean street;
    final boolean textDirection;
    final int textTransliterationMode;
    final boolean clampSmallDistance;
    final boolean wazeAlerts;
    final int routeMetricsMode;
    final boolean eta;
    final boolean remainingTime;
    final boolean remainingDistance;
    final int speedLimitMode;
    final int speedLimitFreeFallback;
    final int speedLimitOverlaySeconds;
    final int speedLimitCompositePlacement;
    final int speedLimitManeuverOverlaySize;
    final int speedLimitLaneOverlaySize;

    private HudOutputPreferenceSnapshot(
            DirectTbtPayload.Options options, boolean wazeAlerts,
            int textTransliterationMode) {
        png = options.png;
        nativeManeuver = options.nativeManeuver;
        lanes = options.lanes;
        distance = options.distance;
        street = options.street;
        textDirection = options.textDirection;
        this.textTransliterationMode = textTransliterationMode;
        clampSmallDistance = options.clampSmallDistance;
        this.wazeAlerts = wazeAlerts;
        routeMetricsMode = options.routeMetricsMode;
        eta = options.showEta;
        remainingTime = options.showRemainingTime;
        remainingDistance = options.showRemainingDistance;
        speedLimitMode = options.speedLimitMode;
        speedLimitFreeFallback = options.speedLimitFreeFallback;
        speedLimitOverlaySeconds = options.speedLimitOverlaySeconds;
        speedLimitCompositePlacement = options.speedLimitCompositePlacement;
        speedLimitManeuverOverlaySize = options.speedLimitManeuverOverlaySize;
        speedLimitLaneOverlaySize = options.speedLimitLaneOverlaySize;
    }

    static HudOutputPreferenceSnapshot capture(Context context) {
        while (true) {
            int before = HudPrefs.outputOptionsRevision();
            DirectTbtPayload.Options options = DirectTbtPayload.Options.from(context);
            boolean alerts = HudPrefs.isWazeAlertsEnabled(context);
            int transliteration = HudPrefs.transliterationMode(context);
            if (before == HudPrefs.outputOptionsRevision()) {
                return new HudOutputPreferenceSnapshot(options, alerts, transliteration);
            }
        }
    }

    static HudOutputPreferenceSnapshot from(
            DirectTbtPayload.Options options, boolean wazeAlerts) {
        return from(options, wazeAlerts, HudPrefs.TRANSLITERATION_OFF);
    }

    static HudOutputPreferenceSnapshot from(
            DirectTbtPayload.Options options, boolean wazeAlerts,
            int textTransliterationMode) {
        return new HudOutputPreferenceSnapshot(
                Objects.requireNonNull(options, "options"), wazeAlerts,
                textTransliterationMode);
    }

    String compact() {
        return "png=" + bit(png)
                + " native=" + bit(nativeManeuver)
                + " lanes=" + bit(lanes)
                + " distance=" + bit(distance)
                + " street=" + bit(street)
                + " textDirection=" + bit(textDirection)
                + " textTransliteration=" + textTransliterationMode
                + " clampSmallDistance=" + bit(clampSmallDistance)
                + " wazeAlerts=" + bit(wazeAlerts)
                + " routeMetrics=" + routeMetricsMode
                + " eta=" + bit(eta)
                + " remainingTime=" + bit(remainingTime)
                + " remainingDistance=" + bit(remainingDistance)
                + " speedLimitMode=" + speedLimitMode
                + " speedFreeFallback=" + speedLimitFreeFallback
                + " speedOverlaySeconds=" + speedLimitOverlaySeconds
                + " speedPlacement=" + speedLimitCompositePlacement
                + " speedManeuverSize=" + speedLimitManeuverOverlaySize
                + " speedLaneSize=" + speedLimitLaneOverlaySize;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HudOutputPreferenceSnapshot)) return false;
        HudOutputPreferenceSnapshot other = (HudOutputPreferenceSnapshot) value;
        return png == other.png
                && nativeManeuver == other.nativeManeuver
                && lanes == other.lanes
                && distance == other.distance
                && street == other.street
                && textDirection == other.textDirection
                && textTransliterationMode == other.textTransliterationMode
                && clampSmallDistance == other.clampSmallDistance
                && wazeAlerts == other.wazeAlerts
                && routeMetricsMode == other.routeMetricsMode
                && eta == other.eta
                && remainingTime == other.remainingTime
                && remainingDistance == other.remainingDistance
                && speedLimitMode == other.speedLimitMode
                && speedLimitFreeFallback == other.speedLimitFreeFallback
                && speedLimitOverlaySeconds == other.speedLimitOverlaySeconds
                && speedLimitCompositePlacement == other.speedLimitCompositePlacement
                && speedLimitManeuverOverlaySize == other.speedLimitManeuverOverlaySize
                && speedLimitLaneOverlaySize == other.speedLimitLaneOverlaySize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                png, nativeManeuver, lanes, distance, street, textDirection,
                textTransliterationMode, clampSmallDistance, wazeAlerts, routeMetricsMode, eta,
                remainingTime, remainingDistance, speedLimitMode,
                speedLimitFreeFallback, speedLimitOverlaySeconds,
                speedLimitCompositePlacement, speedLimitManeuverOverlaySize,
                speedLimitLaneOverlaySize);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
