package com.bydhud.app;

/** Maps the current AMap maneuver representation to the selected Instrument/broadcast pair. */
final class ManeuverCompatibilityProfile {
    static final int AMAP = 0;
    static final int BAIDU = 1;

    private ManeuverCompatibilityProfile() {
    }

    static int normalize(int profile) {
        return profile == BAIDU ? BAIDU : AMAP;
    }

    static boolean isBaiduInstrumentIcon(int icon) {
        if (icon >= 1001 && icon <= 1010) return true;
        switch (icon) {
            case 1024:
            case 1031:
            case 1069:
            case 1071:
            case 1073:
            case 1075:
            case 1111:
                return true;
            default:
                return false;
        }
    }

    static Mapping map(int profile, int amapIcon, int roundaboutExit,
            int legacyInstrumentIcon) {
        if (normalize(profile) == AMAP) {
            return new Mapping(amapIcon, roundaboutExit,
                    legacyInstrumentIcon, amapIcon, roundaboutExit, true);
        }
        if (legacyInstrumentIcon == 0 && amapIcon <= 0) {
            return baidu(amapIcon, roundaboutExit, 0, 0);
        }
        switch (amapIcon) {
            case 2: return baidu(amapIcon, roundaboutExit, 1007, 7);
            case 3: return baidu(amapIcon, roundaboutExit, 1003, 3);
            case 4: return baidu(amapIcon, roundaboutExit, 1008, 8);
            case 5: return baidu(amapIcon, roundaboutExit, 1002, 2);
            case 6: return baidu(amapIcon, roundaboutExit, 1006, 6);
            case 7: return baidu(amapIcon, roundaboutExit, 1004, 4);
            case 8: return baidu(amapIcon, roundaboutExit, 1005, 5);
            case 9: return baidu(amapIcon, roundaboutExit, 1001, 1);
            case 11:
            case 17:
                return baidu(amapIcon, roundaboutExit, 1009, 9);
            case 12:
            case 18:
                return baidu(amapIcon, roundaboutExit, 1010, 10);
            case 14: return baidu(amapIcon, roundaboutExit, 1031, 31);
            case 15: return baidu(amapIcon, roundaboutExit, 1024, 24);
            case 19: return baidu(amapIcon, roundaboutExit, 1111, 1111);
            case 21:
            case 25:
                return baidu(amapIcon, roundaboutExit, 1075, 96);
            case 22:
            case 26:
                return baidu(amapIcon, roundaboutExit, 1071, 92);
            case 23:
            case 27:
                return baidu(amapIcon, roundaboutExit, 1069, 90);
            case 24:
            case 28:
                return baidu(amapIcon, roundaboutExit, 1073, 94);
            default:
                return new Mapping(amapIcon, roundaboutExit, 0, 0, 0, false);
        }
    }

    private static Mapping baidu(int amapIcon, int roundaboutExit,
            int instrumentIcon, int broadcastIcon) {
        return new Mapping(amapIcon, roundaboutExit,
                instrumentIcon, broadcastIcon, 0, true);
    }

    static final class Mapping {
        final int sourceIcon;
        final int sourceRoundaboutExit;
        final int instrumentIcon;
        final int broadcastIcon;
        final int broadcastRoundaboutExit;
        final boolean supported;

        Mapping(int sourceIcon, int sourceRoundaboutExit,
                int instrumentIcon, int broadcastIcon,
                int broadcastRoundaboutExit, boolean supported) {
            this.sourceIcon = sourceIcon;
            this.sourceRoundaboutExit = sourceRoundaboutExit;
            this.instrumentIcon = instrumentIcon;
            this.broadcastIcon = broadcastIcon;
            this.broadcastRoundaboutExit = broadcastRoundaboutExit;
            this.supported = supported;
        }
    }
}
