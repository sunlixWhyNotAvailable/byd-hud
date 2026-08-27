package com.bydhud.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Bounded diagnostic SOME/IP payloads; no lifecycle or ownership side effects. */
public final class HudCheckPayload {
    private static final long ROAD_SERVICE_ID = 0x000B010A00010000L;
    private static final long TRAFFIC_INFO_SERVICE_ID = 0x000B000700070000L;
    private static final long MATRIX_SERVICE_ID = 0x000B820282020000L;
    private static final long STATISTIC_SERVICE_ID = 0x000B000D000D0000L;
    private static final long ROUTE_METADATA_SERVICE_ID = 0x000B000E000E0000L;

    private static final long MAP_PATH_TOPIC = 0x4010A00018002L;
    private static final long MAP_TOPIC = 0x4010A00018003L;
    private static final long TRAFFIC_INFO_TOPIC = 0x4000700078003L;
    private static final long MATRIX_TRAFFIC_LIGHT_TOPIC = 0x4820282028001L;
    private static final long MATRIX_NAV_ACTION_CAMERA_TOPIC = 0x482028202800BL;
    private static final long STATISTIC_SEGMENTS_TOPIC = 0x0004000D000D8001L;
    private static final long STATISTIC_SEGMENTS_2_TOPIC = 0x0004000D000D8002L;
    private static final long STATISTIC_SUMMARY_TOPIC = 0x0004000E000E8001L;
    private static final int EXTENDED_MAP_INDEX = 15;
    private static final int EXTENDED_NAV_MAP_INDEX = 16;
    private static final int EXTENDED_TRAFFIC_SPEED_INDEX = 17;
    private static final int EXTENDED_TRAFFIC_CAMERA_INDEX = 18;
    private static final int EXTENDED_TRAFFIC_SECTION_INDEX = 19;
    private static final int EXTENDED_CAMERA_INDEX = 20;
    private static final int EXTENDED_LANES_INDEX = 21;
    private static final int EXTENDED_LIGHT_INDEX = 22;
    private static final int EXTENDED_SEGMENTS_INDEX = 23;
    private static final int EXTENDED_SUMMARY_INDEX = 24;

    private static final long[] AUXILIARY_SERVICES = {
            ROAD_SERVICE_ID, TRAFFIC_INFO_SERVICE_ID, MATRIX_SERVICE_ID,
            STATISTIC_SERVICE_ID, ROUTE_METADATA_SERVICE_ID
    };

    private HudCheckPayload() {
    }

    /** Builds one real RoadInfo event with only the selected diagnostic fields. */
    public static byte[] buildRoadInfo(Context context, HudCheckState source) {
        HudCheckState state = source == null ? new HudCheckState() : source;
        if (context != null) HudGraphicPayload.setContext(context);
        HudState hud = state.toHudState();
        boolean extended = state.mode == HudCheckState.Mode.EXTENDED;
        byte[] lanePng = !extended && state.laneBitmap
                ? buildLanePngSafely(context, hud) : new byte[0];
        byte[] maneuverPng = !extended && state.maneuverBitmap
                ? HudGraphicPayload.buildTurnPng(hud)
                : HudGraphicPayload.buildOemTurnPng(HudState.TURN_BITMAP_BLANK_SOURCE_ID);

        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeVarintField(fields, 2, 2);
        writeVarintField(fields, 5, hud.numOfLanes);
        writeVarintField(fields, 6, 6);
        writeBytesField(fields, 7, lanePng);
        writeBytesField(fields, 8, maneuverPng);
        writeVarintField(fields, 9, hud.distanceToIntersection);
        writeStringField(fields, 10, hud.roadName);
        writeVarintField(fields, 16, 2);
        writeVarintField(fields, 28, hud.maneuverId);
        writeStringField(fields, 29, HudLaneModel.field29Value(hud));

        // Every snapshot clears optional fields first; this prevents a previous
        // extended case's ETA/camera/geometry from lingering in a receiver.
        writeExtendedNeutralFields(fields);
        if (extended) writeExtendedFields(fields, state.extendedIndex);
        return wrap(fields.toByteArray());
    }

