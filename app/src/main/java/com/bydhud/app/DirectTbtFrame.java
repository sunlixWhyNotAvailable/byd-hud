package com.bydhud.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable direct turn-by-turn state, independent of the navigator and vehicle transport. */
public final class DirectTbtFrame {
    private final int rawManeuverType;
    private final int amapManeuver;
    private final int amapBroadcastManeuver;
    private final int roundaboutExitNumber;
    private final int bydManeuver;
    private final int distanceMeters;
    private final String roadText;
    private final String cueText;
    private final String displayText;
    private final byte[] maneuverPng;
    private final byte[] lanePng;
    private final List<Lane> lanes;
    private final AlertOverlay alertOverlay;
    private final TripMetrics tripMetrics;
    private final SpeedLimit speedLimit;

    public DirectTbtFrame(
            int rawManeuverType,
            int amapManeuver,
            int bydManeuver,
            int distanceMeters,
            String roadText,
            String cueText,
            String displayText,
            byte[] maneuverPng,
            byte[] lanePng,
            List<Lane> lanes,
            AlertOverlay alertOverlay) {
        this(rawManeuverType, amapManeuver, bydManeuver, distanceMeters,
                roadText, cueText, displayText, maneuverPng, lanePng, lanes,
                alertOverlay, TripMetrics.empty(), SpeedLimit.inactive(),
                amapManeuver, 0);
    }

    public DirectTbtFrame(
            int rawManeuverType,
            int amapManeuver,
            int bydManeuver,
            int distanceMeters,
            String roadText,
            String cueText,
            String displayText,
            byte[] maneuverPng,
            byte[] lanePng,
            List<Lane> lanes,
            AlertOverlay alertOverlay,
            TripMetrics tripMetrics) {
        this(rawManeuverType, amapManeuver, bydManeuver, distanceMeters,
                roadText, cueText, displayText, maneuverPng, lanePng, lanes,
                alertOverlay, tripMetrics, SpeedLimit.inactive(),
                amapManeuver, 0);
    }

    DirectTbtFrame(
            int rawManeuverType,
            int amapManeuver,
            int bydManeuver,
            int distanceMeters,
            String roadText,
            String cueText,
            String displayText,
            byte[] maneuverPng,
            byte[] lanePng,
            List<Lane> lanes,
            AlertOverlay alertOverlay,
            TripMetrics tripMetrics,
            SpeedLimit speedLimit,
            int amapBroadcastManeuver,
            int roundaboutExitNumber) {
        this.rawManeuverType = rawManeuverType;
        this.amapManeuver = Math.max(0, amapManeuver);
        this.amapBroadcastManeuver = Math.max(0, amapBroadcastManeuver);
        this.roundaboutExitNumber = clamp(roundaboutExitNumber, 0, 10);
        this.bydManeuver = Math.max(0, bydManeuver);
        this.distanceMeters = Math.max(0, distanceMeters);
        this.roadText = safeText(roadText);
        this.cueText = safeText(cueText);
        this.displayText = safeText(displayText);
        this.maneuverPng = cloneBytes(maneuverPng);
        this.lanePng = cloneBytes(lanePng);
        this.lanes = Collections.unmodifiableList(new ArrayList<>(
                lanes == null ? Collections.emptyList() : lanes));
        this.alertOverlay = alertOverlay == null ? AlertOverlay.inactive() : alertOverlay;
        this.tripMetrics = tripMetrics == null ? TripMetrics.empty() : tripMetrics;
        this.speedLimit = speedLimit == null ? SpeedLimit.inactive() : speedLimit;
    }

    public static DirectTbtFrame empty() {
        return new DirectTbtFrame(-1, 0, 0, 0, "", "", "",
                null, null, Collections.emptyList(), AlertOverlay.inactive());
    }

    public int getRawManeuverType() {
        return rawManeuverType;
    }

    public int getAmapManeuver() {
        return amapManeuver;
    }

    public int getAmapBroadcastManeuver() {
        return amapBroadcastManeuver;
    }

    public int getRoundaboutExitNumber() {
        return roundaboutExitNumber;
    }

