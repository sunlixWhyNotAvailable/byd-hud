package com.bydhud.app;

import java.util.Objects;

/** Immutable, bounded HUD output-check selections shared by Compose and senders. */
public final class HudCheckState {
    public enum Mode { BASIC, EXTENDED }

    public enum Field { MANEUVER, LANES, DISTANCE, STREET, TRAFFIC_LIGHT }

    private static final int EXTENDED_BASELINE_DISTANCE = 77;
    private static final String EXTENDED_BASELINE_STREET = "Continue straight";
    private static final String EXTENDED_BASELINE_LANES = "S* | S | S* | S | S*";

    private static final Maneuver[] MANEUVERS = {
            new Maneuver("Straight", "Прямо", 9, 11),
            new Maneuver("Left", "Ліворуч", 2, 1),
            new Maneuver("Right", "Праворуч", 3, 2),
            new Maneuver("Slight left", "Плавно ліворуч", 4, 3),
            new Maneuver("Slight right", "Плавно праворуч", 5, 5),
            new Maneuver("Sharp left", "Різко ліворуч", 6, 1),
            new Maneuver("Left U-turn", "Розворот ліворуч", 8, 7),
            new Maneuver("Right U-turn", "Розворот праворуч", 19, 8),
            new Maneuver("Roundabout right exit 2", "Коло, виїзд праворуч 2", 23, 11),
            new Maneuver("Exit ramp left", "З’їзд ліворуч", 71, 1),
            new Maneuver("Exit ramp right", "З’їзд праворуч", 70, 2)
    };

    private static final String[] LANES = {
            EXTENDED_BASELINE_LANES,
            "L | S* | S*+R",
            "S | S | Rs*",
            "Ls | S*+Ls | S* | S*+R",
            "L | S*+L | S* | S* | R",
            "U*+L | S* | S+R",
            "L*+S | S | S+R*",
            "S*+R* | S | R"
    };

    private static final int[] DISTANCES = {1, 11, 20, 55, 155, 1555, 15555};

    private static final String[] STREETS = {
            "ТЕСТ", "TEST", "Київ", "Main Street", "Львів", "Oak Avenue",
            "Шевченка", "Щаслива", "Європейська", "Їжак"
    };

    private static final Label[] TRAFFIC_LIGHTS = {
            new Label("Green · straight · 8", "Зелений · прямо · 8"),
            new Label("Red · left · 8", "Червоний · ліворуч · 8"),
            new Label("Yellow · right · 8", "Жовтий · праворуч · 8"),
            new Label("Green · U-turn · 8", "Зелений · розворот · 8"),
            new Label("Green · straight · 3", "Зелений · прямо · 3"),
            new Label("Green · straight · 2", "Зелений · прямо · 2"),
            new Label("Green · straight · 1", "Зелений · прямо · 1"),
            new Label("Green · straight · 0", "Зелений · прямо · 0"),
            new Label("Red · straight · 99", "Червоний · прямо · 99"),
            new Label("Pass · 通行", "Проїзд · 通行"),
            new Label("Wait · 等待", "Очікування · 等待"),
            new Label("Caution · 注意", "Увага · 注意")
    };