    /** Returns auxiliary events for the current extended case; Basic has none. */
    public static List<Packet> auxiliaryPackets(Context context, HudCheckState source) {
        HudCheckState state = source == null ? new HudCheckState() : source;
        if (state.mode != HudCheckState.Mode.EXTENDED) return Collections.emptyList();
        if (context != null) HudGraphicPayload.setContext(context);
        List<Packet> result = new ArrayList<>();
        switch (state.extendedIndex) {
            case EXTENDED_MAP_INDEX:
                result.add(new Packet(ROAD_SERVICE_ID, MAP_PATH_TOPIC,
                        buildMapPathPayload(diagnosticMapPng())));
                break;
            case EXTENDED_NAV_MAP_INDEX:
                result.add(new Packet(ROAD_SERVICE_ID, MAP_TOPIC,
                        buildNavigationMapPayload(diagnosticMapPng())));
                break;
            case EXTENDED_TRAFFIC_SPEED_INDEX:
                result.add(new Packet(TRAFFIC_INFO_SERVICE_ID, TRAFFIC_INFO_TOPIC,
                        buildTrafficInfoPayload(60, 0, 0)));
                break;
            case EXTENDED_TRAFFIC_CAMERA_INDEX:
                result.add(new Packet(TRAFFIC_INFO_SERVICE_ID, TRAFFIC_INFO_TOPIC,
                        buildTrafficInfoPayload(0, 40, 120)));
                break;
            case EXTENDED_TRAFFIC_SECTION_INDEX:
                result.add(new Packet(TRAFFIC_INFO_SERVICE_ID, TRAFFIC_INFO_TOPIC,
                        buildTrafficSectionPayload()));
                break;
            case EXTENDED_CAMERA_INDEX:
                result.add(new Packet(MATRIX_SERVICE_ID, MATRIX_NAV_ACTION_CAMERA_TOPIC,
                        buildCameraActionPayload(true)));
                break;
            case EXTENDED_LANES_INDEX:
                result.add(new Packet(MATRIX_SERVICE_ID, 0x000482028202800CL,
                        buildRouteLanesPayload()));
                break;
            case EXTENDED_LIGHT_INDEX:
                result.add(new Packet(MATRIX_SERVICE_ID, MATRIX_TRAFFIC_LIGHT_TOPIC,
                        buildTrafficLightPayload(true)));
                break;
            case EXTENDED_SEGMENTS_INDEX:
                result.add(new Packet(STATISTIC_SERVICE_ID, STATISTIC_SEGMENTS_TOPIC,
                        buildStatisticSegmentPayload(0xD619A0F8L, true, 5.0d, 2.2d)));
                result.add(new Packet(STATISTIC_SERVICE_ID, STATISTIC_SEGMENTS_2_TOPIC,
                        buildStatisticSegmentMarkerPayload(0xE38A6876L)));
                break;
            case EXTENDED_SUMMARY_INDEX:
                result.add(new Packet(ROUTE_METADATA_SERVICE_ID, STATISTIC_SUMMARY_TOPIC,
                        buildStatisticSummaryPayload(true)));
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns type-correct empty events for every disposable auxiliary topic. */
    public static List<Packet> clearAuxiliaryPackets() {
        List<Packet> result = new ArrayList<>();
        byte[] empty = wrap(new byte[0]);
        result.add(new Packet(ROAD_SERVICE_ID, MAP_PATH_TOPIC, empty));
        result.add(new Packet(ROAD_SERVICE_ID, MAP_TOPIC, empty));
        result.add(new Packet(TRAFFIC_INFO_SERVICE_ID, TRAFFIC_INFO_TOPIC, empty));
        result.add(new Packet(MATRIX_SERVICE_ID, MATRIX_TRAFFIC_LIGHT_TOPIC, empty));
        result.add(new Packet(MATRIX_SERVICE_ID, MATRIX_NAV_ACTION_CAMERA_TOPIC, empty));
        result.add(new Packet(MATRIX_SERVICE_ID, 0x000482028202800CL,
                buildRouteLanesPayload(true)));
        result.add(new Packet(STATISTIC_SERVICE_ID, STATISTIC_SEGMENTS_TOPIC,
                buildStatisticSegmentPayload(0xD619A0F8L, false, 0.0d, 0.0d)));
        result.add(new Packet(STATISTIC_SERVICE_ID, STATISTIC_SEGMENTS_2_TOPIC,
                buildStatisticSegmentMarkerPayload(0xE38A6876L)));
        result.add(new Packet(ROUTE_METADATA_SERVICE_ID, STATISTIC_SUMMARY_TOPIC,
                buildStatisticSummaryPayload(false)));
        return Collections.unmodifiableList(result);
    }

    public static long[] auxiliaryServiceIds() {
        return AUXILIARY_SERVICES.clone();
    }

    public static int extendedCount() {
        return HudCheckState.extendedCount();
    }

    private static byte[] buildLanePngSafely(Context context, HudState state) {
        // A null context is useful for JVM serialization tests. In production,
        // renderer failures remain visible to the coordinator's send failure path.
        return context == null ? new byte[0] : HudGraphicPayload.buildLanePng(state);
    }

    public static final class Packet {
        public final long serviceId;
        public final long topicId;
        public final byte[] payload;

        Packet(long serviceId, long topicId, byte[] payload) {
            this.serviceId = serviceId;
            this.topicId = topicId;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }
    }

    private static void writeExtendedNeutralFields(ByteArrayOutputStream out) {
        writeVarintField(out, 3, 0);
        writeVarintField(out, 4, 0);
        for (int field = 11; field <= 15; field++) writeVarintField(out, field, 0);
        writeVarintField(out, 17, 0);
        writeVarintField(out, 18, 0);
        writeDoubleField(out, 19, 0.0d);
        writeDoubleField(out, 20, 0.0d);
        writeVarintField(out, 21, 0);
        writeVarintField(out, 22, 0);
        writeVarintField(out, 23, 0);
        writeStringField(out, 24, "");
        writeStringField(out, 25, "");
        writeStringField(out, 26, "");
        writeStringField(out, 27, "");
        writeStringField(out, 30, "");
        writeStringField(out, 31, "");
        writeDoubleField(out, 32, 0.0d);
        writeDoubleField(out, 33, 0.0d);
    }

    private static void writeExtendedFields(ByteArrayOutputStream out, int index) {
        switch (Math.floorMod(index, HudCheckState.extendedCount())) {
            case 0:
                writeStringField(out, 26, "12:34");
                break;
            case 1:
                writeVarintField(out, 3, 12_345);
                writeVarintField(out, 4, 1_200);
                writeStringField(out, 27, "20 min");
                break;
            case 2:
                writeVarintField(out, 3, 12_345);
                break;
            case 3:
                writeVarintField(out, 11, 60);
                break;
            case 4:
                writeVarintField(out, 12, 25);
                writeVarintField(out, 13, 300);
                writeVarintField(out, 14, 120);
                writeVarintField(out, 15, 40);
                writeVarintField(out, 21, 25);
                break;
            case 5:
                writeVarintField(out, 17, 1);
                writeVarintField(out, 18, 120);
                break;
            case 6:
                writeVarintField(out, 23, 1);
                break;
            case 7:
                writeStringField(out, 24,
                        "[{\"id\":\"diag\",\"lat\":31.2304,\"lon\":121.4737,"
                                + "\"name\":\"TEST\",\"type\":\"0101\"}]");
                break;
            case 8:
                writeStringField(out, 25, "121.4737,31.2304");
                break;
            case 9:
                writeDoubleField(out, 19, 121.4737d);
                writeDoubleField(out, 20, 31.2304d);
                break;
            case 10:
                writeVarintField(out, 22, 12);
                break;
            case 11:
                writeStringField(out, 30,
                        "[[121.4737,31.2304,0],[121.4740,31.2307,0]]");
                break;
            case 12:
                writeStringField(out, 31, "121.4740,31.2307,0");
                break;
            case 13:
                writeDoubleField(out, 32, 90.0d);
                break;
            case 14:
                writeDoubleField(out, 33, 0.42d);
                break;
            default:
                break;
        }
    }

    private static byte[] buildMapPathPayload(byte[] png) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeVarintField(fields, 1, 0);
        writeVarintField(fields, 2, 2);
        writeVarintField(fields, 3, 1);
        writeVarintField(fields, 4, 45);
        writeFloatField(fields, 5, 1.5f);
        writeStringField(fields, 6, Base64.getEncoder().encodeToString(png));
        return wrap(fields.toByteArray());
    }

    private static byte[] buildNavigationMapPayload(byte[] png) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        String encoded = Base64.getEncoder().encodeToString(png);
        for (int i = 0; i < encoded.length(); i++) writeVarint(packed, encoded.charAt(i));
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeBytesField(fields, 1, packed.toByteArray());
        return fields.toByteArray();
    }

