package com.bydhud.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded HUD output-check selections shared by Compose and senders. */
public final class HudCheckState {
    public enum Mode { BASIC, EXTENDED, SHANGHAI }

    public enum Field { MANEUVER, LANES, DISTANCE, STREET, TRAFFIC_LIGHT }

    public enum Format { NO_PNG, A, D, B, C }

    public enum CodeTable { BYD, AMAP, HUAWEI }

    private static final int LEGACY_GEOMETRY_FIRST_INDEX = HudCheckGeometry.FIRST_EXTENDED_INDEX;
    private static final int LEGACY_GEOMETRY_END_INDEX = HudCheckLiveScenario.FIRST_EXTENDED_INDEX;
    private static final String EXTENDED_BASELINE_LANES = "S* | S | S* | S | S*";

    private static final Maneuver[] MANEUVERS = {
            new Maneuver("Straight", "Прямо", 9, 11, 9),
            new Maneuver("Left", "Ліворуч", 2, 1, 2),
            new Maneuver("Right", "Праворуч", 3, 2, 3),
            new Maneuver("Slight left", "Плавно ліворуч", 4, 3, 4),
            new Maneuver("Slight right", "Плавно праворуч", 5, 5, 5),
            new Maneuver("Sharp left", "Різко ліворуч", 6, 1, 6),
            new Maneuver("Left U-turn", "Розворот ліворуч", 8, 7, 8),
            new Maneuver("Right U-turn", "Розворот праворуч", 19, 8, 19),
            new Maneuver("Roundabout right exit 2", "Коло, виїзд праворуч 2", 26, 99, 22, 26,
                    RoundaboutExit.Direction.RIGHT, 2),
            new Maneuver("Exit ramp left", "З’їзд ліворуч", 71, 1, 2),
            new Maneuver("Exit ramp right", "З’їзд праворуч", 70, 2, 3)
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
            new ExtendedSample("Road data", "Дані дороги", "RoadInfo · f22", "12"),
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
            new ExtendedSample("Straight", "Прямо", "RoadInfo · f30", ""),
            new ExtendedSample("Slight left", "Плавно ліворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Slight right", "Плавно праворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Left", "Ліворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Right", "Праворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Sharp left", "Різко ліворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Sharp right", "Різко праворуч", "RoadInfo · f30", ""),
            new ExtendedSample("Left U-turn candidate", "Розворот ліворуч · кандидат", "RoadInfo · f30", ""),
            new ExtendedSample("Right U-turn candidate", "Розворот праворуч · кандидат", "RoadInfo · f30", "")
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
    public final Format format;
    public final CodeTable codeTable;

    public HudCheckState() {
        this(Mode.BASIC, false, true, 0, 0, 0, 0, 0, false, false, false, 0,
                Format.NO_PNG, CodeTable.BYD);
    }

    private HudCheckState(Mode mode, boolean running, boolean automatic,
                          int maneuverIndex, int laneIndex, int distanceIndex,
                          int streetIndex, int trafficLightIndex,
                          boolean maneuverBitmap, boolean laneBitmap,
                          boolean transliterate, int extendedIndex, Format format,
                          CodeTable codeTable) {
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
        this.extendedIndex = wrap(extendedIndex, extendedCount());
        this.format = format == null ? Format.NO_PNG : format;
        this.codeTable = codeTable == null ? CodeTable.BYD : codeTable;
    }

    public HudCheckState selectMode(Mode value) {
        Mode selected = value == null ? Mode.BASIC : value;
        return mode == selected
                ? this
                : new HudCheckState(selected, false, automatic, maneuverIndex, laneIndex,
                        distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap,
                        laneBitmap, transliterate, extendedIndex, format, codeTable);
    }

    public HudCheckState selectBasicFormat(Format value) {
        Format selected = value == null ? Format.NO_PNG : value;
        if (mode != Mode.BASIC || running || format == selected) return this;
        return new HudCheckState(mode, false, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap,
                laneBitmap, transliterate, extendedIndex, selected, codeTable);
    }

    public HudCheckState selectCodeTable(CodeTable value) {
        CodeTable selected = value == null ? CodeTable.BYD : value;
        if (mode != Mode.BASIC || running || !allowsCodeTable() || codeTable == selected) return this;
        return new HudCheckState(mode, false, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap,
                laneBitmap, transliterate, extendedIndex, format, selected);
    }

    public CodeTable effectiveCodeTable() {
        return allowsCodeTable() ? codeTable : CodeTable.BYD;
    }

    public boolean allowsCodeTable() {
        return format == Format.A || format == Format.B;
    }

    public boolean effectiveManeuverBitmap() {
        return format == Format.NO_PNG && maneuverBitmap;
    }

    public boolean effectiveLaneBitmap() {
        return format == Format.NO_PNG && laneBitmap;
    }

    /** Candidate C reuses the bounded Instrument lane-code mapping. */
    public List<Integer> effectiveLaneCodes() {
        if (format != Format.C) return Collections.emptyList();
        List<Integer> codes = new ArrayList<>();
        for (HudLaneModel.LaneSpec lane : laneSpecs(effectiveLaneIndex())) {
            int code = lane.instrumentCode();
            if (code < 0) throw new IllegalStateException("HUD Check lane pattern is not representable");
            codes.add(code);
        }
        return Collections.unmodifiableList(codes);
    }

    org.json.JSONObject checkpoint() throws org.json.JSONException {
        return new org.json.JSONObject().put("mode", mode.name()).put("running", running)
                .put("automatic", automatic).put("maneuver", maneuverIndex).put("lane", laneIndex)
                .put("distance", distanceIndex).put("street", streetIndex).put("light", trafficLightIndex)
                .put("maneuverBitmap", maneuverBitmap).put("laneBitmap", laneBitmap)
                .put("transliterate", transliterate).put("extended", extendedIndex)
                .put("format", format.name()).put("codeTable", codeTable.name())
                .put("extendedCount", extendedCount());
    }

    static HudCheckState restore(org.json.JSONObject state) {
        int extended = state.optInt("extended");
        // Older checkpoints include the removed segment/summary probes at indices 23 and 24.
        if (state.optInt("extendedCount", 54) == 54 && extended >= 23) {
            extended = Math.max(23, extended - 2);
        }
        Format format = Format.NO_PNG;
        CodeTable codeTable = CodeTable.BYD;
        try {
            format = Format.valueOf(state.optString("format", Format.NO_PNG.name()));
        } catch (IllegalArgumentException ignored) {
            // Legacy checkpoints stored only legacyBasic; both values migrate to No PNG.
        }
        try {
            codeTable = CodeTable.valueOf(state.optString("codeTable", CodeTable.BYD.name()));
        } catch (IllegalArgumentException ignored) {
            // Keep older or unknown profiles on the established BYD mapping.
        }
        return new HudCheckState(Mode.valueOf(state.optString("mode", "BASIC")),
                state.optBoolean("running"), state.optBoolean("automatic", true),
                state.optInt("maneuver"), state.optInt("lane"), state.optInt("distance"),
                state.optInt("street"), state.optInt("light"), state.optBoolean("maneuverBitmap"),
                state.optBoolean("laneBitmap"), state.optBoolean("transliterate"), extended,
                format, codeTable);
    }

    public HudCheckState toggleRun() {
        if (mode == Mode.SHANGHAI) return this;
        return new HudCheckState(mode, !running, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap,
                transliterate, extendedIndex, format, codeTable);
    }

    public HudCheckState stop() {
        return running ? new HudCheckState(mode, false, automatic, maneuverIndex, laneIndex,
                distanceIndex, streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap,
                transliterate, extendedIndex, format, codeTable) : this;
    }

    public HudCheckState step(Field field, int delta) {
        if (field == null || delta == 0) return this;
        switch (field) {
            case MANEUVER:
                return replace(maneuverIndex + delta, laneIndex, distanceIndex, streetIndex,
                        trafficLightIndex, extendedIndex);
            case LANES:
                return replace(maneuverIndex, nextLaneIndex(laneIndex, delta), distanceIndex, streetIndex,
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
                maneuverBitmap, laneBitmap, transliterate, extendedIndex, format, codeTable);
    }

    public HudCheckState withManeuverBitmap(boolean value) {
        return format != Format.NO_PNG || maneuverBitmap == value ? this
                : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                value, laneBitmap, transliterate, extendedIndex, format, codeTable);
    }

    public HudCheckState withLaneBitmap(boolean value) {
        return format != Format.NO_PNG || laneBitmap == value ? this
                : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                maneuverBitmap, value, transliterate, extendedIndex, format, codeTable);
    }

