package com.bydhud.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Builds complete 0x8001 road-info payloads without owning a transport. */
public final class DirectTbtPayload {
    private static final int NATIVE_BLANK_ID = 99;
    private static final Object OPTIONS_LOCK = new Object();
    private static final Options[] OPTIONS_CACHE = new Options[32];
    private static byte[] cachedBlankS72Png;

    private DirectTbtPayload() {
    }

    public static byte[] build(DirectTbtFrame frame, int counter, Options options) {
        return prepare(frame, options).build(counter);
    }

    public static Prepared prepare(DirectTbtFrame frame, Options options) {
        DirectTbtFrame safeFrame = frame == null ? DirectTbtFrame.empty() : frame;
        Options safeOptions = options == null ? Options.ALL : options;
        DirectTbtFrame.AlertOverlay alert = safeFrame.getAlertOverlay();

        List<DirectTbtFrame.Lane> lanes = safeOptions.lanes
                ? safeFrame.getLanes() : Collections.emptyList();
        byte[] lanePng = safeOptions.lanes ? safeFrame.getLanePng() : new byte[0];
        byte[] navManeuverPng = safeFrame.getManeuverPng();
        boolean blankLaneManeuver = !alert.isActive()
                && (lanePng.length > 0 || !lanes.isEmpty())
                && navManeuverPng.length == 0;
        boolean destinationManeuver = !alert.isActive()
                && safeFrame.getAmapManeuver() == 15;
        boolean blankDestinationManeuver = destinationManeuver
                && navManeuverPng.length == 0;

        byte[] maneuverPng = alert.isActive()
                ? alert.getManeuverPng()
                : (blankLaneManeuver || blankDestinationManeuver
                ? safeOptions.blankS72Png.clone() : navManeuverPng);
        int nativeManeuver = alert.isActive()
                ? 0
                : (blankLaneManeuver || destinationManeuver
                ? NATIVE_BLANK_ID : safeFrame.getBydManeuver());
        int distanceMeters = alert.isActive()
                ? alert.getDistanceMeters() : safeFrame.getDistanceMeters();
        String displayText = alert.isActive()
                ? alert.getDisplayText() : safeFrame.getDisplayText();

        if (!safeOptions.png) maneuverPng = new byte[0];
        if (!safeOptions.nativeManeuver) nativeManeuver = 0;
        if (!safeOptions.distance) distanceMeters = 0;
        if (!safeOptions.street) displayText = "";

        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (!lanes.isEmpty()) writeVarintField(fields, 5, lanes.size());
        writeVarintField(fields, 6, lanePng.length > 0 || !lanes.isEmpty() ? 6 : 1);
        writeBytesField(fields, 7, lanePng);
        writeBytesField(fields, 8, maneuverPng);
        writeVarintField(fields, 9, Math.max(0, distanceMeters));
        writeStringField(fields, 10, displayText);
        writeVarintField(fields, 16, 2);
        writeStringField(fields, 26, "");
        writeVarintField(fields, 28, Math.max(0, nativeManeuver));
        if (!lanes.isEmpty()) writeStringField(fields, 29, laneText(lanes));
        return new Prepared(fields.toByteArray());
    }

    public static byte[] buildClear() {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writeVarintField(inner, 16, 1);
        writeVarintField(inner, 6, 255);
        return wrap(inner.toByteArray());
    }

    private static byte[] loadBlankS72(Context context) {
        Bitmap bitmap = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.global_image_hud_sou72_day);
        if (bitmap == null) {
            throw new IllegalStateException("Unable to load S72 blank maneuver drawable");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("Unable to encode S72 blank maneuver drawable");
            }
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static String laneText(List<DirectTbtFrame.Lane> lanes) {
        StringBuilder out = new StringBuilder();
        for (DirectTbtFrame.Lane lane : lanes) {
            int code = lane.getAmapCode();
            out.append(code).append(',')
                    .append(lane.isRecommended() ? code : 255)
                    .append('|');
        }
        return out.toString();
    }

    private static byte[] wrap(byte[] inner) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x0a);
        writeVarint(out, inner.length);
        out.write(inner, 0, inner.length);
        return out.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field) << 3);
        writeVarint(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int field, String value) {
        writeBytesField(out, field,
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        byte[] bytes = value == null ? new byte[0] : value;
        writeVarint(out, (((long) field) << 3) | 2L);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (true) {
            int bits = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            if (remaining == 0) {
                out.write(bits);
                return;
            }
            out.write(bits | 0x80);
        }
    }

    private static int varintSize(long value) {
        int size = 1;
        long remaining = value;
        while ((remaining >>>= 7) != 0) size++;
        return size;
    }

    private static int writeVarint(byte[] out, int offset, long value) {
        long remaining = value;
        int next = offset;
        while (true) {
            int bits = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            out[next++] = (byte) (remaining == 0 ? bits : bits | 0x80);
            if (remaining == 0) return next;
        }
    }

    /** Static frame body reused by the 20 Hz sender; each tick allocates only its final payload. */
    public static final class Prepared {
        private final byte[] fields;

        private Prepared(byte[] fields) {
            this.fields = fields == null ? new byte[0] : fields;
        }

        public byte[] build(int counter) {
            long safeCounter = counter & 0xffL;
            int counterBytes = 1 + varintSize(safeCounter);
            int innerLength = counterBytes + fields.length;
            byte[] payload = new byte[1 + varintSize(innerLength) + innerLength];
            int offset = 0;
            payload[offset++] = 0x0a;
            offset = writeVarint(payload, offset, innerLength);
            payload[offset++] = 0x10;
            offset = writeVarint(payload, offset, safeCounter);
            System.arraycopy(fields, 0, payload, offset, fields.length);
            return payload;
        }
    }

    /** Independent switches for each optional cluster output. */
    public static final class Options {
        public static final Options ALL = new Options(true, true, true, true, true);

        public final boolean png;
        public final boolean nativeManeuver;
        public final boolean lanes;
        public final boolean distance;
        public final boolean street;
        private final byte[] blankS72Png;

        public Options(boolean png, boolean nativeManeuver, boolean lanes,
                       boolean distance, boolean street) {
            this(png, nativeManeuver, lanes, distance, street, null);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, byte[] blankS72Png) {
            this.png = png;
            this.nativeManeuver = nativeManeuver;
            this.lanes = lanes;
            this.distance = distance;
            this.street = street;
            this.blankS72Png = blankS72Png == null ? new byte[0] : blankS72Png.clone();
        }

        public static Options from(Context context) {
            Context safeContext = Objects.requireNonNull(context, "context");
            boolean png = HudPrefs.isPngOutputEnabled(safeContext);
            boolean nativeManeuver = HudPrefs.isNativeOutputEnabled(safeContext);
            boolean lanes = HudPrefs.isLaneOutputEnabled(safeContext);
            boolean distance = HudPrefs.isDistanceOutputEnabled(safeContext);
            boolean street = HudPrefs.isStreetOutputEnabled(safeContext);
            int key = (png ? 1 : 0)
                    | (nativeManeuver ? 2 : 0)
                    | (lanes ? 4 : 0)
                    | (distance ? 8 : 0)
                    | (street ? 16 : 0);
            synchronized (OPTIONS_LOCK) {
                Options cached = OPTIONS_CACHE[key];
                if (cached != null) return cached;
                if (cachedBlankS72Png == null) cachedBlankS72Png = loadBlankS72(safeContext);
                cached = new Options(png, nativeManeuver, lanes, distance, street,
                        cachedBlankS72Png);
                OPTIONS_CACHE[key] = cached;
                return cached;
            }
        }
    }
}
