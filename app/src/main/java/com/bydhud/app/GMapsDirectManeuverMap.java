package com.bydhud.app;

import com.bydhud.gmapswire.GmapsWireParser;

/** Versioned GMaps wire maneuver mapping for the direct HUD channel. */
final class GMapsDirectManeuverMap {
    private static final int SOURCE_BLANK = 72;
    private static final int NATIVE_BLANK = 99;

    private int lastClockwiseRoundaboutSource = SOURCE_BLANK;
    private int lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;
    private RoundaboutExit lastClockwiseRoundaboutExit = RoundaboutExit.unknown();
    private RoundaboutExit lastCounterClockwiseRoundaboutExit = RoundaboutExit.unknown();
    private int lastClockwiseRoundaboutStep = -1;
    private int lastCounterClockwiseRoundaboutStep = -1;

    Result map(int wireValue, int currentStepIndex) {
        return map(wireValue, currentStepIndex, validStepIndex(currentStepIndex));
    }

    Result map(int wireValue, int currentStepIndex, boolean currentStepIndexPresent) {
        // The parser preserves zero as its legacy default. Only an original field 3
        // observation can establish or expire a generic roundabout-exit context.
        int roundaboutStepIndex = currentStepIndexPresent
                && validStepIndex(currentStepIndex) ? currentStepIndex : -1;
        clearExpiredRoundaboutContext(roundaboutStepIndex);
        String name = GmapsWireParser.maneuverName(wireValue);
        if (name == null) {
            clearRoundaboutSequence();
            return result(wireValue, "UNKNOWN", 0, SOURCE_BLANK);
        }

        switch (name) {
            case "DEPART":
                clearRoundaboutSequence();
                // DEPART is blank on RoadInfo, but the dashboard contract uses AMap 20.
                return result(wireValue, name, 20, SOURCE_BLANK);
            case "NAME_CHANGE":
            case "STRAIGHT":
            case "FERRY_BOAT":
            case "FERRY_TRAIN":
            case "MERGE":
                clearRoundaboutSequence();
                return result(wireValue, name, 9, 9);
            case "KEEP_LEFT":
            case "TURN_SLIGHT_LEFT":
            case "ON_RAMP_SLIGHT_LEFT":
            case "OFF_RAMP_SLIGHT_LEFT":
            case "FORK_LEFT":
            case "MERGE_LEFT":
            case "ON_RAMP_KEEP_LEFT":
            case "OFF_RAMP_KEEP_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 4, 4);
            case "KEEP_RIGHT":
            case "TURN_SLIGHT_RIGHT":
            case "ON_RAMP_SLIGHT_RIGHT":
            case "OFF_RAMP_SLIGHT_RIGHT":
            case "FORK_RIGHT":
            case "MERGE_RIGHT":
            case "ON_RAMP_KEEP_RIGHT":
            case "OFF_RAMP_KEEP_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 5, 5);
            case "TURN_NORMAL_LEFT":
            case "ON_RAMP_NORMAL_LEFT":
            case "OFF_RAMP_NORMAL_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 2, 2);
            case "TURN_NORMAL_RIGHT":
            case "ON_RAMP_NORMAL_RIGHT":
            case "OFF_RAMP_NORMAL_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 3, 3);
            case "TURN_SHARP_LEFT":
            case "ON_RAMP_SHARP_LEFT":
            case "OFF_RAMP_SHARP_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 6, 6);
            case "TURN_SHARP_RIGHT":
            case "ON_RAMP_SHARP_RIGHT":
            case "OFF_RAMP_SHARP_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 7, 7);
            case "U_TURN_LEFT":
            case "ON_RAMP_U_TURN_LEFT":
            case "OFF_RAMP_U_TURN_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 8, 8);
            case "U_TURN_RIGHT":
            case "ON_RAMP_U_TURN_RIGHT":
            case "OFF_RAMP_U_TURN_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 19, 19);
            case "DESTINATION":
            case "DESTINATION_STRAIGHT":
            case "DESTINATION_LEFT":
            case "DESTINATION_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 15, 10);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_LEFT":
                return exactRoundabout(wireValue, name, true, 25, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_STRAIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_LEFT":
                return exactRoundabout(wireValue, name, true, 27, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_RIGHT":
                return exactRoundabout(wireValue, name, true, 26, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_U_TURN":
                return exactRoundabout(wireValue, name, true, 28, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_RIGHT":
                return exactRoundabout(wireValue, name, false, 22, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_STRAIGHT":
                return exactRoundabout(wireValue, name, false, 23, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_LEFT":
                return exactRoundabout(wireValue, name, false, 21, roundaboutStepIndex);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_U_TURN":
                return exactRoundabout(wireValue, name, false, 24, roundaboutStepIndex);
            case "ROUNDABOUT_EXIT_CW": {
                RoundaboutExit exit = circleContext(
                        lastClockwiseRoundaboutExit,
                        lastClockwiseRoundaboutStep, roundaboutStepIndex);
                Result mapped = result(wireValue, name, 22,
                        exit.hasDirection() ? lastClockwiseRoundaboutSource : SOURCE_BLANK, 18, exit);
                if (!exit.hasDirection() && (lastClockwiseRoundaboutExit.hasDirection()
                        || lastCounterClockwiseRoundaboutExit.hasDirection())) {
                    clearRoundaboutSequence();
                }
                return mapped;
            }
            case "ROUNDABOUT_EXIT_CCW": {
                RoundaboutExit exit = circleContext(
                        lastCounterClockwiseRoundaboutExit,
                        lastCounterClockwiseRoundaboutStep, roundaboutStepIndex);
                Result mapped = result(wireValue, name, 25,
                        exit.hasDirection() ? lastCounterClockwiseRoundaboutSource : SOURCE_BLANK, 12, exit);
                if (!exit.hasDirection() && (lastClockwiseRoundaboutExit.hasDirection()
                        || lastCounterClockwiseRoundaboutExit.hasDirection())) {
                    clearRoundaboutSequence();
                }
                return mapped;
            }
            case "ROUNDABOUT_ENTER_AND_EXIT_CW":
            case "ROUNDABOUT_ENTER_CW":
                clearRoundaboutSequence();
                return result(wireValue, name, 22, SOURCE_BLANK, 17);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW":
            case "ROUNDABOUT_ENTER_CCW":
                clearRoundaboutSequence();
                return result(wireValue, name, 25, SOURCE_BLANK, 11);
            default:
                clearRoundaboutSequence();
                return result(wireValue, name, 0, SOURCE_BLANK);
        }
    }

    void reset() {
        clearRoundaboutSequence();
    }

    private Result exactRoundabout(
            int wireValue, String name, boolean clockwise, int source, int currentStepIndex) {
        RoundaboutExit exit = exactDirection(wireValue, name);
        if (clockwise) {
            lastClockwiseRoundaboutSource = source;
            lastClockwiseRoundaboutExit = exit;
            lastClockwiseRoundaboutStep = validStepIndex(currentStepIndex)
                    ? currentStepIndex : -1;
            lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;
            lastCounterClockwiseRoundaboutExit = RoundaboutExit.unknown();
            lastCounterClockwiseRoundaboutStep = -1;
        } else {
            lastCounterClockwiseRoundaboutSource = source;
            lastCounterClockwiseRoundaboutExit = exit;
            lastCounterClockwiseRoundaboutStep = validStepIndex(currentStepIndex)
                    ? currentStepIndex : -1;
            lastClockwiseRoundaboutSource = SOURCE_BLANK;
            lastClockwiseRoundaboutExit = RoundaboutExit.unknown();
            lastClockwiseRoundaboutStep = -1;
        }
        return result(wireValue, name, clockwise ? 22 : 25, source, source, exit);
    }

    private static RoundaboutExit exactDirection(int wireValue, String name) {
        RoundaboutExit.Direction direction;
        if (name.endsWith("_U_TURN")) {
            direction = RoundaboutExit.Direction.U_TURN;
        } else if (name.endsWith("_STRAIGHT")) {
            direction = RoundaboutExit.Direction.STRAIGHT;
        } else if (name.endsWith("_LEFT")) {
            direction = RoundaboutExit.Direction.LEFT;
        } else if (name.endsWith("_RIGHT")) {
            direction = RoundaboutExit.Direction.RIGHT;
        } else {
            direction = RoundaboutExit.Direction.UNKNOWN;
        }
        return RoundaboutExit.of(direction, RoundaboutExit.Source.GMAPS_EXACT,
                wireValue, Double.NaN);
    }

    private static RoundaboutExit circleContext(
            RoundaboutExit exit, int contextStep, int currentStep) {
        // shortcut: adjacent-step identity is ambiguous without a circle ID; use it when decoded.
        // Absent indices cannot establish even this bounded handoff.
        return exit.hasDirection() && contextStep >= 0 && validStepIndex(currentStep)
                && currentStep >= contextStep && currentStep - contextStep <= 1
                ? RoundaboutExit.of(exit.getDirection(),
                RoundaboutExit.Source.GMAPS_CIRCLE_CONTEXT,
                exit.getRawType(), exit.getRawAngleDegrees())
                : RoundaboutExit.unknown();
    }

    private void clearExpiredRoundaboutContext(int currentStepIndex) {
        if (!validStepIndex(currentStepIndex)) return;
        boolean clockwiseExpired = lastClockwiseRoundaboutExit.hasDirection()
                && (lastClockwiseRoundaboutStep < 0
                || currentStepIndex < lastClockwiseRoundaboutStep
                || currentStepIndex - lastClockwiseRoundaboutStep > 1);
        boolean counterClockwiseExpired = lastCounterClockwiseRoundaboutExit.hasDirection()
                && (lastCounterClockwiseRoundaboutStep < 0
                || currentStepIndex < lastCounterClockwiseRoundaboutStep
                || currentStepIndex - lastCounterClockwiseRoundaboutStep > 1);
        if (clockwiseExpired || counterClockwiseExpired) clearRoundaboutSequence();
    }

    private static boolean validStepIndex(int stepIndex) {
        return stepIndex >= 0;
    }

    private static Result result(int wireValue, String name, int intermediate, int source) {
        return result(wireValue, name, intermediate, source, intermediate);
    }

    private static Result result(int wireValue, String name, int intermediate,
            int source, int amapBroadcastManeuver) {
        return new Result(wireValue, name, intermediate, source, nativeFor(intermediate),
                amapBroadcastManeuver, 0);
    }

    private static Result result(int wireValue, String name, int intermediate,
            int source, int amapBroadcastManeuver, RoundaboutExit roundaboutExit) {
        return new Result(wireValue, name, intermediate, source, nativeFor(intermediate),
                amapBroadcastManeuver, 0, roundaboutExit);
    }

    /*
     * Intermediate legend:
     * 0 unknown/blank; 2 normal left; 3 normal right; 4 slight/keep left;
     * 5 slight/keep right; 6 sharp left; 7 sharp right; 8 left U-turn;
     * 9 straight; 15 destination; 19 right U-turn; 22 clockwise roundabout;
     * 25 counter-clockwise roundabout.
     */
    private static int nativeFor(int intermediate) {
        switch (intermediate) {
            case 2: return 1;
            case 3: return 2;
            case 4: return 3;
            case 5: return 5;
            case 6: return 1;
            case 7: return 2;
            case 8: return 7;
            case 9: return 11;
            case 19: return 8;
            case 15:
            case 22:
            case 25:
            default: return NATIVE_BLANK;
        }
    }

    private void clearRoundaboutSequence() {
        lastClockwiseRoundaboutSource = SOURCE_BLANK;
        lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;
        lastClockwiseRoundaboutExit = RoundaboutExit.unknown();
        lastCounterClockwiseRoundaboutExit = RoundaboutExit.unknown();
        lastClockwiseRoundaboutStep = -1;
        lastCounterClockwiseRoundaboutStep = -1;
    }

    static final class Result {
        final int wireValue;
        final String maneuverName;
        final int intermediate;
        final int fallbackSource;
        final int nativeManeuver;
        final int amapBroadcastManeuver;
        final int roundaboutExitNumber;
        final RoundaboutExit roundaboutExit;

        Result(int wireValue, String maneuverName, int intermediate,
               int fallbackSource, int nativeManeuver,
               int amapBroadcastManeuver, int roundaboutExitNumber) {
            this(wireValue, maneuverName, intermediate, fallbackSource, nativeManeuver,
                    amapBroadcastManeuver, roundaboutExitNumber, RoundaboutExit.unknown());
        }

        Result(int wireValue, String maneuverName, int intermediate,
               int fallbackSource, int nativeManeuver,
               int amapBroadcastManeuver, int roundaboutExitNumber,
               RoundaboutExit roundaboutExit) {
            this.wireValue = wireValue;
            this.maneuverName = maneuverName;
            this.intermediate = intermediate;
            this.fallbackSource = fallbackSource;
            this.nativeManeuver = nativeManeuver;
            this.amapBroadcastManeuver = amapBroadcastManeuver;
            this.roundaboutExitNumber = roundaboutExitNumber;
            this.roundaboutExit = roundaboutExit;
        }
    }
}
