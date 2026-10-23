package com.bydhud.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fixed, bounded wire records accepted by ClusterDebug's normal extra. */
final class ClusterDebugPayload {
    static final int MAX_RECORD_BYTES = 252;
    static final String ACTION = "byd.hud.NAVIGATION";
    static final String EXTRA_NORMAL = "normal";

    private static final int FID_STATUS = 0x43E0003A;
    private static final int FID_SIMPLE_ICON = 0x43F01010;
    private static final int FID_DUAL_ICON = 0x43F01030;
    private static final int FID_DISTANCE = 0x43F01018;
    private static final int FID_ROAD = 0x43FA1008;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ClusterDebugPayload() {
    }

    static boolean validGuidance(int icon, int distanceMeters, String road) {
        String text = road == null ? "" : road;
        return InstrumentProxyContract.validGuidance(icon, distanceMeters, text)
                && 5L + text.length() * 2L <= MAX_RECORD_BYTES;
    }

    static Record navigationStatus(int status) {
        if (!InstrumentProxyContract.validStatus(status)) {
            throw new IllegalArgumentException("invalid navigation status");
        }
        return intRecord("navigation_status", FID_STATUS, status);
    }

    static List<Record> guidance(int icon, int distanceMeters, String road) {
        String text = road == null ? "" : road;
        if (!validGuidance(icon, distanceMeters, text)) {
            throw new IllegalArgumentException("invalid or oversized ClusterDebug guidance");
        }
        List<Record> records = new ArrayList<>(4);
        records.add(intRecord("simple_icon", FID_SIMPLE_ICON, icon));
        records.add(intRecord("dual_icon", FID_DUAL_ICON, icon));
        records.add(intRecord("distance", FID_DISTANCE, distanceMeters));
        records.add(record("road", FID_ROAD, text.getBytes(StandardCharsets.UTF_16LE)));
        return records;
    }

    private static Record intRecord(String name, int fid, int value) {
        return record(name, fid, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(value).array());
    }

    private static Record record(String name, int fid, byte[] value) {
        if (value.length + 5 > MAX_RECORD_BYTES || value.length > 255) {
            throw new IllegalArgumentException("ClusterDebug record exceeds 252 bytes");
        }
        ByteBuffer record = ByteBuffer.allocate(value.length + 5).order(ByteOrder.BIG_ENDIAN);
        record.putInt(fid).put((byte) value.length).put(value);
        byte[] bytes = record.array();
        StringBuilder hex = new StringBuilder(bytes.length * 3 - 1);
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) hex.append(',');
            int octet = bytes[index] & 0xFF;
            hex.append(HEX[octet >>> 4]).append(HEX[octet & 0x0F]);
        }
        return new Record(name, hex.toString());
    }

    static final class Record {
        final String name;
        final String normalHex;

        private Record(String name, String normalHex) {
            this.name = name;
            this.normalHex = normalHex;
        }
    }
}