    public HudCheckState withTransliterate(boolean value) {
        return transliterate == value ? this : new HudCheckState(mode, running, automatic,
                maneuverIndex, laneIndex, distanceIndex, streetIndex, trafficLightIndex,
                maneuverBitmap, laneBitmap, value, extendedIndex, format, codeTable);
    }

    public String maneuverLabel(boolean ukrainian) {
        Maneuver sample = MANEUVERS[maneuverIndex];
        return ukrainian ? sample.uk : sample.en;
    }

    public String lanes() {
        return LANES[effectiveLaneIndex()];
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
        HudCheckLiveScenario live = liveScenario();
        if (live != null) return ukrainian ? live.labelUk : live.labelEn;
        ExtendedSample sample = EXTENDED[extendedIndex];
        return ukrainian ? sample.uk : sample.en;
    }

    public String extendedLabelRussian() {
        HudCheckLiveScenario live = liveScenario();
        return live == null ? extendedLabel(false) : live.labelRu;
    }

    public String extendedField() {
        if (isLivePipelineCheck()) {
            String fields = "RoadInfo · f9";
            if (!effectiveManeuverBitmap()) fields += ", f28";
            if (format == Format.B || format == Format.C) fields += ", f8";
            if (format != Format.D) fields += ", f19–20, f30–32";
            return fields;
        }
        return EXTENDED[extendedIndex].field;
    }

