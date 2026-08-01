package com.bydhud.gmapswire;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GmapsWireParser {
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int MAX_FIELDS_PER_MESSAGE = 4096;
    private static final int MAX_STEPS = 1024;
    private static final int MAX_ROUTE_LEGS = 64;
    private static final int MAX_TEXT_BYTES = 32 * 1024;
    private static final int MAX_CUE_OPTIONS = 128;
    private static final int MAX_CUE_LINES = 32;
    private static final int MAX_CUE_TOKENS = 256;
    private static final int MAX_LANES = 32;
    private static final int MAX_LANE_ARROWS = 16;

    private static final String[] MANEUVER_NAMES = {
            "UNKNOWN", "DEPART", "NAME_CHANGE", "KEEP_LEFT", "KEEP_RIGHT",
            "TURN_SLIGHT_LEFT", "TURN_SLIGHT_RIGHT", "TURN_NORMAL_LEFT",
            "TURN_NORMAL_RIGHT", "TURN_SHARP_LEFT", "TURN_SHARP_RIGHT",
            "U_TURN_LEFT", "U_TURN_RIGHT", "ON_RAMP_SLIGHT_LEFT",
            "ON_RAMP_SLIGHT_RIGHT", "ON_RAMP_NORMAL_LEFT",
            "ON_RAMP_NORMAL_RIGHT", "ON_RAMP_SHARP_LEFT",
            "ON_RAMP_SHARP_RIGHT", "ON_RAMP_U_TURN_LEFT",
            "ON_RAMP_U_TURN_RIGHT", "OFF_RAMP_SLIGHT_LEFT",
            "OFF_RAMP_SLIGHT_RIGHT", "OFF_RAMP_NORMAL_LEFT",
            "OFF_RAMP_NORMAL_RIGHT", "FORK_LEFT", "FORK_RIGHT", "MERGE_LEFT",
            "MERGE_RIGHT", null, null,
            "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_STRAIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CW_U_TURN",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_RIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_STRAIGHT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_LEFT",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW_U_TURN", "STRAIGHT", "FERRY_BOAT",
            "FERRY_TRAIN", "DESTINATION", "DESTINATION_STRAIGHT",
            "DESTINATION_LEFT", "DESTINATION_RIGHT", "MERGE",
            "ROUNDABOUT_ENTER_AND_EXIT_CW", "ROUNDABOUT_ENTER_AND_EXIT_CCW",
            "ON_RAMP_KEEP_LEFT", "ON_RAMP_KEEP_RIGHT", "OFF_RAMP_SHARP_LEFT",
            "OFF_RAMP_SHARP_RIGHT", "OFF_RAMP_KEEP_LEFT", "OFF_RAMP_KEEP_RIGHT",
            "OFF_RAMP_U_TURN_LEFT", "OFF_RAMP_U_TURN_RIGHT",
            "ROUNDABOUT_ENTER_CW", "ROUNDABOUT_EXIT_CW", "ROUNDABOUT_ENTER_CCW",
            "ROUNDABOUT_EXIT_CCW"
    };

    private GmapsWireParser() {
    }

    public static void selfCheck() {
        byte[] payload = new byte[]{
                0x1a, 0x1b, 0x12, 0x19, 0x1a, 0x17,
                0x08, 0x07,
                0x12, 0x13,
                0x12, 0x0b, 0x12, 0x02, 0x08, 0x08,
                0x1a, 0x02, 0x08, 0x1e, 0x42, 0x01, 0x78,
                0x18, 0x00, 0x22, 0x02, 0x08, 0x05
        };
        try {
            Map<String, Object> value = summarize(payload);
            if (!"route_state".equals(value.get("shape"))
                    || !Long.valueOf(7).equals(value.get("routeRevision"))
                    || !Integer.valueOf(1).equals(value.get("stepCount"))
                    || !Integer.valueOf(8).equals(value.get("maneuverEnum"))
                    || !"TURN_NORMAL_RIGHT".equals(value.get("maneuverName"))
                    || !Boolean.TRUE.equals(value.get("maneuverKnown"))
                    || !Long.valueOf(30).equals(value.get("distanceMeters"))
                    || !Long.valueOf(5).equals(value.get("routeMetricSeconds"))
                    || !"x".equals(value.get("contentDescription"))) {
                throw new IllegalStateException("unexpected parser result: " + value);
            }
            List<String> cue = parseCueOption(new byte[]{
                    0x0a, 0x0a, 0x0a, 0x08, 0x0a, 0x06, 0x0a, 0x04,
                    0x52, 0x6f, 0x61, 0x64
            });
            if (cue.size() != 1 || !"Road".equals(cue.get(0))) {
                throw new IllegalStateException("unexpected cue parser result: " + cue);
            }
            List<Map<String, Object>> lanes = parseLaneGuidance(new byte[]{
                    0x0a, 0x0d, 0x0a, 0x06, 0x08, 0x05, 0x10, 0x02, 0x18, 0x01,
                    0x15, 0x00, 0x00, (byte) 0x80, 0x3f
            });
            if (lanes.size() != 1 || castList(lanes.get(0).get("arrows")).size() != 1) {
                throw new IllegalStateException("unexpected lane parser result: " + lanes);
            }
            Map<String, Object> arrow = castMap(castList(lanes.get(0).get("arrows")).get(0));
            if (!"NORMAL".equals(arrow.get("shape"))
                    || !"RIGHT".equals(arrow.get("side"))
                    || !Boolean.TRUE.equals(arrow.get("recommended"))) {
                throw new IllegalStateException("unexpected lane parser result: " + lanes);
            }
        } catch (Exception error) {
            throw new IllegalStateException("protobuf self-check failed", error);
        }
    }

    public static Map<String, Object> summarize(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("invalid payload size");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Field> root = parse(payload);
        byte[] navigationContainer = firstBytes(root, 3);
        if (navigationContainer == null) return shape(result, "unknown");

        List<Field> container = parse(navigationContainer);
        byte[] navigationEnvelope = firstBytes(container, 2);
        if (navigationEnvelope == null) {
            return shape(result, "navigation_container_without_envelope");
        }
        List<Field> envelope = parse(navigationEnvelope);
        byte[] routeBytes = firstBytes(envelope, 3);
        if (routeBytes == null) {
            return shape(result, firstBytes(envelope, 4) == null
                    ? "navigation_envelope_unknown" : "navigation_terminal");
        }

        List<Field> route = parse(routeBytes);
        List<byte[]> routeLegBytes = allBytes(route, 2);
        if (routeLegBytes.isEmpty()) return shape(result, "route_without_state");
        if (routeLegBytes.size() > MAX_ROUTE_LEGS) throw new IOException("too many route legs");

        List<LegState> routeLegs = new ArrayList<>();
        for (byte[] legBytes : routeLegBytes) routeLegs.add(parseLegState(legBytes));
        LegState currentLeg = routeLegs.get(0);
        List<byte[]> steps = currentLeg.steps;
        int currentIndex = currentLeg.currentIndex;
        shape(result, "route_state");
        result.put("routeRevision", firstVarint(route, 1, -1L));
        result.put("routeLegCount", routeLegs.size());
        result.put("stepCount", steps.size());
        result.put("currentStepIndex", currentIndex);
        if (currentLeg.remainingSeconds >= 0L) {
            result.put("routeMetricSeconds", currentLeg.remainingSeconds);
            result.put("nextStopRemainingSeconds", currentLeg.remainingSeconds);
        }
        if (currentLeg.distanceAvailable) {
            result.put("nextStopRemainingDistanceMeters", currentLeg.remainingDistanceMeters);
        }
        putWholeRouteMetrics(result, routeLegs);
        if (currentIndex < 0 || currentIndex >= steps.size()) {
            result.put("currentStepAvailable", false);
            return result;
        }

        result.put("currentStepAvailable", true);
        List<Field> step = parse(steps.get(currentIndex));
        putCueSummary(result, "cue", allBytes(step, 1));
        byte[] maneuver = firstBytes(step, 2);
        if (maneuver != null) {
            long rawValue = firstVarint(parse(maneuver), 1, -1L);
            if (rawValue >= 0 && rawValue <= Integer.MAX_VALUE) {
                int value = (int) rawValue;
                String name = maneuverName(value);
                result.put("maneuverEnum", value);
                result.put("maneuverKnown", name != null);
                if (name != null) result.put("maneuverName", name);
            }
        }
        byte[] distance = firstBytes(step, 3);
        if (distance != null) {
            List<Field> value = parse(distance);
            long meters = firstVarint(value, 1, -1L);
            if (meters >= 0) result.put("distanceMeters", meters);
            putString(result, "distanceValue", firstString(value, 2));
            putString(result, "distanceDisplay", firstString(value, 5));
        }
        putOptionalBoolean(result, "stepFlag4", step, 4);
        putCueSummary(result, "longCue", allBytes(step, 5));
        putOptionalBoolean(result, "laneGuidanceActive", step, 6);
        byte[] laneGuidance = firstBytes(step, 7);
        if (laneGuidance != null) {
            result.put("lanes", parseLaneGuidance(laneGuidance));
        }
        putString(result, "contentDescription", firstString(step, 8));
        putOptionalBoolean(result, "stepFlag9", step, 9);
        putString(result, "maneuverQualifierText", firstString(step, 10));
        return result;
    }

    private static LegState parseLegState(byte[] routeStateBytes) throws IOException {
        List<Field> routeState = parse(routeStateBytes);
        List<byte[]> steps = allBytes(routeState, 2);
        if (steps.size() > MAX_STEPS) throw new IOException("too many route steps");
        int currentIndex = checkedInt(
                firstVarint(routeState, 3, 0L), "current step index");
        byte[] routeMetric = firstBytes(routeState, 4);
        long remainingSeconds = routeMetric == null
                ? -1L : firstVarint(parse(routeMetric), 1, -1L);
        long remainingDistanceMeters = 0L;
        boolean distanceAvailable = false;
        int firstRemainingStep = Math.max(0, currentIndex);
        if (firstRemainingStep < steps.size()) {
            for (int index = firstRemainingStep; index < steps.size(); index++) {
                byte[] distance = firstBytes(parse(steps.get(index)), 3);
                if (distance == null) continue;
                long meters = firstVarint(parse(distance), 1, -1L);
                if (meters < 0L) continue;
                remainingDistanceMeters = checkedAdd(
                        remainingDistanceMeters, meters, "route distance");
                distanceAvailable = true;
            }
        }
        return new LegState(steps, currentIndex, remainingSeconds,
                remainingDistanceMeters, distanceAvailable);
    }

    private static void putWholeRouteMetrics(
            Map<String, Object> result, List<LegState> routeLegs) throws IOException {
        long seconds = 0L;
        long meters = 0L;
        boolean timeAvailable = true;
        boolean distanceAvailable = true;
        for (LegState leg : routeLegs) {
            if (leg.remainingSeconds < 0L) {
                timeAvailable = false;
            } else if (timeAvailable) {
                seconds = checkedAdd(seconds, leg.remainingSeconds, "route time");
            }
            if (!leg.distanceAvailable) {
                distanceAvailable = false;
            } else if (distanceAvailable) {
                meters = checkedAdd(meters, leg.remainingDistanceMeters, "route distance");
            }
        }
        if (timeAvailable) result.put("wholeRouteRemainingSeconds", seconds);
        if (distanceAvailable) result.put("wholeRouteRemainingDistanceMeters", meters);
    }

    private static long checkedAdd(long left, long right, String name) throws IOException {
        if (right < 0L || left > Long.MAX_VALUE - right) {
            throw new IOException("invalid " + name);
        }
        return left + right;
    }

    public static String maneuverName(int value) {
        return value >= 0 && value < MANEUVER_NAMES.length ? MANEUVER_NAMES[value] : null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: <input-bin-dir> <output-jsonl>");
        }
        selfCheck();
        File input = new File(args[0]);
        File output = new File(args[1]);
        if (!input.isDirectory()) throw new IOException("input directory is missing: " + input);
        File[] files = input.listFiles((dir, name) -> name.endsWith(".bin") || name.endsWith(".pb"));
        if (files == null) throw new IOException("cannot list input directory: " + input);
        Arrays.sort(files);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8)) {
            for (File file : files) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("file", file.getName());
                byte[] payload = Files.readAllBytes(file.toPath());
                record.put("bytes", payload.length);
                try {
                    record.putAll(summarize(payload));
                } catch (Exception error) {
                    record.put("shape", "parse_error");
                    record.put("error", error.toString());
                }
                writer.write(toJson(record));
                writer.newLine();
            }
        }
    }

    private static Map<String, Object> shape(Map<String, Object> target, String value) {
        target.put("shape", value);
        return target;
    }

    private static void putString(Map<String, Object> target, String name, String value) {
        if (value != null) target.put(name, value);
    }

    private static void putOptionalBoolean(
            Map<String, Object> target, String name, List<Field> fields, int number) {
        Long value = optionalVarint(fields, number);
        if (value != null) target.put(name, value != 0);
    }

    private static void putCueSummary(
            Map<String, Object> target, String prefix, List<byte[]> options) throws IOException {
        if (options.isEmpty()) return;
        if (options.size() > MAX_CUE_OPTIONS) throw new IOException("too many cue options");
        target.put(prefix + "OptionCount", options.size());
        target.put(prefix + "Lines", parseCueOption(options.get(0)));
        if (options.size() > 1) {
            target.put(prefix + "CompactLines", parseCueOption(options.get(options.size() - 1)));
        }
    }

    private static List<String> parseCueOption(byte[] bytes) throws IOException {
        List<String> lines = new ArrayList<>();
        List<byte[]> groups = allBytes(parse(bytes), 1);
        if (groups.size() > MAX_CUE_LINES) throw new IOException("too many cue lines");
        for (byte[] group : groups) {
            List<byte[]> tokens = allBytes(parse(group), 1);
            if (tokens.size() > MAX_CUE_TOKENS) throw new IOException("too many cue tokens");
            StringBuilder line = new StringBuilder();
            for (byte[] token : tokens) line.append(cueTokenText(parse(token)));
            if (line.length() > 0) lines.add(line.toString());
        }
        return lines;
    }

    private static String cueTokenText(List<Field> token) throws IOException {
        int[] oneOfFields = {1, 4, 5, 6, 7};
        for (int number : oneOfFields) {
            byte[] value = firstBytes(token, number);
            if (value == null) continue;
            List<Field> message = parse(value);
            String text = firstString(message, number == 7 ? 2 : 1);
            return text == null ? "" : text;
        }
        return "";
    }

    private static List<Map<String, Object>> parseLaneGuidance(byte[] bytes) throws IOException {
        List<byte[]> laneBytes = allBytes(parse(bytes), 1);
        if (laneBytes.size() > MAX_LANES) throw new IOException("too many lanes");
        List<Map<String, Object>> lanes = new ArrayList<>();
        for (byte[] laneValue : laneBytes) {
            List<Field> lane = parse(laneValue);
            List<byte[]> arrowBytes = allBytes(lane, 1);
            if (arrowBytes.size() > MAX_LANE_ARROWS) {
                throw new IOException("too many arrows in lane");
            }
            List<Map<String, Object>> arrows = new ArrayList<>();
            for (byte[] arrowValue : arrowBytes) {
                List<Field> arrowFields = parse(arrowValue);
                int shape = checkedInt(firstVarint(arrowFields, 1, 0), "lane shape");
                int side = checkedInt(firstVarint(arrowFields, 2, 0), "lane side");
                Map<String, Object> arrow = new LinkedHashMap<>();
                arrow.put("shapeEnum", shape);
                arrow.put("shape", laneShapeName(shape));
                arrow.put("sideEnum", side);
                arrow.put("side", laneSideName(side));
                arrow.put("recommended", firstVarint(arrowFields, 3, 0) != 0);
                arrows.add(arrow);
            }
            Map<String, Object> laneResult = new LinkedHashMap<>();
            laneResult.put("arrows", arrows);
            Integer metadataBits = firstFixed32(lane, 2);
            if (metadataBits != null) {
                laneResult.put("metadata", Float.intBitsToFloat(metadataBits));
            }
            lanes.add(laneResult);
        }
        return lanes;
    }

    private static String laneShapeName(int value) {
        String[] values = {
                "UNKNOWN", "STRAIGHT", "STRAIGHT_TALL", "SLIGHT", "SLIGHT_TALL",
                "NORMAL", "NORMAL_SHORT", "SHARP", "SHARP_SHORT", "UTURN",
                "UTURN_SHORT", "STUB"
        };
        return value >= 0 && value < values.length ? values[value] : "UNRECOGNIZED";
    }

    private static String laneSideName(int value) {
        String[] values = {"UNSPECIFIED", "LEFT", "RIGHT"};
        return value >= 0 && value < values.length ? values[value] : "UNRECOGNIZED";
    }

    private static List<Field> parse(byte[] bytes) throws IOException {
        List<Field> fields = new ArrayList<>();
        int[] offset = {0};
        while (offset[0] < bytes.length) {
            if (fields.size() >= MAX_FIELDS_PER_MESSAGE) {
                throw new IOException("too many protobuf fields");
            }
            long tag = readVarint(bytes, offset);
            int number = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (number == 0) throw new IOException("invalid protobuf field 0");
            if (wire == 0) {
                fields.add(Field.varint(number, readVarint(bytes, offset)));
            } else if (wire == 1) {
                requireRemaining(bytes, offset[0], 8);
                offset[0] += 8;
            } else if (wire == 2) {
                long lengthValue = readVarint(bytes, offset);
                if (lengthValue < 0 || lengthValue > MAX_PAYLOAD_BYTES) {
                    throw new IOException("invalid protobuf length");
                }
                int length = (int) lengthValue;
                requireRemaining(bytes, offset[0], length);
                byte[] value = new byte[length];
                System.arraycopy(bytes, offset[0], value, 0, length);
                offset[0] += length;
                fields.add(Field.bytes(number, value));
            } else if (wire == 5) {
                requireRemaining(bytes, offset[0], 4);
                int value = (bytes[offset[0]] & 0xff)
                        | ((bytes[offset[0] + 1] & 0xff) << 8)
                        | ((bytes[offset[0] + 2] & 0xff) << 16)
                        | ((bytes[offset[0] + 3] & 0xff) << 24);
                fields.add(Field.fixed32(number, value));
                offset[0] += 4;
            } else {
                throw new IOException("unsupported protobuf wire type " + wire);
            }
        }
        return fields;
    }

    private static long readVarint(byte[] bytes, int[] offset) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (offset[0] >= bytes.length) throw new IOException("truncated protobuf varint");
            int next = bytes[offset[0]++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IOException("protobuf varint is too long");
    }

    private static int checkedInt(long value, String name) throws IOException {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("invalid " + name);
        }
        return (int) value;
    }

    private static void requireRemaining(byte[] bytes, int offset, int length) throws IOException {
        if (length < 0 || offset < 0 || offset > bytes.length - length) {
            throw new IOException("truncated protobuf field");
        }
    }

    private static byte[] firstBytes(List<Field> fields, int number) {
        for (Field field : fields) {
            if (field.number == number && field.bytes != null) return field.bytes;
        }
        return null;
    }

    private static List<byte[]> allBytes(List<Field> fields, int number) {
        List<byte[]> result = new ArrayList<>();
        for (Field field : fields) {
            if (field.number == number && field.bytes != null) result.add(field.bytes);
        }
        return result;
    }

    private static long firstVarint(List<Field> fields, int number, long fallback) {
        for (Field field : fields) {
            if (field.number == number && field.hasVarint) return field.varint;
        }
        return fallback;
    }

    private static Long optionalVarint(List<Field> fields, int number) {
        for (Field field : fields) {
            if (field.number == number && field.hasVarint) return field.varint;
        }
        return null;
    }

    private static Integer firstFixed32(List<Field> fields, int number) {
        for (Field field : fields) {
            if (field.number == number && field.hasFixed32) return field.fixed32;
        }
        return null;
    }

    private static String firstString(List<Field> fields, int number) throws IOException {
        byte[] value = firstBytes(fields, number);
        if (value == null) return null;
        if (value.length > MAX_TEXT_BYTES) throw new IOException("protobuf text is too long");
        return new String(value, StandardCharsets.UTF_8);
    }

    private static String toJson(Map<String, Object> values) {
        StringBuilder output = new StringBuilder();
        appendJsonValue(output, values);
        return output.toString();
    }

    private static void appendJsonValue(StringBuilder output, Object value) {
        if (value instanceof Map<?, ?>) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) output.append(',');
                first = false;
                appendJsonString(output, entry.getKey().toString());
                output.append(':');
                appendJsonValue(output, entry.getValue());
            }
            output.append('}');
            return;
        }
        if (value instanceof Iterable<?>) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) output.append(',');
                first = false;
                appendJsonValue(output, item);
            }
            output.append(']');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value == null) {
            output.append("null");
        } else {
            appendJsonString(output, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    private static void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                output.append('\\').append(character);
            } else if (character == '\n') {
                output.append("\\n");
            } else if (character == '\r') {
                output.append("\\r");
            } else if (character == '\t') {
                output.append("\\t");
            } else if (character < 0x20) {
                output.append(String.format("\\u%04x", (int) character));
            } else {
                output.append(character);
            }
        }
        output.append('"');
    }

    private static final class Field {
        final int number;
        final long varint;
        final boolean hasVarint;
        final byte[] bytes;
        final int fixed32;
        final boolean hasFixed32;

        private Field(
                int number, long varint, boolean hasVarint, byte[] bytes,
                int fixed32, boolean hasFixed32) {
            this.number = number;
            this.varint = varint;
            this.hasVarint = hasVarint;
            this.bytes = bytes;
            this.fixed32 = fixed32;
            this.hasFixed32 = hasFixed32;
        }

        static Field varint(int number, long value) {
            return new Field(number, value, true, null, 0, false);
        }

        static Field bytes(int number, byte[] value) {
            return new Field(number, 0, false, value, 0, false);
        }

        static Field fixed32(int number, int value) {
            return new Field(number, 0, false, null, value, true);
        }
    }

    private static final class LegState {
        final List<byte[]> steps;
        final int currentIndex;
        final long remainingSeconds;
        final long remainingDistanceMeters;
        final boolean distanceAvailable;

        LegState(List<byte[]> steps, int currentIndex, long remainingSeconds,
                 long remainingDistanceMeters, boolean distanceAvailable) {
            this.steps = steps;
            this.currentIndex = currentIndex;
            this.remainingSeconds = remainingSeconds;
            this.remainingDistanceMeters = remainingDistanceMeters;
            this.distanceAvailable = distanceAvailable;
        }
    }
}
