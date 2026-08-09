package com.bydhud.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SimpleTimeZone;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.text.SimpleDateFormat;

/** Builds complete 0x8001 road-info payloads without owning a transport. */
public final class DirectTbtPayload {
    private static final int NATIVE_BLANK_ID = 99;
    static final int SPEED_PLACEMENT_NONE = 0;
    static final int SPEED_PLACEMENT_MANEUVER = 1;
    static final int SPEED_PLACEMENT_LANES = 2;
    private static final Object OPTIONS_LOCK = new Object();
    private static Options cachedOptions;
    private static int cachedOptionsRevision = Integer.MIN_VALUE;
    private static byte[] cachedBlankS72Png;

    private DirectTbtPayload() {
    }

    static <T> RevisionedSnapshot<T> readStableSnapshot(
            IntSupplier revision, Supplier<T> values) {
        while (true) {
            int before = revision.getAsInt();
            T snapshot = values.get();
            if (before == revision.getAsInt()) {
                return new RevisionedSnapshot<>(before, snapshot);
            }
        }
    }

    static final class RevisionedSnapshot<T> {
        final int revision;
        final T values;

        RevisionedSnapshot(int revision, T values) {
            this.revision = revision;
            this.values = values;
        }
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
                ? (alert.useRouteNative()
                ? safeFrame.getBydManeuver() : NATIVE_BLANK_ID)
                : (blankLaneManeuver || destinationManeuver
                ? NATIVE_BLANK_ID : safeFrame.getBydManeuver());
        int distanceMeters = alert.isActive()
                ? alert.getDistanceMeters() : safeFrame.getDistanceMeters();
        distanceMeters = HudDisplayPolicy.displayDistanceMeters(
                distanceMeters, safeOptions.clampSmallDistance);
        String displayText;
        if (alert.isActive()) {
            displayText = safeOptions.street ? alert.getDisplayText() : "";
        } else if (safeOptions.street && nonBlank(safeFrame.getRoadText())) {
            displayText = safeFrame.getRoadText();
        } else if (safeOptions.textDirection && nonBlank(safeFrame.getCueText())) {
            displayText = safeFrame.getCueText();
        } else {
            displayText = "";
        }
        if (!alert.isActive()) {
            String metricPrefix = metricPrefix(safeFrame, safeOptions);
            if (!metricPrefix.isEmpty()) {
                displayText = displayText.isEmpty()
                        ? metricPrefix : metricPrefix + " " + displayText;
            }
        }