    public int getBydManeuver() {
        return bydManeuver;
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public String getRoadText() {
        return roadText;
    }

    public String getCueText() {
        return cueText;
    }

    public String getDisplayText() {
        return displayText;
    }

    public String getEtaText() {
        return "";
    }

    public byte[] getManeuverPng() {
        return maneuverPng.clone();
    }

    public byte[] getLanePng() {
        return lanePng.clone();
    }

    public List<Lane> getLanes() {
        return lanes;
    }

    public AlertOverlay getAlertOverlay() {
        return alertOverlay;
    }

    public TripMetrics getTripMetrics() {
        return tripMetrics;
    }

    public SpeedLimit getSpeedLimit() {
        return speedLimit;
    }

    DirectTbtFrame withAlertOverlay(AlertOverlay overlay) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, overlay, tripMetrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withManeuverPng(byte[] png) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, png, lanePng,
                lanes, alertOverlay, tripMetrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    boolean hasLaneGuidance() {
        return lanePng.length > 0 || !lanes.isEmpty();
    }

    DirectTbtFrame withLanesFrom(DirectTbtFrame other) {
        if (other == null || !other.hasLaneGuidance()) return this;
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng,
                other.lanePng, other.lanes, alertOverlay, tripMetrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withTripMetrics(TripMetrics metrics) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, alertOverlay, metrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withSpeedLimit(SpeedLimit value) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, alertOverlay, tripMetrics, value,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withDistanceMeters(int value) {
        int normalized = Math.max(0, value);
        if (normalized == distanceMeters) return this;
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                normalized, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, alertOverlay, tripMetrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withNavigationText(String road, String cue) {
        if (road.equals(roadText) && cue.equals(cueText)) return this;
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, road, cue, displayText, maneuverPng, lanePng,
                lanes, alertOverlay, tripMetrics, speedLimit,
                amapBroadcastManeuver, roundaboutExitNumber);
    }

    DirectTbtFrame withVehicleTbt(int broadcastManeuver, int exitNumber) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, alertOverlay, tripMetrics, speedLimit,
                broadcastManeuver, exitNumber);
    }

    /** Canonical direct-channel speed-limit state; zero is an explicit clear. */
    public static final class SpeedLimit {
        private static final SpeedLimit INACTIVE = new SpeedLimit(0, 0, "", 0L);

        private final int displayValue;
        private final int kph;
        private final String unit;
        private final long eventElapsedMs;

        public SpeedLimit(int displayValue, int kph, String unit, long eventElapsedMs) {
            this.displayValue = displayValue > 0 && displayValue <= 300 ? displayValue : 0;
            this.kph = kph > 0 && kph <= 300 ? kph : 0;
            this.unit = this.displayValue == 0 ? "" : safeText(unit);
            this.eventElapsedMs = Math.max(0L, eventElapsedMs);
        }

        public static SpeedLimit inactive() {
            return INACTIVE;
        }

        public boolean isActive() {
            return displayValue > 0;
        }

        public int getDisplayValue() {
            return displayValue;
        }

        public int getKph() {
            return kph;
        }

        public String getUnit() {
            return unit;
        }

        public long getEventElapsedMs() {
            return eventElapsedMs;
        }
    }

    /** Structured trip totals carried independently from the current maneuver. */
    public static final class TripMetrics {
        private static final TripMetrics EMPTY = new TripMetrics(
                TravelMetrics.unavailable(), TravelMetrics.unavailable());

        private final TravelMetrics nextStop;
        private final TravelMetrics wholeRoute;

        public TripMetrics(TravelMetrics nextStop, TravelMetrics wholeRoute) {
            this.nextStop = nextStop == null ? TravelMetrics.unavailable() : nextStop;
            this.wholeRoute = wholeRoute == null ? TravelMetrics.unavailable() : wholeRoute;
        }

        public static TripMetrics empty() {
            return EMPTY;
        }

        public static TripMetrics nextStopOnly(TravelMetrics nextStop) {
            return new TripMetrics(nextStop, TravelMetrics.unavailable());
        }

        public TravelMetrics getNextStop() {
            return nextStop;
        }

        public TravelMetrics getWholeRoute() {
            return wholeRoute;
        }

        public boolean isEmpty() {
            return !nextStop.hasAnyValue() && !wholeRoute.hasAnyValue();
        }
    }

    /** One destination scope; negative values mean that field is unavailable. */
    public static final class TravelMetrics {
        public static final int UNKNOWN_ZONE_OFFSET_SECONDS = Integer.MIN_VALUE;
        private static final TravelMetrics UNAVAILABLE = new TravelMetrics(
                -1L, UNKNOWN_ZONE_OFFSET_SECONDS, -1L, -1L);

        private final long arrivalTimeEpochMs;
        private final int arrivalZoneOffsetSeconds;
        private final long remainingTimeSeconds;
        private final long remainingDistanceMeters;

        public TravelMetrics(
                long arrivalTimeEpochMs,
                long remainingTimeSeconds,
                long remainingDistanceMeters) {
            this(arrivalTimeEpochMs, UNKNOWN_ZONE_OFFSET_SECONDS,
                    remainingTimeSeconds, remainingDistanceMeters);
        }

        public TravelMetrics(
                long arrivalTimeEpochMs,
                int arrivalZoneOffsetSeconds,
                long remainingTimeSeconds,
                long remainingDistanceMeters) {
            this.arrivalTimeEpochMs = arrivalTimeEpochMs > 0L ? arrivalTimeEpochMs : -1L;
            this.arrivalZoneOffsetSeconds = arrivalTimeEpochMs > 0L
                    ? arrivalZoneOffsetSeconds : UNKNOWN_ZONE_OFFSET_SECONDS;
            this.remainingTimeSeconds = remainingTimeSeconds >= 0L
                    ? remainingTimeSeconds : -1L;
            this.remainingDistanceMeters = remainingDistanceMeters >= 0L
                    ? remainingDistanceMeters : -1L;
        }

