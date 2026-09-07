package com.bydhud.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.SimpleTimeZone;

/** Pure metric selection/formatting shared by the street field and separate HUD rows. */
final class HudEtaText {
    static final HudEtaText EMPTY = new HudEtaText("", "", "");
    final String arrival;
    final String duration;
    final String remainingDistance;

    HudEtaText(String arrival, String duration, String remainingDistance) {
        this.arrival = arrival;
        this.duration = duration;
        this.remainingDistance = remainingDistance;
    }

    static HudEtaText from(DirectTbtFrame frame, DirectTbtPayload.Options options) {
        if (options.routeMetricsMode == HudPrefs.ROUTE_METRICS_OFF) return EMPTY;
        DirectTbtFrame.TripMetrics trip = frame.getTripMetrics();
        DirectTbtFrame.TravelMetrics next = trip.getNextStop();
        DirectTbtFrame.TravelMetrics whole = trip.getWholeRoute();
        boolean preferWhole = options.wholeRouteMetrics;
        DirectTbtFrame.TravelMetrics eta = select(preferWhole,
                whole.getArrivalTimeEpochMs() > 0, next.getArrivalTimeEpochMs() > 0, whole, next);
        DirectTbtFrame.TravelMetrics time = select(preferWhole,
                whole.getRemainingTimeSeconds() >= 0, next.getRemainingTimeSeconds() >= 0, whole, next);
        DirectTbtFrame.TravelMetrics distance = select(preferWhole,
                whole.getRemainingDistanceMeters() >= 0, next.getRemainingDistanceMeters() >= 0, whole, next);
        String arrival = "";
        if (options.showEta && eta.getArrivalTimeEpochMs() > 0) {
            SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.US);
            if (eta.getArrivalZoneOffsetSeconds()
                    != DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS) {
                formatter.setTimeZone(new SimpleTimeZone(eta.getArrivalZoneOffsetSeconds() * 1000, "HUD"));
            }
            arrival = formatter.format(new Date(eta.getArrivalTimeEpochMs()));
        }
        boolean ua = options.presentation.ukrainian;
        return new HudEtaText(arrival,
                options.showRemainingTime ? duration(time.getRemainingTimeSeconds(), ua) : "",
                options.showRemainingDistance ? distance(distance.getRemainingDistanceMeters(), ua) : "");
    }

    private static DirectTbtFrame.TravelMetrics select(boolean wholePreferred,
            boolean wholeAvailable, boolean nextAvailable,
            DirectTbtFrame.TravelMetrics whole, DirectTbtFrame.TravelMetrics next) {
        return wholePreferred ? (wholeAvailable || !nextAvailable ? whole : next)
                : (nextAvailable || !wholeAvailable ? next : whole);
    }

    static String duration(long seconds, boolean ua) {
        if (seconds < 0) return "";
        long minutes = seconds / 60 + (seconds % 60 == 0 ? 0 : 1);
        String minutePart = minutes % 60 + (ua ? " хв" : " min");
        return minutes < 60 ? minutes + (ua ? " хв" : " min")
                : minutes / 60 + (ua ? " г " : " h ") + minutePart;
    }

    static String distance(long meters, boolean ua) {
        if (meters < 0) return "";
        if (meters < 1000) return meters + (ua ? " м" : " m");
        String value = String.format(Locale.US, "%.1f", meters / 1000d);
        if (value.endsWith(".0")) value = value.substring(0, value.length() - 2);
        if (ua) value = value.replace('.', ',');
        return value + (ua ? " км" : " km");
    }

    String joined() {
        StringBuilder result = new StringBuilder();
        append(result, arrival);
        append(result, duration);
        append(result, remainingDistance);
        return result.toString();
    }

    private static void append(StringBuilder result, String value) {
        if (value.isEmpty()) return;
        if (result.length() > 0) result.append(" | ");
        result.append(value);
    }

    String street(String road, boolean replace) {
        String metrics = joined();
        if (replace) return metrics;
        if (metrics.isEmpty()) return road;
        return "[" + metrics + "]" + (road.isEmpty() ? "" : " " + road);
    }
}