    private static final ExtendedSample[] EXTENDED = {
            new ExtendedSample("Arrival time", "Час прибуття", "RoadInfo · f26", "12:34"),
            new ExtendedSample("Remaining time", "Час до прибуття", "RoadInfo · f3, f4, f27", "12345 m · 1200 s · 20 min"),
            new ExtendedSample("Remaining distance", "Відстань до прибуття", "RoadInfo · f3", "12345 m"),
            new ExtendedSample("Road speed limit", "Обмеження швидкості дороги", "RoadInfo · f11", "60 km/h"),
            new ExtendedSample("Speed data", "Дані швидкості", "RoadInfo · f12–15, f21", "25 · 300 · 120 · 40 · 25"),
            new ExtendedSample("Camera type and distance", "Тип камери та дистанція", "RoadInfo · f17–18", "1 · 120 m"),
            new ExtendedSample("Warning", "Попередження", "RoadInfo · f23", "1"),
            new ExtendedSample("Points of interest", "Об’єкти на маршруті", "RoadInfo · f24", "[{name: TEST, type: 0101}]"),
            new ExtendedSample("Destination", "Місце призначення", "RoadInfo · f25", "121.4737, 31.2304"),
            new ExtendedSample("Position", "Положення", "RoadInfo · f19–20", "121.4737, 31.2304"),
            new ExtendedSample("Road data", "Дані дороги", "RoadInfo · f5–6, f22", "5 · 6 · 12"),
            new ExtendedSample("Guide line", "Лінія маршруту", "RoadInfo · f30", "121.4737,31.2304 → 121.4740,31.2307"),
            new ExtendedSample("Guide point", "Точка маршруту", "RoadInfo · f31", "121.4740,31.2307,0"),
            new ExtendedSample("Heading", "Курс", "RoadInfo · f32", "90°"),
            new ExtendedSample("Route progress", "Прогрес маршруту", "RoadInfo · f33", "0.42"),
            new ExtendedSample("Map path", "Шлях на мапі", "MapPath · 0x8002", "f1=0 · f2=2 · f3=1 · f4=45 · f5=1.5 · f6=Base64 PNG 320 × 180"),
            new ExtendedSample("Navigation map", "Навігаційна мапа", "NavigationMap · 0x8003", "f1 packed ASCII Base64 PNG · 320 × 180"),
            new ExtendedSample("Traffic speed limit", "Обмеження швидкості руху", "TrafficInfo · f8", "60 km/h"),
            new ExtendedSample("Speed camera", "Камера швидкості", "TrafficInfo · f9–10", "40 km/h · 120 m"),
            new ExtendedSample("Average-speed section", "Ділянка середньої швидкості", "TrafficInfo · f11–15", "121.4737,31.2304 → 121.4747,31.2314 · 40"),
            new ExtendedSample("Camera guidance", "Підказка про камеру", "Matrix · navigation action / camera", "11 · 77 m · 1 · 120 m"),
            new ExtendedSample("Route lanes", "Смуги маршруту", "RouteSession · lane data", EXTENDED_BASELINE_LANES),
            new ExtendedSample("Route traffic light", "Світлофор маршруту", "Matrix · traffic-light data", "direction=4 · state=4 · countdown=8"),
            new ExtendedSample("Route segments", "Сегменти маршруту",
                    "Statistic · f1, f5, f12–13",
                    "markers=0xD619A0F8,0xE38A6876 · f12=5.0 · f13=2.2 · second marker-only"),
            new ExtendedSample("Route summary", "Підсумок маршруту",
                    "RouteMetadata · f1, f3–4",
                    "routeId=1 · active=1 · f4=epoch ms × 1000")
    };

    public final Mode mode;
    public final boolean running;
    public final boolean automatic;
    public final int maneuverIndex;
    public final int laneIndex;
    public final int distanceIndex;
    public final int streetIndex;
    public final int trafficLightIndex;
    public final boolean maneuverBitmap;
    public final boolean laneBitmap;
    public final boolean transliterate;
    public final int extendedIndex;

    public HudCheckState() {
        this(Mode.BASIC, false, true, 0, 0, 0, 0, 0, false, false, false, 0);
    }

    private HudCheckState(Mode mode, boolean running, boolean automatic,
                          int maneuverIndex, int laneIndex, int distanceIndex,
                          int streetIndex, int trafficLightIndex,
                          boolean maneuverBitmap, boolean laneBitmap,
                          boolean transliterate, int extendedIndex) {
        this.mode = mode == null ? Mode.BASIC : mode;
        this.running = running;
        this.automatic = automatic;
        this.maneuverIndex = wrap(maneuverIndex, MANEUVERS.length);
        this.laneIndex = wrap(laneIndex, LANES.length);
        this.distanceIndex = wrap(distanceIndex, DISTANCES.length);
        this.streetIndex = wrap(streetIndex, STREETS.length);
        this.trafficLightIndex = wrap(trafficLightIndex, TRAFFIC_LIGHTS.length);
        this.maneuverBitmap = maneuverBitmap;
        this.laneBitmap = laneBitmap;
        this.transliterate = transliterate;
        this.extendedIndex = wrap(extendedIndex, EXTENDED.length);
    }

