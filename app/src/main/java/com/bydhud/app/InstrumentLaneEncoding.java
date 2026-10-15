package com.bydhud.app;

/** Pure Instrument lane batch encoder for the selected navigation compatibility profile. */
final class InstrumentLaneEncoding {
    private static final int MAX_LANES = HudLaneModel.MAX_LANES;
    private static final int FID_LANE_COUNT = 427_827_416;
    private static final int FID_LANE_GUIDE_BASE = 427_827_288;
    private static final int FID_LANE_LINE_TYPE_BASE = 427_827_296;
    private static final int FID_LANE_RECOMMENDED_BASE = 427_827_300;
    private static final int RIGHT_U_TURN = -2;

    private InstrumentLaneEncoding() {
    }

    static Encoding encode(int profile, int[] directions, int[] recommendations) {
        boolean baidu = profile == ManeuverCompatibilityProfile.BAIDU;
        if (directions == null || recommendations == null
                || directions.length != recommendations.length
                || directions.length > MAX_LANES) {
            return cleared(baidu, baidu ? "invalid lane input" : "");
        }

        int[] baiduIds = baidu ? new int[directions.length] : null;
        if (baidu) {
            for (int index = 0; index < directions.length; index++) {
                int directionMask = compactMask(directions[index]);
                if (directionMask == RIGHT_U_TURN) {
                    return cleared(true, "right-U direction");
                }
                if (directionMask < 0) {
                    return cleared(true, "unsupported direction");
                }

                int recommendation = recommendations[index];
                int recommendationMask = recommendation == DirectTbtFrame.Lane.NO_RECOMMENDATION
                        ? 0 : compactMask(recommendation);
                if (recommendationMask == RIGHT_U_TURN) {
                    return cleared(true, "right-U recommendation");
                }
                if (recommendationMask < 0) {
                    return cleared(true, "unsupported recommendation");
                }
                if ((recommendationMask & ~directionMask) != 0) {
                    return cleared(true, "recommendation outside direction");
                }

                baiduIds[index] = baiduLaneId(directionMask, recommendationMask);
                if (baiduIds[index] < 0) {
                    return cleared(true, "unsupported recommendation subset");
                }
            }
        }

        Encoding encoded = cleared(baidu, "");
        encoded.values[0] = directions.length;
        for (int index = 0; index < directions.length; index++) {
            if (baidu) {
                encoded.values[index + 1] = baiduIds[index];
                encoded.values[index + 9] = 15;
                encoded.values[index + 17] = 0;
            } else {
                encoded.values[index + 1] = amapLaneGuideValue(
                        directions[index], recommendations[index]);
                encoded.values[index + 9] = recommendations[index] == 255 ? 5 : 0;
                encoded.values[index + 17] = 1;
            }
        }
        return encoded;
    }

    private static Encoding cleared(boolean baidu, String issue) {
        int[] featureIds = new int[25];
        int[] values = new int[25];
        featureIds[0] = FID_LANE_COUNT;
        for (int index = 0; index < MAX_LANES; index++) {
            int offset = index * 16;
            featureIds[index + 1] = FID_LANE_GUIDE_BASE + offset;
            featureIds[index + 9] = FID_LANE_LINE_TYPE_BASE + offset;
            featureIds[index + 17] = FID_LANE_RECOMMENDED_BASE + offset;
            values[index + 1] = baidu ? 0 : -1;
            values[index + 9] = baidu ? 15 : 14;
            values[index + 17] = baidu ? 0 : -1;
        }
        return new Encoding(featureIds, values, issue);
    }

    private static int compactMask(int code) {
        switch (code) {
            case 0: return 1;
            case 1: return 2;
            case 2: return 3;
            case 3: return 4;
            case 4: return 5;
            case 5: return 8;
            case 6: return 6;
            case 7: return 7;
            case 8: return RIGHT_U_TURN;
            case 9: return 9;
            case 10: return RIGHT_U_TURN;
            case 11: return 10;
            case 12: return RIGHT_U_TURN;
            case 16: return 11;
            case 17: return 12;
            case 18: return 14;
            case 19: return 13;
            case 20: return RIGHT_U_TURN;
            default: return -1;
        }
    }