        if (!safeOptions.png) maneuverPng = new byte[0];
        if (!safeOptions.nativeManeuver) nativeManeuver = 0;
        if (!safeOptions.distance) distanceMeters = 0;
        int speedPlacement = speedPlacement(safeFrame, safeOptions);
        boolean maneuverSpeedApplied = false;
        if (speedPlacement != SPEED_PLACEMENT_NONE) {
            int speedLimit = safeFrame.getSpeedLimit().getDisplayValue();
            if (safeOptions.speedLimitMode == HudPrefs.SPEED_LIMIT_COMPOSITE) {
                boolean occupied = speedPlacement == SPEED_PLACEMENT_MANEUVER
                        ? maneuverFieldOccupied(safeFrame, safeOptions)
                        : laneFieldOccupied(safeFrame, safeOptions);
                if (occupied) {
                    byte[] composite = SpeedLimitPng.composite(
                            speedLimit,
                            speedPlacement == SPEED_PLACEMENT_MANEUVER
                                    ? maneuverPng : lanePng,
                            speedPlacement == SPEED_PLACEMENT_MANEUVER
                                    ? safeOptions.speedLimitManeuverOverlaySize
                                    : safeOptions.speedLimitLaneOverlaySize,
                            speedPlacement == SPEED_PLACEMENT_LANES);
                    if (composite != null) {
                        if (speedPlacement == SPEED_PLACEMENT_MANEUVER) {
                            maneuverPng = composite;
                            maneuverSpeedApplied = true;
                        } else {
                            lanePng = composite;
                        }
                    }
                } else {
                    byte[] speedPng = SpeedLimitPng.get(
                            speedLimit, SpeedLimitPng.STANDALONE_SIZE_PX);
                    if (speedPlacement == SPEED_PLACEMENT_MANEUVER) {
                        maneuverPng = speedPng;
                        maneuverSpeedApplied = speedPng.length > 0;
                    } else {
                        lanePng = speedPng;
                    }
                }
            } else {
                byte[] speedPng = SpeedLimitPng.get(speedLimit);
                if (speedPlacement == SPEED_PLACEMENT_MANEUVER) {
                    maneuverPng = speedPng;
                    maneuverSpeedApplied = true;
                } else if (speedPlacement == SPEED_PLACEMENT_LANES) {
                    lanes = Collections.emptyList();
                    lanePng = speedPng;
                }
            }
        }
        String maneuverMode = maneuverSpeedApplied ? "speed_limit"
                : !safeOptions.png ? "disabled"
                : alert.isActive() ? "alert"
                : blankLaneManeuver || blankDestinationManeuver ? "blank_s72"
                : navManeuverPng.length > 0 ? "current" : "empty";

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
        return new Prepared(fields.toByteArray(), maneuverMode, nativeManeuver,
                distanceMeters, displayText, lanes.size(), lanePng.length,
                maneuverPng);
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

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String metricPrefix(DirectTbtFrame frame, Options options) {
        if (options.routeMetricsMode == HudPrefs.ROUTE_METRICS_OFF
                || !options.showEta && !options.showRemainingTime
                && !options.showRemainingDistance) {
            return "";
        }
        DirectTbtFrame.TripMetrics metrics = frame.getTripMetrics();
        DirectTbtFrame.TravelMetrics nextStop = metrics.getNextStop();
        DirectTbtFrame.TravelMetrics wholeRoute = metrics.getWholeRoute();
        boolean preferWholeRoute = options.routeMetricsMode
                == HudPrefs.ROUTE_METRICS_WHOLE_ROUTE;
        StringBuilder values = new StringBuilder();
        DirectTbtFrame.TravelMetrics eta = selectMetric(
                preferWholeRoute, wholeRoute.getArrivalTimeEpochMs() > 0L,
                wholeRoute, nextStop);
        if (options.showEta && eta.getArrivalTimeEpochMs() > 0L) {
            SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.US);
            if (eta.getArrivalZoneOffsetSeconds()
                    != DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS) {
                formatter.setTimeZone(new SimpleTimeZone(
                        eta.getArrivalZoneOffsetSeconds() * 1000, "ETA"));
            }
            appendMetric(values, "ETA: "
                    + formatter.format(new Date(eta.getArrivalTimeEpochMs())));
        }
        DirectTbtFrame.TravelMetrics time = selectMetric(
                preferWholeRoute, wholeRoute.getRemainingTimeSeconds() >= 0L,
                wholeRoute, nextStop);
        if (options.showRemainingTime && time.getRemainingTimeSeconds() >= 0L) {
            long minutes = (time.getRemainingTimeSeconds() + 59L) / 60L;
            appendMetric(values, minutes + " min");
        }
        DirectTbtFrame.TravelMetrics distance = selectMetric(
                preferWholeRoute, wholeRoute.getRemainingDistanceMeters() >= 0L,
                wholeRoute, nextStop);
        if (options.showRemainingDistance && distance.getRemainingDistanceMeters() >= 0L) {
            appendMetric(values, formatDistance(distance.getRemainingDistanceMeters()));
        }
        return values.length() == 0 ? "" : "[" + values + "]";
    }

    private static DirectTbtFrame.TravelMetrics selectMetric(
            boolean preferWholeRoute,
            boolean wholeRouteFieldAvailable,
            DirectTbtFrame.TravelMetrics wholeRoute,
            DirectTbtFrame.TravelMetrics nextStop) {
        return preferWholeRoute && wholeRouteFieldAvailable ? wholeRoute : nextStop;
    }

    private static void appendMetric(StringBuilder values, String value) {
        if (values.length() > 0) values.append(" | ");
        values.append(value);
    }

    private static String formatDistance(long meters) {
        if (meters < 1000L) return meters + " m";
        double kilometers = meters / 1000d;
        String value = String.format(Locale.US, "%.1f", kilometers);
        if (value.endsWith(".0")) value = value.substring(0, value.length() - 2);
        return value + " km";
    }