    private static byte[] buildTrafficInfoPayload(int roadSpeed, int cameraSpeed,
                                                   int cameraDistance) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (roadSpeed > 0) writeVarintField(fields, 8, roadSpeed);
        if (cameraSpeed > 0) writeVarintField(fields, 9, cameraSpeed);
        if (cameraDistance > 0) writeVarintField(fields, 10, cameraDistance);
        return wrap(fields.toByteArray());
    }

    private static byte[] buildTrafficSectionPayload() {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeDoubleField(fields, 11, 121.4737d);
        writeDoubleField(fields, 12, 31.2304d);
        writeDoubleField(fields, 13, 121.4747d);
        writeDoubleField(fields, 14, 31.2314d);
        writeVarintField(fields, 15, 40);
        return wrap(fields.toByteArray());
    }

    private static byte[] buildCameraActionPayload(boolean active) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (active) {
            writeVarintField(fields, 1, 11);
            writeVarintField(fields, 2, 1);
            writeVarintField(fields, 3, 0);
            writeVarintField(fields, 4, 77);
            writeVarintField(fields, 5, 1);
            writeVarintField(fields, 6, 120);
        }
        return wrap(fields.toByteArray());
    }

    private static byte[] buildTrafficLightPayload(boolean active) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (active) {
            long start = System.currentTimeMillis() / 1000L;
            writeVarintField(fields, 1, 1);
            writeDoubleField(fields, 2, 121.4737d);
            writeDoubleField(fields, 3, 31.2304d);
            writeVarintField(fields, 4, 4);
            writeVarintField(fields, 5, start);
            writeVarintField(fields, 6, start + 8);
            writeVarintField(fields, 7, 4);
        }
        return wrap(fields.toByteArray());
    }

    private static byte[] buildRouteLanesPayload() {
        return buildRouteLanesPayload(false);
    }

    private static byte[] buildRouteLanesPayload(boolean clear) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (clear) {
            writeBytesField(fields, 1, new byte[0]);
            writeBytesField(fields, 2, new byte[0]);
            writeBytesField(fields, 4, new byte[0]);
            writeBytesField(fields, 5, new byte[0]);
            writeBytesField(fields, 6, new byte[0]);
        } else {
            writeBytesField(fields, 1, new byte[]{1, 1, 1, 1, 1});
            writeBytesField(fields, 2, new byte[]{1, (byte) 255, 1, (byte) 255, 1});
            writeBytesField(fields, 4, new byte[]{(byte) 255, (byte) 255, (byte) 255,
                    (byte) 255, (byte) 255});
            writeBytesField(fields, 5, new byte[]{0, 0, 0, 0, 0});
            writeBytesField(fields, 6, new byte[]{(byte) 255, 0, (byte) 255, 0, (byte) 255});
        }
        writeDoubleField(fields, 9, System.currentTimeMillis());
        return wrap(fields.toByteArray());
    }

    private static byte[] buildStatisticSegmentPayload(long marker, boolean active,
                                                        double length, double time) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeVarintField(fields, 1, marker);
        writeVarintField(fields, 5, active ? 1 : 0);
        writeDoubleField(fields, 12, active ? length : 0.0d);
        writeDoubleField(fields, 13, active ? time : 0.0d);
        return wrap(fields.toByteArray());
    }

    private static byte[] buildStatisticSegmentMarkerPayload(long marker) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeVarintField(fields, 1, marker);
        return wrap(fields.toByteArray());
    }

    private static byte[] buildStatisticSummaryPayload(boolean active) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeVarintField(fields, 1, 1);
        writeVarintField(fields, 3, active ? 1 : 0);
        // Probe terminal frames retain a fresh timestamp while deactivating
        // the route metadata, preventing a stale session from being treated
        // as current by receivers.
        writeDoubleField(fields, 4, System.currentTimeMillis() * 1_000.0d);
        return wrap(fields.toByteArray());
    }

    private static byte[] diagnosticMapPng() {
        int width = 320;
        int height = 180;
        byte[] raw = new byte[height * (1 + width * 4)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            raw[offset++] = 0;
            for (int x = 0; x < width; x++) {
                raw[offset++] = 0;
                raw[offset++] = 0;
                raw[offset++] = 0;
                raw[offset++] = 0;
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw);
        } catch (IOException e) {
            return new byte[0];
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, 8);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writePngInt(header, width);
        writePngInt(header, height);
        header.write(8);
        header.write(6);
        header.write(0);
        header.write(0);
        header.write(0);
        writePngChunk(png, "IHDR", header.toByteArray());
        writePngChunk(png, "IDAT", compressed.toByteArray());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] wrap(byte[] fields) {
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        writeBytesField(wrapped, 1, fields);
        return wrapped.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field) << 3);
        writeVarint(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int field, String value) {
        writeBytesField(out, field, value == null
                ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        byte[] bytes = value == null ? new byte[0] : value;
        writeVarint(out, (((long) field) << 3) | 2L);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeFloatField(ByteArrayOutputStream out, int field, float value) {
        writeVarint(out, (((long) field) << 3) | 5L);
        int bits = Float.floatToRawIntBits(value);
        for (int i = 0; i < 4; i++) out.write((bits >>> (i * 8)) & 0xff);
    }

    private static void writeDoubleField(ByteArrayOutputStream out, int field, double value) {
        writeVarint(out, (((long) field) << 3) | 1L);
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) out.write((int) ((bits >>> (i * 8)) & 0xffL));
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (true) {
            int bits = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            out.write(remaining == 0 ? bits : bits | 0x80);
            if (remaining == 0) return;
        }
    }

    private static void writePngInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writePngChunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        writePngInt(png, data.length);
        png.write(name, 0, name.length);
        png.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name, 0, name.length);
        crc.update(data, 0, data.length);
        writePngInt(png, (int) crc.getValue());
    }
}