    public boolean isGeometryCheck() {
        return mode == Mode.EXTENDED && extendedIndex >= LEGACY_GEOMETRY_FIRST_INDEX
                && extendedIndex < LEGACY_GEOMETRY_END_INDEX;
    }

    public boolean isLivePipelineCheck() {
        return mode == Mode.EXTENDED && liveScenario() != null;
    }

    /** Extended adds its selected probe to the same Basic input and channel choices. */
    public boolean isLiveCheck() {
        return mode == Mode.BASIC || mode == Mode.EXTENDED;
    }

    /** Shanghai owns its stock navigator input separately. */
    public DirectTbtFrame liveFrame() {
        if (!isLiveCheck()) return null;
        HudCheckLiveScenario live = liveScenario();
        return live == null ? MANEUVERS[maneuverIndex].frame(distance(), effectiveStreet())
                : live.frame().withNavigationText(effectiveStreet(), "");
    }

    HudManeuverGeometry.Selection liveGeometry() {
        DirectTbtFrame frame = liveFrame();
        return frame == null ? null : HudManeuverGeometry.select(frame, true, false);
    }

    String liveGeometrySummary() {
        DirectTbtFrame frame = liveFrame();
        if (frame == null) return "";
        int code = liveNativeManeuver();
        if (format == Format.D) {
            return "f28=" + code + " geometry=omitted source=HUD_CHECK"
                    + (frame.getRoundaboutExit().hasDirection()
                    ? " " + frame.getRoundaboutExit().diagnostics() : "");
        }
        HudManeuverGeometry.Selection geometry = liveGeometry();
        if (geometry == null) return "";
        return "f19=" + geometry.longitude + " f20=" + geometry.latitude
                + " f28=" + code + " f32=" + geometry.heading
                + " geometry=" + geometry.name + " source=HUD_CHECK"
                + (frame.getRoundaboutExit().hasDirection()
                ? " " + frame.getRoundaboutExit().diagnostics() : "");
    }

    int liveNativeManeuver() {
        if (!isLiveCheck()) return -1;
        HudCheckLiveScenario live = liveScenario();
        return live == null
                ? HudCheckManeuverTable.basic(effectiveCodeTable(), maneuverIndex)
                : HudCheckManeuverTable.live(effectiveCodeTable(), live);
    }

    int liveDistanceMeters() {
        DirectTbtFrame frame = liveFrame();
        return frame == null ? -1 : frame.getDistanceMeters();
    }

    String liveScenarioKey() {
        HudCheckLiveScenario live = liveScenario();
        return live == null ? "basic-m" + maneuverIndex : live.key;
    }

    public String extendedValue() {
        HudCheckLiveScenario live = liveScenario();
        if (live != null && format == Format.D) {
            return "f9=" + live.distanceMeters + " · f28=" + liveNativeManeuver();
        }
        return live != null ? live.value()
                : isGeometryCheck() ? HudCheckGeometry.display(extendedIndex)
                : EXTENDED[extendedIndex].value;
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
        return EXTENDED.length + HudCheckLiveScenario.count();
    }

    public HudState toHudState() {
        HudState state = new HudState();
        HudCheckLiveScenario live = liveScenario();
        Maneuver maneuver = MANEUVERS[maneuverIndex];
        String laneText = lanes();
        int meters = live != null ? live.distanceMeters : distance();
        String street = effectiveStreet();

        state.hudCheck = this;
        state.distanceToIntersection = meters;
        state.maneuverId = live == null ? maneuver.nativeId : live.nativeManeuver;
        // Keep the semantic source ID available to manual mapping even when
        // the selected channel omits artwork. HudCheckPayload selects the channel
        // independently; source identity must not be lost with the PNG.
        state.turnBitmapId = live == null ? maneuver.pngId : live.bitmapId;
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
        state.includeLaneBitmap = effectiveLaneBitmap();
        state.turnBitmapHiddenLocked = false;
        return state;
    }