    static int speedPlacement(DirectTbtFrame frame, Options options) {
        if (frame == null || options == null || !frame.getSpeedLimit().isActive()
                || options.speedLimitMode == HudPrefs.SPEED_LIMIT_OFF) {
            return SPEED_PLACEMENT_NONE;
        }
        if (options.speedLimitMode == HudPrefs.SPEED_LIMIT_MANEUVER) {
            return SPEED_PLACEMENT_MANEUVER;
        }
        if (options.speedLimitMode == HudPrefs.SPEED_LIMIT_LANES) {
            return SPEED_PLACEMENT_LANES;
        }
        boolean maneuverOccupied = maneuverFieldOccupied(frame, options);
        boolean lanesOccupied = laneFieldOccupied(frame, options);
        if (options.speedLimitMode == HudPrefs.SPEED_LIMIT_COMPOSITE) {
            int selector = options.speedLimitCompositePlacement;
            if (selector == HudPrefs.SPEED_LIMIT_COMPOSITE_LANES_ONLY) {
                return SPEED_PLACEMENT_LANES;
            }
            if (selector == HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_MANEUVER
                    || selector == HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_LANES) {
                if (maneuverOccupied != lanesOccupied) {
                    return maneuverOccupied
                            ? SPEED_PLACEMENT_LANES : SPEED_PLACEMENT_MANEUVER;
                }
                return selector == HudPrefs.SPEED_LIMIT_COMPOSITE_FREE_OR_LANES
                        ? SPEED_PLACEMENT_LANES : SPEED_PLACEMENT_MANEUVER;
            }
            return SPEED_PLACEMENT_MANEUVER;
        }
        if (!maneuverOccupied) return SPEED_PLACEMENT_MANEUVER;
        if (!lanesOccupied) return SPEED_PLACEMENT_LANES;
        if (options.speedLimitFreeFallback == HudPrefs.SPEED_LIMIT_FALLBACK_MANEUVER) {
            return SPEED_PLACEMENT_MANEUVER;
        }
        if (options.speedLimitFreeFallback == HudPrefs.SPEED_LIMIT_FALLBACK_LANES) {
            return SPEED_PLACEMENT_LANES;
        }
        return SPEED_PLACEMENT_NONE;
    }

    static boolean speedOverlaysOccupiedField(DirectTbtFrame frame, Options options) {
        if (options != null && options.speedLimitMode == HudPrefs.SPEED_LIMIT_COMPOSITE) {
            return false;
        }
        int placement = speedPlacement(frame, options);
        return placement == SPEED_PLACEMENT_MANEUVER
                ? maneuverFieldOccupied(frame, options)
                : placement == SPEED_PLACEMENT_LANES && laneFieldOccupied(frame, options);
    }

    private static boolean maneuverFieldOccupied(DirectTbtFrame frame, Options options) {
        if (!options.png) return false;
        DirectTbtFrame.AlertOverlay alert = frame.getAlertOverlay();
        return alert.isActive() && alert.getManeuverPng().length > 0
                || !alert.isActive() && frame.getManeuverPng().length > 0;
    }

    private static boolean laneFieldOccupied(DirectTbtFrame frame, Options options) {
        return options.lanes
                && (frame.getLanePng().length > 0 || !frame.getLanes().isEmpty());
    }

    static String shortSha256(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                output.append(String.format(Locale.US, "%02X", digest[index] & 0xff));
            }
            return output.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    static int pngWidth(byte[] png) {
        return pngDimension(png, 16);
    }

    static int pngHeight(byte[] png) {
        return pngDimension(png, 20);
    }