    public HudCheckState selectMode(Mode value) {
        Mode selected = value == null ? Mode.BASIC : value;
        return mode == selected
                ? this
                : new HudCheckState(selected, false, automatic, maneuverIndex, laneIndex,
                        distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap,
                        laneBitmap, transliterate, extendedIndex);
    }

    public HudCheckState toggleRun() {
        return new HudCheckState(mode, !running, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap,
                transliterate, extendedIndex);
    }

    public HudCheckState stop() {
        return running ? new HudCheckState(mode, false, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap,
                transliterate, extendedIndex) : this;
    }

    public HudCheckState step(Field field, int delta) {
        if (field == null || delta == 0) return this;
        switch (field) {
            case MANEUVER:
                return replace(maneuverIndex + delta, laneIndex, distanceIndex, streetIndex,
                        trafficLightIndex, extendedIndex);
            case LANES:
                return replace(maneuverIndex, laneIndex + delta, distanceIndex, streetIndex,
                        trafficLightIndex, extendedIndex);
            case DISTANCE:
                return replace(maneuverIndex, laneIndex, distanceIndex + delta, streetIndex,
                        trafficLightIndex, extendedIndex);
            case STREET:
                return replace(maneuverIndex, laneIndex, distanceIndex, streetIndex + delta,
                        trafficLightIndex, extendedIndex);
            case TRAFFIC_LIGHT:
                return replace(maneuverIndex, laneIndex, distanceIndex, streetIndex,
                        trafficLightIndex + delta, extendedIndex);
            default:
                return this;
        }
    }

    public HudCheckState stepExtended(int delta) {
        return mode == Mode.EXTENDED && !automatic && delta != 0
                ? replace(maneuverIndex, laneIndex, distanceIndex, streetIndex,
                        trafficLightIndex, extendedIndex + delta) : this;
    }

    public HudCheckState tick() {
        return running && automatic && mode == Mode.EXTENDED
                ? replace(maneuverIndex, laneIndex, distanceIndex, streetIndex,
                        trafficLightIndex, extendedIndex + 1) : this;
    }

    public HudCheckState withAutomatic(boolean value) {
        return automatic == value ? this : new HudCheckState(mode, running, value,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                maneuverBitmap, laneBitmap, transliterate, extendedIndex);
    }