    private HudCheckState replace(int maneuver, int lanes, int distance, int street,
                                  int trafficLight, int extended) {
        return new HudCheckState(mode, running, automatic, maneuver, lanes, distance, street,
                trafficLight, maneuverBitmap, laneBitmap, transliterate, extended, format, codeTable);
    }

    private int nextLaneIndex(int current, int delta) {
        if (format != Format.C) {
            return wrap(wrap(current, LANES.length) + Math.floorMod(delta, LANES.length), LANES.length);
        }
        int[] valid = representableLaneIndexes();
        if (valid.length == 0) return wrap(current, LANES.length);
        int position = 0;
        int effective = effectiveLaneIndex();
        while (position < valid.length && valid[position] != effective) position++;
        if (position == valid.length) position = 0;
        return valid[wrap(position + Math.floorMod(delta, valid.length), valid.length)];
    }

    private int effectiveLaneIndex() {
        if (format != Format.C || lanePatternRepresentable(laneIndex)) return laneIndex;
        int[] valid = representableLaneIndexes();
        return valid.length == 0 ? laneIndex : valid[0];
    }

    private static int[] representableLaneIndexes() {
        int count = 0;
        for (int index = 0; index < LANES.length; index++) {
            if (lanePatternRepresentable(index)) count++;
        }
        int[] indexes = new int[count];
        int next = 0;
        for (int index = 0; index < LANES.length; index++) {
            if (lanePatternRepresentable(index)) indexes[next++] = index;
        }
        return indexes;
    }

    private static boolean lanePatternRepresentable(int index) {
        HudLaneModel.LaneSpec[] lanes = laneSpecs(index);
        if (lanes.length == 0) return false;
        for (HudLaneModel.LaneSpec lane : lanes) {
            if (lane.instrumentCode() < 0) return false;
        }
        return true;
    }

    private static HudLaneModel.LaneSpec[] laneSpecs(int index) {
        String pattern = LANES[wrap(index, LANES.length)];
        HudState state = new HudState();
        state.numOfLanes = laneCount(pattern);
        state.laneString = pattern.replace(" ", "");
        return HudLaneModel.parse(state);
    }

    private HudCheckLiveScenario liveScenario() {
        return mode == Mode.EXTENDED
                ? HudCheckLiveScenario.forExtendedIndex(extendedIndex) : null;
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
                && mode == that.mode && format == that.format && codeTable == that.codeTable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, running, automatic, maneuverIndex, laneIndex, distanceIndex,
                streetIndex, trafficLightIndex, maneuverBitmap, laneBitmap, transliterate,
                extendedIndex, format, codeTable);
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
        final int amapManeuver;
        final int amapBroadcastManeuver;
        final int roundaboutExitNumber;
        final RoundaboutExit.Direction roundaboutDirection;

        Maneuver(String en, String uk, int pngId, int nativeId, int amapManeuver) {
            this(en, uk, pngId, nativeId, amapManeuver, amapManeuver,
                    RoundaboutExit.Direction.UNKNOWN, 0);
        }

        Maneuver(String en, String uk, int pngId, int nativeId, int amapManeuver,
                 int amapBroadcastManeuver,
                 RoundaboutExit.Direction roundaboutDirection, int roundaboutExitNumber) {
            super(en, uk);
            this.pngId = pngId;
            this.nativeId = nativeId;
            this.amapManeuver = amapManeuver;
            this.amapBroadcastManeuver = amapBroadcastManeuver;
            this.roundaboutDirection = roundaboutDirection;
            this.roundaboutExitNumber = roundaboutExitNumber;
        }

        DirectTbtFrame frame(int distanceMeters, String roadText) {
            DirectTbtFrame frame = new DirectTbtFrame(-1, amapManeuver, nativeId,
                    distanceMeters, roadText, "", roadText, null, null,
                    java.util.Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive())
                    .withVehicleTbt(amapBroadcastManeuver, roundaboutExitNumber);
            return roundaboutDirection == RoundaboutExit.Direction.UNKNOWN ? frame
                    : frame.withRoundaboutExit(RoundaboutExit.of(roundaboutDirection,
                    RoundaboutExit.Source.HUD_CHECK, -1, Double.NaN));
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