    private static int pngDimension(byte[] png, int offset) {
        if (png == null || png.length < 24
                || png[0] != (byte) 0x89 || png[1] != 0x50
                || png[2] != 0x4e || png[3] != 0x47
                || png[12] != 0x49 || png[13] != 0x48
                || png[14] != 0x44 || png[15] != 0x52) {
            return 0;
        }
        long value = ((long) (png[offset] & 0xff) << 24)
                | ((long) (png[offset + 1] & 0xff) << 16)
                | ((long) (png[offset + 2] & 0xff) << 8)
                | (png[offset + 3] & 0xffL);
        return value > Integer.MAX_VALUE ? 0 : (int) value;
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
        private final String maneuverMode;
        private final int nativeManeuver;
        private final int distanceMeters;
        private final String displayText;
        private final int laneCount;
        private final int lanePngBytes;
        private final int maneuverPngBytes;
        private final String maneuverPngSha;
        private final int maneuverPngWidth;
        private final int maneuverPngHeight;

        private Prepared(byte[] fields, String maneuverMode, int nativeManeuver,
                         int distanceMeters, String displayText, int laneCount,
                         int lanePngBytes, byte[] maneuverPng) {
            this.fields = fields == null ? new byte[0] : fields;
            this.maneuverMode = maneuverMode == null ? "empty" : maneuverMode;
            this.nativeManeuver = nativeManeuver;
            this.distanceMeters = distanceMeters;
            this.displayText = displayText == null ? "" : displayText;
            this.laneCount = laneCount;
            this.lanePngBytes = lanePngBytes;
            this.maneuverPngBytes = maneuverPng == null ? 0 : maneuverPng.length;
            this.maneuverPngSha = shortSha256(maneuverPng);
            this.maneuverPngWidth = pngWidth(maneuverPng);
            this.maneuverPngHeight = pngHeight(maneuverPng);
        }

        public String maneuverMode() {
            return maneuverMode;
        }

        public int nativeManeuver() {
            return nativeManeuver;
        }

        public int distanceMeters() {
            return distanceMeters;
        }

        public String displayText() {
            return displayText;
        }

        public int laneCount() {
            return laneCount;
        }

        public int lanePngBytes() {
            return lanePngBytes;
        }

        public int maneuverPngBytes() {
            return maneuverPngBytes;
        }

        public String maneuverPngSha() {
            return maneuverPngSha;
        }

        public int maneuverPngWidth() {
            return maneuverPngWidth;
        }

        public int maneuverPngHeight() {
            return maneuverPngHeight;
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
        public static final Options ALL = new Options(
                true, true, true, true, true, true, false,
                false, false, false, false, null);

        public final boolean png;
        public final boolean nativeManeuver;
        public final boolean lanes;
        public final boolean distance;
        public final boolean street;
        public final boolean textDirection;
        public final boolean clampSmallDistance;
        public final int routeMetricsMode;
        public final boolean wholeRouteMetrics;
        public final boolean showEta;
        public final boolean showRemainingTime;
        public final boolean showRemainingDistance;
        public final int speedLimitMode;
        public final int speedLimitFreeFallback;
        public final int speedLimitOverlaySeconds;
        public final int speedLimitCompositePlacement;
        public final int speedLimitManeuverOverlaySize;
        public final int speedLimitLaneOverlaySize;
        private final byte[] blankS72Png;

        public Options(boolean png, boolean nativeManeuver, boolean lanes,
                       boolean distance, boolean street) {
            this(png, nativeManeuver, lanes, distance, street, street, false, null);
        }

        public Options(boolean png, boolean nativeManeuver, boolean lanes,
                       boolean distance, boolean street, boolean textDirection) {
            this(png, nativeManeuver, lanes, distance, street, textDirection, false, null);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, boolean textDirection,
                boolean clampSmallDistance) {
            this(png, nativeManeuver, lanes, distance, street, textDirection,
                    clampSmallDistance, false, false, false, false, null);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, boolean textDirection,
                boolean clampSmallDistance, byte[] blankS72Png) {
            this(png, nativeManeuver, lanes, distance, street, textDirection,
                    clampSmallDistance, false, false, false, false, blankS72Png);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, boolean textDirection,
                boolean clampSmallDistance, boolean wholeRouteMetrics,
                boolean showEta, boolean showRemainingTime,
                boolean showRemainingDistance, byte[] blankS72Png) {
            this(png, nativeManeuver, lanes, distance, street, textDirection,
                    clampSmallDistance, wholeRouteMetrics
                            ? HudPrefs.ROUTE_METRICS_WHOLE_ROUTE
                            : HudPrefs.ROUTE_METRICS_NEXT_STOP,
                    showEta, showRemainingTime, showRemainingDistance,
                    HudPrefs.SPEED_LIMIT_OFF, HudPrefs.SPEED_LIMIT_FALLBACK_OFF,
                    5, blankS72Png);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, boolean textDirection,
                boolean clampSmallDistance, int routeMetricsMode,
                boolean showEta, boolean showRemainingTime,
                boolean showRemainingDistance, int speedLimitMode,
                int speedLimitFreeFallback, int speedLimitOverlaySeconds,
                byte[] blankS72Png) {
            this(png, nativeManeuver, lanes, distance, street, textDirection,
                    clampSmallDistance, routeMetricsMode, showEta, showRemainingTime,
                    showRemainingDistance, speedLimitMode, speedLimitFreeFallback,
                    speedLimitOverlaySeconds,
                    HudPrefs.SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY, 64, 36,
                    blankS72Png);
        }

        Options(boolean png, boolean nativeManeuver, boolean lanes,
                boolean distance, boolean street, boolean textDirection,
                boolean clampSmallDistance, int routeMetricsMode,
                boolean showEta, boolean showRemainingTime,
                boolean showRemainingDistance, int speedLimitMode,
                int speedLimitFreeFallback, int speedLimitOverlaySeconds,
                int speedLimitCompositePlacement,
                int speedLimitManeuverOverlaySize,
                int speedLimitLaneOverlaySize,
                byte[] blankS72Png) {
            this.png = png;
            this.nativeManeuver = nativeManeuver;
            this.lanes = lanes;
            this.distance = distance;
            this.street = street;
            this.textDirection = textDirection;
            this.clampSmallDistance = clampSmallDistance;
            this.routeMetricsMode = routeMetricsMode;
            this.wholeRouteMetrics = routeMetricsMode == HudPrefs.ROUTE_METRICS_WHOLE_ROUTE;
            this.showEta = showEta;
            this.showRemainingTime = showRemainingTime;
            this.showRemainingDistance = showRemainingDistance;
            this.speedLimitMode = speedLimitMode;
            this.speedLimitFreeFallback = speedLimitFreeFallback;
            this.speedLimitOverlaySeconds = speedLimitOverlaySeconds;
            this.speedLimitCompositePlacement = speedLimitCompositePlacement;
            this.speedLimitManeuverOverlaySize = speedLimitManeuverOverlaySize;
            this.speedLimitLaneOverlaySize = speedLimitLaneOverlaySize;
            this.blankS72Png = blankS72Png == null ? new byte[0] : blankS72Png.clone();
        }

        public static Options from(Context context) {
            Context safeContext = Objects.requireNonNull(context, "context");
            byte[] blankS72Png;
            synchronized (OPTIONS_LOCK) {
                int revision = HudPrefs.outputOptionsRevision();
                if (cachedOptions != null && cachedOptionsRevision == revision
                        && revision == HudPrefs.outputOptionsRevision()) {
                    return cachedOptions;
                }
                if (cachedBlankS72Png == null) cachedBlankS72Png = loadBlankS72(safeContext);
                blankS72Png = cachedBlankS72Png;
            }
            while (true) {
                RevisionedSnapshot<Options> snapshot = readStableSnapshot(
                        HudPrefs::outputOptionsRevision,
                        () -> new Options(
                                HudPrefs.isPngOutputEnabled(safeContext),
                                HudPrefs.isNativeOutputEnabled(safeContext),
                                HudPrefs.isLaneOutputEnabled(safeContext),
                                HudPrefs.isDistanceOutputEnabled(safeContext),
                                HudPrefs.isStreetOutputEnabled(safeContext),
                                HudPrefs.isTextDirectionOutputEnabled(safeContext),
                                HudPrefs.isSmallDistanceClampEnabled(safeContext),
                                HudPrefs.routeMetricsMode(safeContext),
                                HudPrefs.isEtaOutputEnabled(safeContext),
                                HudPrefs.isRemainingTimeOutputEnabled(safeContext),
                                HudPrefs.isRemainingDistanceOutputEnabled(safeContext),
                                HudPrefs.speedLimitMode(safeContext),
                                HudPrefs.speedLimitFreeFallback(safeContext),
                                HudPrefs.speedLimitOverlaySeconds(safeContext),
                                HudPrefs.speedLimitCompositePlacement(safeContext),
                                HudPrefs.speedLimitManeuverOverlaySize(safeContext),
                                HudPrefs.speedLimitLaneOverlaySize(safeContext),
                                blankS72Png));
                synchronized (OPTIONS_LOCK) {
                    if (snapshot.revision != HudPrefs.outputOptionsRevision()) continue;
                    if (cachedOptions != null
                            && cachedOptionsRevision == snapshot.revision) {
                        return cachedOptions;
                    }
                    cachedOptions = snapshot.values;
                    cachedOptionsRevision = snapshot.revision;
                    return cachedOptions;
                }
            }
        }
    }
}