    private static int baiduLaneId(int directions, int recommendations) {
        int count = Integer.bitCount(directions);
        int selected = Integer.bitCount(recommendations);
        if (selected != 0 && selected != count && selected != 1) return -1;
        switch (directions) {
            case 1: return recommendations == 0 ? 97 : 96;
            case 2: return recommendations == 0 ? 101 : 100;
            case 4: return recommendations == 0 ? 102 : 103;
            case 8: return recommendations == 0 ? 99 : 98;
            case 3:
                switch (recommendations) {
                    case 0: return 105;
                    case 1: return 104;
                    case 2: return 106;
                    case 3: return 107;
                }
                break;
            case 5:
                switch (recommendations) {
                    case 0: return 108;
                    case 1: return 109;
                    case 4: return 110;
                    case 5: return 111;
                }
                break;
            case 6:
                switch (recommendations) {
                    case 0: return 125;
                    case 2: return 124;
                    case 4: return 126;
                    case 6: return 127;
                }
                break;
            case 9:
                switch (recommendations) {
                    case 0: return 120;
                    case 1: return 122;
                    case 8: return 121;
                    case 9: return 123;
                }
                break;
            case 10:
                switch (recommendations) {
                    case 0: return 112;
                    case 2: return 114;
                    case 8: return 113;
                    case 10: return 115;
                }
                break;
            case 12:
                switch (recommendations) {
                    case 0: return 116;
                    case 4: return 117;
                    case 8: return 118;
                    case 12: return 119;
                }
                break;
            case 7:
                switch (recommendations) {
                    case 0: return 128;
                    case 1: return 129;
                    case 2: return 130;
                    case 4: return 131;
                    case 7: return 132;
                }
                break;
            case 11:
                switch (recommendations) {
                    case 0: return 138;
                    case 1: return 140;
                    case 2: return 141;
                    case 8: return 139;
                    case 11: return 142;
                }
                break;
            case 13:
                switch (recommendations) {
                    case 0: return 143;
                    case 1: return 146;
                    case 4: return 145;
                    case 8: return 144;
                    case 13: return 147;
                }
                break;
            case 14:
                switch (recommendations) {
                    case 0: return 133;
                    case 2: return 136;
                    case 4: return 135;
                    case 8: return 134;
                    case 14: return 137;
                }
                break;
        }
        return -1;
    }

    private static int amapLaneGuideValue(int direction, int recommendation) {
        if (isComplexLane(direction)) {
            int complex = complexLaneGuide(direction, recommendation);
            if (complex >= 0) return complex;
        } else if (recommendation != 255 && recommendation != -1) {
            return simpleFrontLane(recommendation);
        }
        return simpleBackLane(direction);
    }

    private static boolean isComplexLane(int direction) {
        switch (direction) {
            case 2:
            case 4:
            case 6:
            case 7:
            case 9:
            case 10:
            case 11:
            case 12:
            case 16:
            case 17:
            case 18:
            case 19:
            case 20:
                return true;
            default:
                return false;
        }
    }

    private static int complexLaneGuide(int direction, int recommendation) {
        switch (direction) {
            case 2: return recommendation == 0 ? 51 : recommendation == 1 ? 52 : -1;
            case 4: return recommendation == 0 ? 53 : recommendation == 3 ? 54 : -1;
            case 6: return recommendation == 1 ? 55 : recommendation == 3 ? 56 : -1;
            case 7: return recommendation == 0 ? 57
                    : recommendation == 1 ? 58 : recommendation == 3 ? 59 : -1;
            case 9: return recommendation == 0 ? 60 : recommendation == 5 ? 61 : -1;
            case 10: return recommendation == 0 ? 62 : recommendation == 8 ? 63 : -1;
            case 11: return recommendation == 1 ? 64 : recommendation == 5 ? 65 : -1;
            case 12: return recommendation == 3 ? 66 : recommendation == 8 ? 67 : -1;
            case 16: return recommendation == 0 ? 70
                    : recommendation == 1 ? 71 : recommendation == 5 ? 72 : -1;
            case 17: return recommendation == 3 ? 73 : recommendation == 5 ? 74 : -1;
            case 18: return recommendation == 1 ? 75
                    : recommendation == 3 ? 76 : recommendation == 5 ? 77 : -1;
            case 19: return recommendation == 0 ? 78
                    : recommendation == 3 ? 79 : recommendation == 5 ? 80 : -1;
            case 20: return recommendation == 1 ? 81 : recommendation == 8 ? 82 : -1;
            default: return -1;
        }
    }

    private static int simpleBackLane(int direction) {
        if (direction >= 0 && direction < 13) return direction + 1;
        return direction >= 16 && direction < 255 ? direction : -1;
    }

    private static int simpleFrontLane(int direction) {
        if (direction >= 0 && direction < 13) return direction + 26;
        return direction >= 16 && direction < 255 ? direction + 25 : -1;
    }

    static final class Encoding {
        final int[] featureIds;
        final int[] values;
        final String issue;

        Encoding(int[] featureIds, int[] values, String issue) {
            this.featureIds = featureIds;
            this.values = values;
            this.issue = issue;
        }

        int laneCount() {
            return values[0];
        }
    }
}