    public HudCheckState withManeuverBitmap(boolean value) {
        return maneuverBitmap == value ? this : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                value, laneBitmap, transliterate, extendedIndex);
    }

    public HudCheckState withLaneBitmap(boolean value) {
        return laneBitmap == value ? this : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                maneuverBitmap, value, transliterate, extendedIndex);
    }

    public HudCheckState withTransliterate(boolean value) {
        return transliterate == value ? this : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                maneuverBitmap, laneBitmap, value, extendedIndex);
    }

    public String maneuverLabel(boolean ukrainian) {
        Maneuver sample = MANEUVERS[maneuverIndex];
        return ukrainian ? sample.uk : sample.en;
    }

    public String lanes() {
        return LANES[laneIndex];
    }

    public int distance() {
        return DISTANCES[distanceIndex];
    }

    public String sourceStreet() {
        return STREETS[streetIndex];
    }

    public String effectiveStreet() {
        return transliterate
                ? HudTextTransliterator.transform(sourceStreet(), HudTextTransliterator.UKRAINIAN)
                : sourceStreet();
    }

    public String trafficLightLabel(boolean ukrainian) {
        Label label = TRAFFIC_LIGHTS[trafficLightIndex];
        return ukrainian ? label.uk : label.en;
    }

    public String extendedLabel(boolean ukrainian) {
        ExtendedSample sample = EXTENDED[extendedIndex];
        return ukrainian ? sample.uk : sample.en;
    }

    public String extendedField() {
        return EXTENDED[extendedIndex].field;
    }

    public String extendedValue() {
        return EXTENDED[extendedIndex].value;
    }

    public String expected(boolean ukrainian) {
        if (mode == Mode.EXTENDED) {
            return extendedLabel(ukrainian) + " · " + extendedField() + " = " + extendedValue();
        }
        return maneuverLabel(ukrainian) + " · " + lanes() + " · " + distance()
                + " m · " + effectiveStreet() + " · " + trafficLightLabel(ukrainian);
    }

    /** Number of bounded Extended fixtures exposed by the diagnostic UI. */
    public static int extendedCount() {
        return EXTENDED.length;
    }

    public HudState toHudState() {
        HudState state = new HudState();
        boolean baseline = mode == Mode.EXTENDED;
        Maneuver maneuver = baseline ? MANEUVERS[0] : MANEUVERS[maneuverIndex];
        String laneText = baseline ? EXTENDED_BASELINE_LANES : lanes();
        int meters = baseline ? EXTENDED_BASELINE_DISTANCE : distance();
        String street = baseline ? EXTENDED_BASELINE_STREET : effectiveStreet();

        state.hudCheck = this;
        state.distanceToIntersection = meters;
        state.maneuverId = maneuver.nativeId;
        // Keep the semantic source ID available to manual mapping even when
        // Stock suppresses artwork. HudCheckPayload selects blank S72 for f8
        // independently; source identity must not be lost with the PNG.
        state.turnBitmapId = maneuver.pngId;
        state.turnBitmapMode = HudState.TURN_BITMAP_OEM;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = 0;
        state.timeToDestination = 0;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = laneCount(laneText);
        state.roadName = street;
        state.directionText = "";
        state.laneString = laneText.replace(" ", "");
        state.guidePoint = "";
        state.navigationRatio = 0.0d;
        state.includeNativeArrow = true;
        state.includeLaneBitmap = !baseline && laneBitmap;
        state.turnBitmapHiddenLocked = false;
        return state;
    }

    private HudCheckState replace(int maneuver, int lanes, int distance, int street,
                                  int trafficLight, int extended) {
        return new HudCheckState(mode, running, automatic, maneuver, lanes, distance, street,
                trafficLight, maneuverBitmap, laneBitmap, transliterate, extended);
    }

    private static int laneCount(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        int count = 0;
        for (String token : value.split("\\|", -1)) if (!token.trim().isEmpty()) count++;
        return count;
    }

    private static int wrap(int value, int size) {
        return Math.floorMod(value, size);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HudCheckState)) return false;
        HudCheckState that = (HudCheckState) other;
        return running == that.running && automatic == that.automatic
                && maneuverIndex == that.maneuverIndex && laneIndex == that.laneIndex
                && distanceIndex == that.distanceIndex && streetIndex == that.streetIndex
                && trafficLightIndex == that.trafficLightIndex
                && maneuverBitmap == that.maneuverBitmap && laneBitmap == that.laneBitmap
                && transliterate == that.transliterate && extendedIndex == that.extendedIndex
                && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, running, automatic, maneuverIndex, laneIndex, distanceIndex,
                streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap, transliterate,
                extendedIndex);
    }

    private static class Label {
        final String en;
        final String uk;

        Label(String en, String uk) {
            this.en = en;
            this.uk = uk;
        }
    }

    private static final class Maneuver extends Label {
        final int pngId;
        final int nativeId;

        Maneuver(String en, String uk, int pngId, int nativeId) {
            super(en, uk);
            this.pngId = pngId;
            this.nativeId = nativeId;
        }
    }

    private static final class ExtendedSample extends Label {
        final String field;
        final String value;

        ExtendedSample(String en, String uk, String field, String value) {
            super(en, uk);
            this.field = field;
            this.value = value;
        }
    }
}
