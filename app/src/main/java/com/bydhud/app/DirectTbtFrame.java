package com.bydhud.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Waze turn-by-turn state, independent of the vehicle transport. */
public final class DirectTbtFrame {
    private final int rawManeuverType;
    private final int amapManeuver;
    private final int bydManeuver;
    private final int distanceMeters;
    private final String roadText;
    private final String cueText;
    private final String displayText;
    private final byte[] maneuverPng;
    private final byte[] lanePng;
    private final List<Lane> lanes;
    private final AlertOverlay alertOverlay;

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
        this.rawManeuverType = rawManeuverType;
        this.amapManeuver = Math.max(0, amapManeuver);
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

    DirectTbtFrame withAlertOverlay(AlertOverlay overlay) {
        return new DirectTbtFrame(rawManeuverType, amapManeuver, bydManeuver,
                distanceMeters, roadText, cueText, displayText, maneuverPng, lanePng,
                lanes, overlay);
    }

    boolean hasSameGuidance(DirectTbtFrame other) {
        return other != null
                && rawManeuverType == other.rawManeuverType
                && roadText.equals(other.roadText)
                && cueText.equals(other.cueText);
    }

    private static byte[] cloneBytes(byte[] value) {
        return value == null ? new byte[0] : value.clone();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /** Lane metadata used by field 29 of the cluster payload. */
    public static final class Lane {
        private final int direction;
        private final boolean recommended;
        private final String rawDirections;

        public Lane(int direction, boolean recommended, String rawDirections) {
            this.direction = direction;
            this.recommended = recommended;
            this.rawDirections = safeText(rawDirections);
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
            if (direction == 3) return 3;
            if (direction == 2) return 4;
            return 0;
        }
    }

    /** Alert state is an overlay; navigation lanes remain on the parent frame. */
    public static final class AlertOverlay {
        private static final AlertOverlay INACTIVE =
                new AlertOverlay(false, -1, 0, "", null);

        private final boolean active;
        private final int id;
        private final int distanceMeters;
        private final String displayText;
        private final byte[] maneuverPng;

        private AlertOverlay(boolean active, int id, int distanceMeters,
                             String displayText, byte[] maneuverPng) {
            this.active = active;
            this.id = id;
            this.distanceMeters = Math.max(0, distanceMeters);
            this.displayText = safeText(displayText);
            this.maneuverPng = cloneBytes(maneuverPng);
        }

        public static AlertOverlay inactive() {
            return INACTIVE;
        }

        public static AlertOverlay active(
                int id, int distanceMeters, String displayText, byte[] maneuverPng) {
            return new AlertOverlay(true, id, distanceMeters, displayText, maneuverPng);
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

        public String getDisplayText() {
            return displayText;
        }

        public byte[] getManeuverPng() {
            return maneuverPng.clone();
        }
    }
}
