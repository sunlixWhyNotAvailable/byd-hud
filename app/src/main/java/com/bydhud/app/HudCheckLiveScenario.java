package com.bydhud.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit HUD Check inputs sent through the production Direct TBT formatter. */
final class HudCheckLiveScenario {
    static final int FIRST_EXTENDED_INDEX = 34;

    private static final List<HudCheckLiveScenario> SCENARIOS = Collections.unmodifiableList(Arrays.asList(
            straight("neutral-before-right"),
            turn("right-300", "Right · 300 m", "Праворуч · 300 м", "Направо · 300 м", 3, 2, 300),
            turn("right-150", "Right · 150 m", "Праворуч · 150 м", "Направо · 150 м", 3, 2, 150),
            turn("right-80", "Right · 80 m", "Праворуч · 80 м", "Направо · 80 м", 3, 2, 80),
            turn("right-30", "Right · 30 m", "Праворуч · 30 м", "Направо · 30 м", 3, 2, 30),
            straight("neutral-after-right"),
            turn("left-300", "Left · 300 m", "Ліворуч · 300 м", "Налево · 300 м", 2, 1, 300),
            turn("left-150", "Left · 150 m", "Ліворуч · 150 м", "Налево · 150 м", 2, 1, 150),
            turn("left-80", "Left · 80 m", "Ліворуч · 80 м", "Налево · 80 м", 2, 1, 80),
            turn("left-30", "Left · 30 m", "Ліворуч · 30 м", "Налево · 30 м", 2, 1, 30),
            straight("neutral-after-left"),
            circle("roundabout-right", "Roundabout exit · right · 60 m", "Виїзд з кола · праворуч · 60 м",
                    "Выезд с круга · направо · 60 м", RoundaboutExit.Direction.RIGHT, 99),
            straight("neutral-between-roundabouts"),
            circle("roundabout-straight", "Roundabout exit · straight · 60 m", "Виїзд з кола · прямо · 60 м",
                    "Выезд с круга · прямо · 60 м", RoundaboutExit.Direction.STRAIGHT, 99),
            straight("neutral-between-roundabout-directions"),
            circle("roundabout-left", "Roundabout exit · left · 60 m", "Виїзд з кола · ліворуч · 60 м",
                    "Выезд с круга · налево · 60 м", RoundaboutExit.Direction.LEFT, 99),
            straight("neutral-before-native-controls"),
            circle("roundabout-native-99-before", "Diagnostic only · right circle · native 99",
                    "Лише діагностика · коло праворуч · штатний код 99",
                    "Только диагностика · круг направо · штатный код 99", RoundaboutExit.Direction.RIGHT, 99),
            circle("roundabout-native-11", "Diagnostic only · right circle · native 11",
                    "Лише діагностика · коло праворуч · штатний код 11",
                    "Только диагностика · круг направо · штатный код 11", RoundaboutExit.Direction.RIGHT, 11),
            circle("roundabout-native-99-after", "Diagnostic only · right circle · native 99",
                    "Лише діагностика · коло праворуч · штатний код 99",
                    "Только диагностика · круг направо · штатный код 99", RoundaboutExit.Direction.RIGHT, 99)
    ));

    final String key;
    final String labelEn;
    final String labelUk;
    final String labelRu;
    final int amapManeuver;
    final int amapBroadcastManeuver;
    final int nativeManeuver;
    final int distanceMeters;
    final int bitmapId;
    final RoundaboutExit.Direction roundaboutDirection;
    final boolean diagnosticNativeControl;

    private HudCheckLiveScenario(String key, String labelEn, String labelUk, String labelRu,
                                 int amapManeuver, int amapBroadcastManeuver,
                                 int nativeManeuver, int distanceMeters,
                                 int bitmapId, RoundaboutExit.Direction roundaboutDirection,
                                 boolean diagnosticNativeControl) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelUk = labelUk;
        this.labelRu = labelRu;
        this.amapManeuver = amapManeuver;
        this.amapBroadcastManeuver = amapBroadcastManeuver;
        this.nativeManeuver = nativeManeuver;
        this.distanceMeters = distanceMeters;
        this.bitmapId = bitmapId;
        this.roundaboutDirection = roundaboutDirection;
        this.diagnosticNativeControl = diagnosticNativeControl;
    }

    static int count() {
        return SCENARIOS.size();
    }

    static HudCheckLiveScenario forExtendedIndex(int index) {
        int scenarioIndex = index - FIRST_EXTENDED_INDEX;
        return scenarioIndex < 0 || scenarioIndex >= SCENARIOS.size()
                ? null : SCENARIOS.get(scenarioIndex);
    }

    DirectTbtFrame frame() {
        DirectTbtFrame frame = new DirectTbtFrame(-1, amapManeuver, nativeManeuver,
                distanceMeters, "Continue straight", "", "Continue straight",
                null, null, Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive())
                .withVehicleTbt(amapBroadcastManeuver, 0);
        if (roundaboutDirection != RoundaboutExit.Direction.UNKNOWN) {
            frame = frame.withRoundaboutExit(RoundaboutExit.of(roundaboutDirection,
                    RoundaboutExit.Source.HUD_CHECK, -1, Double.NaN));
        }
        return frame;
    }

    HudManeuverGeometry.Selection geometry() {
        return HudManeuverGeometry.select(frame(), true, false);
    }

    String value() {
        return geometry().display();
    }

    private static HudCheckLiveScenario straight(String key) {
        return new HudCheckLiveScenario(key, "Live pipeline · Straight · 300 m",
                "Наскрізний потік · прямо · 300 м", "Потоковый сценарий · прямо · 300 м", 9, 9, 11, 300, 9,
                RoundaboutExit.Direction.UNKNOWN, false);
    }

    private static HudCheckLiveScenario turn(String key, String labelEn, String labelUk, String labelRu,
                                             int amapManeuver, int nativeManeuver, int distanceMeters) {
        return new HudCheckLiveScenario(key, "Live pipeline · " + labelEn,
                "Наскрізний потік · " + labelUk, "Потоковый сценарий · " + labelRu,
                amapManeuver, amapManeuver, nativeManeuver, distanceMeters,
                nativeManeuver == 1 ? 2 : 3,
                RoundaboutExit.Direction.UNKNOWN, false);
    }

    private static HudCheckLiveScenario circle(String key, String labelEn, String labelUk, String labelRu,
                                               RoundaboutExit.Direction direction, int nativeManeuver) {
        return new HudCheckLiveScenario(key, "Live pipeline · " + labelEn,
                "Наскрізний потік · " + labelUk, "Потоковый сценарий · " + labelRu,
                22, circleBroadcastManeuver(direction), nativeManeuver, 60,
                circleBitmapId(direction),
                direction, key.startsWith("roundabout-native-"));
    }

    private static int circleBroadcastManeuver(RoundaboutExit.Direction direction) {
        switch (direction) {
            case RIGHT: return 26;
            case STRAIGHT: return 27;
            case LEFT: return 25;
            default: return 22;
        }
    }

    private static int circleBitmapId(RoundaboutExit.Direction direction) {
        return circleBroadcastManeuver(direction);
    }
}
