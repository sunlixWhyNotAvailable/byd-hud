package com.bydhud.app;

/** Explicit HUD Check maneuver codes for the three source vocabularies. */
final class HudCheckManeuverTable {
    private static final int[] BYD = {11, 1, 2, 3, 5, 1, 7, 8, 99, 1, 2};
    private static final int[] AMAP = {9, 2, 3, 4, 5, 6, 8, 19, 22, 65, 66};

    private HudCheckManeuverTable() {
    }

    static int basic(HudCheckState.CodeTable table, int maneuverIndex) {
        int index = Math.floorMod(maneuverIndex, BYD.length);
        if (table == HudCheckState.CodeTable.AMAP) return AMAP[index];
        if (table == HudCheckState.CodeTable.HUAWEI) return huawei(AMAP[index]);
        return BYD[index];
    }

    static int live(HudCheckState.CodeTable table, HudCheckLiveScenario scenario) {
        if (scenario.diagnosticNativeControl || table == HudCheckState.CodeTable.BYD) {
            return scenario.nativeManeuver;
        }
        int amap = scenario.amapManeuver;
        switch (scenario.roundaboutDirection) {
            case RIGHT: amap = 22; break;
            case STRAIGHT: amap = 23; break;
            case LEFT: amap = 21; break;
            default: break;
        }
        return table == HudCheckState.CodeTable.AMAP ? amap : huawei(amap);
    }

    private static int huawei(int amapManeuver) {
        switch (amapManeuver) {
            case 1: return 0;
            case 2: return 1;
            case 3: return 2;
            case 4: return 3;
            case 5: return 4;
            case 6: return 5;
            case 7: return 6;
            case 8: return 7;
            case 9: return 8;
            case 10: return 9;
            case 11: return 10;
            case 12: return 11;
            case 13: return 12;
            case 14: return 13;
            case 15: return 14;
            case 16: return 15;
            case 17: return 16;
            case 18: return 17;
            case 19: return 18;
            case 20: return 19;
            case 21: return 49;
            case 22: return 47;
            case 23: return 48;
            case 24: return 50;
            case 25: return 43;
            case 26: return 45;
            case 27: return 44;
            case 28: return 46;
            case 65: return 42;
            case 66: return 41;
            default: return -1;
        }
    }
}
