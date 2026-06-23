package com.bydhud.app;

//centralizes lane and arrow asset combinations so native HUD payloads stay deterministic.

final class HudArrowComboCatalog {
    private static final int DEFAULT_INDEX = 12;

    //defines the Combo module boundary so related behavior stays readable inside one unit.
    static final class Combo {
        final String label;
        final int sourceId;
        final int nativeId;
        final boolean pngVisible;
        final boolean nativeVisible;

        Combo(String label, int sourceId, int nativeId, boolean pngVisible, boolean nativeVisible) {
            this.label = label;
            this.sourceId = sourceId;
            this.nativeId = nativeId;
            this.pngVisible = pngVisible;
            this.nativeVisible = nativeVisible;
        }

        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        String roadLabel() {
            return label + " N" + two(nativeId) + " S" + two(sourceId);
        }
    }

    private static final Combo[] CURATED = {
            new Combo("PNG only hide", 72, 11, false, true),
            new Combo("Native only hide", 9, 99, true, false),
            new Combo("PNG and Native hide", 72, 99, false, false),
            new Combo("End marker car", 0, 99, true, false),
            new Combo("Left 90", 2, 1, true, true),
            new Combo("Right 90", 3, 2, true, true),
            new Combo("Left 45", 4, 3, true, true),
            new Combo("Right 45", 5, 5, true, true),
            new Combo("Left 150", 6, 1, true, true),
            new Combo("Right 90 alt", 7, 2, true, true),
            new Combo("Left U-turn", 8, 7, true, true),
            new Combo("Right U-turn", 19, 8, true, true),
            new Combo("Straight", 9, 11, true, true),
            new Combo("Road stop marker", 10, 99, true, false),
            new Combo("Roundabout enter right straight", 11, 11, true, true),
            new Combo("Roundabout exit right straight", 12, 11, true, true),
            new Combo("Straight dashed base", 20, 11, true, true),
            new Combo("Roundabout right exit 3", 21, 99, true, false),
            new Combo("Roundabout right exit 1", 22, 99, true, false),
            new Combo("Roundabout right exit 2", 23, 11, true, true),
            new Combo("Roundabout right exit 4", 24, 99, true, false),
            new Combo("Roundabout numbered exit 1", 50, 99, true, false),
            new Combo("Roundabout numbered exit 2", 51, 99, true, false),
            new Combo("Roundabout numbered exit 3", 52, 99, true, false),
            new Combo("Roundabout numbered exit 4", 53, 99, true, false),
            new Combo("Roundabout numbered exit 5", 54, 99, true, false),
            new Combo("Roundabout numbered exit 6", 55, 99, true, false),
            new Combo("Roundabout numbered exit 7", 56, 99, true, false),
            new Combo("Roundabout numbered exit 8", 57, 99, true, false),
            new Combo("Roundabout numbered exit 9", 58, 99, true, false),
            new Combo("Roundabout numbered exit 10", 59, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 1", 60, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 2", 61, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 3", 62, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 4", 63, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 5", 64, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 6", 65, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 7", 66, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 8", 67, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 9", 68, 99, true, false),
            new Combo("Roundabout left-hand numbered exit 10", 69, 99, true, false),
            new Combo("Exit ramp right", 70, 2, true, true),
            new Combo("Exit ramp left", 71, 1, true, true)
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudArrowComboCatalog() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int size() {
        return CURATED.length;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int defaultIndex() {
        return DEFAULT_INDEX;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static Combo curatedAt(int index) {
        return CURATED[wrap(index, CURATED.length)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int next(int index) {
        return wrap(index + 1, CURATED.length);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int prev(int index) {
        return wrap(index - 1, CURATED.length);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String two(int value) {
        if (value < 0) {
            return String.valueOf(value);
        }
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int wrapRaw(int value) {
        return wrap(value, 100);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static int wrap(int value, int size) {
        int result = value % size;
        return result < 0 ? result + size : result;
    }
}