        public static TravelMetrics unavailable() {
            return UNAVAILABLE;
        }

        public long getArrivalTimeEpochMs() {
            return arrivalTimeEpochMs;
        }

        public int getArrivalZoneOffsetSeconds() {
            return arrivalZoneOffsetSeconds;
        }

        public long getRemainingTimeSeconds() {
            return remainingTimeSeconds;
        }

        public long getRemainingDistanceMeters() {
            return remainingDistanceMeters;
        }

        public boolean hasAnyValue() {
            return arrivalTimeEpochMs > 0L
                    || remainingTimeSeconds >= 0L
                    || remainingDistanceMeters >= 0L;
        }
    }

    private static byte[] cloneBytes(byte[] value) {
        return value == null ? new byte[0] : value.clone();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Lane metadata used by field 29 of the cluster payload. */
    public static final class Lane {
        public static final int NO_RECOMMENDATION = 255;
        private final int direction;
        private final boolean recommended;
        private final String rawDirections;
        private final int amapCode;
        private final int amapRecommendationCode;

        public Lane(int direction, boolean recommended, String rawDirections) {
            this.direction = direction;
            this.recommended = recommended;
            this.rawDirections = safeText(rawDirections);
            this.amapCode = legacyAmapCode(direction);
            this.amapRecommendationCode = recommended ? this.amapCode : NO_RECOMMENDATION;
        }

        /** Creates a lane with the complete and selected Instrument direction codes. */
        public Lane(int amapCode, int amapRecommendationCode, String rawDirections) {
            this.direction = amapCode;
            this.recommended = amapRecommendationCode != NO_RECOMMENDATION;
            this.rawDirections = safeText(rawDirections);
            this.amapCode = amapCode;
            this.amapRecommendationCode = amapRecommendationCode;
        }

        public int getDirection() {
            return direction;
        }

        public boolean isRecommended() {
            return recommended;
        }

        public String getRawDirections() {
            return rawDirections;
        }

        public int getAmapCode() {
            return amapCode;
        }

        public int getAmapRecommendationCode() {
            return amapRecommendationCode;
        }

        static int legacyAmapCode(int direction) {
            if (direction == 3) return 3;
            if (direction == 2) return 4;
            return 0;
        }

        /** Instrument's compact lane vocabulary; returns -1 for an unrepresentable set. */
        static int instrumentCodeForMask(int mask) {
            switch (mask) {
                case 1: return 0; // S
                case 2: return 1; // L
                case 3: return 2; // S+L
                case 4: return 3; // R
                case 5: return 4; // S+R
                case 8: return 5; // U-left
                case 6: return 6; // L+R
                case 7: return 7; // S+L+R
                case 16: return 8; // U-right
                case 9: return 9; // S+U-left
                case 17: return 10; // S+U-right
                case 10: return 11; // L+U-left
                case 20: return 12; // R+U-right
                case 11: return 16; // S+L+U-left
                case 12: return 17; // R+U-left
                case 14: return 18; // L+R+U-left
                case 13: return 19; // S+R+U-left
                case 18: return 20; // L+U-right
                default: return -1;
            }
        }

    }

    /** Alert state is an overlay; navigation lanes remain on the parent frame. */
    public static final class AlertOverlay {
        private static final AlertOverlay INACTIVE =
                new AlertOverlay(false, -1, 0, false, "", null, false);

        private final boolean active;
        private final int id;
        private final int distanceMeters;
        private final boolean distanceKnown;
        private final String displayText;
        private final byte[] maneuverPng;
        private final boolean useRouteFrame;

        private AlertOverlay(boolean active, int id, int distanceMeters,
                             boolean distanceKnown,
                             String displayText, byte[] maneuverPng,
                             boolean useRouteFrame) {
            this.active = active;
            this.id = id;
            this.distanceMeters = Math.max(0, distanceMeters);
            this.distanceKnown = distanceKnown;
            this.displayText = safeText(displayText);
            this.maneuverPng = cloneBytes(maneuverPng);
            this.useRouteFrame = useRouteFrame;
        }

        public static AlertOverlay inactive() {
            return INACTIVE;
        }

        public static AlertOverlay active(
                int id, int distanceMeters, String displayText, byte[] maneuverPng) {
            return new AlertOverlay(true, id, distanceMeters, distanceMeters >= 0,
                    displayText, maneuverPng, false);
        }

        AlertOverlay withRouteFrame(boolean enabled) {
            if (!active || useRouteFrame == enabled) return this;
            return new AlertOverlay(true, id, distanceMeters, distanceKnown,
                    displayText, maneuverPng, enabled);
        }

        public boolean isActive() {
            return active;
        }

        public int getId() {
            return id;
        }

        public int getDistanceMeters() {
            return distanceMeters;
        }

        public boolean isDistanceKnown() {
            return distanceKnown;
        }

        public String getDisplayText() {
            return displayText;
        }

        public byte[] getManeuverPng() {
            return maneuverPng.clone();
        }

        public boolean useRouteFrame() {
            return useRouteFrame;
        }
    }
}
