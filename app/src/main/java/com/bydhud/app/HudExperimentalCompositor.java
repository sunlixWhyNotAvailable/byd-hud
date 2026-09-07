package com.bydhud.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Bounded live compositor for the approved experimental warning/ETA regions.
 *
 * <p>This class owns no navigation state, transport, preferences, timers, or
 * UI work. A caller supplies already-arbitrated content on its worker thread;
 * this class only creates transparent PNG planes and keeps the last rendered
 * content result. The cache deliberately has no frame identity, sequence, or
 * heartbeat inputs.</p>
 */
public final class HudExperimentalCompositor {
    private final Object cacheLock = new Object();
    private final Renderer renderer;
    private ContentKey lastUpperKey;
    private ContentKey lastLowerKey;
    private byte[] lastUpper;
    private byte[] lastLower;

    /** Uses the Android Canvas renderer in the production worker. */
    public HudExperimentalCompositor() {
        this(HudExperimentalCompositor::renderUncached);
    }

    /**
     * Allows a pure fake renderer to verify cache behavior without Robolectric.
     * Production callers should use the no-argument constructor.
     */
    HudExperimentalCompositor(Renderer renderer) {
        this.renderer = renderer == null ? HudExperimentalCompositor::renderUncached : renderer;
    }

    /** Composes the current content, returning null for a plane with no content. */
    public Result compose(Inputs supplied) {
        Inputs input = supplied == null ? Inputs.empty() : supplied;
        Inputs upper = new Inputs(input.arrival, input.duration, "", input.maneuverPng,
                null, null, null, input.colors);
        Inputs lower = new Inputs("", "", input.remaining, null,
                input.lanePng, input.warningPng, input.warningDistance, input.colors);
        ContentKey upperKey = ContentKey.from(upper);
        ContentKey lowerKey = ContentKey.from(lower);
        synchronized (cacheLock) {
            if (!upperKey.equals(lastUpperKey)) {
                Result rendered = hasMeaningfulF8Content(upper) ? renderer.render(upper) : Result.empty();
                lastUpper = rendered == null ? null : rendered.f8Png();
                lastUpperKey = hasMeaningfulF8Content(upper) && lastUpper == null ? null : upperKey;
            }
            if (!lowerKey.equals(lastLowerKey)) {
                Result rendered = hasMeaningfulF7Content(lower) ? renderer.render(lower) : Result.empty();
                lastLower = rendered == null ? null : rendered.f7Png();
                lastLowerKey = hasMeaningfulF7Content(lower) && lastLower == null ? null : lowerKey;
            }
            return new Result(lastUpper, lastLower);
        }
    }

    /** Pure content predicate used by the sender before scheduling optional planes. */
    public static boolean hasMeaningfulF8Content(Inputs input) {
        if (input == null) return false;
        return meaningful(input.arrival) || meaningful(input.duration)
                || input.maneuverPng.length > 0;
    }

    /** Pure content predicate used by the sender before scheduling optional planes. */
    public static boolean hasMeaningfulF7Content(Inputs input) {
        if (input == null) return false;
        return meaningful(input.remaining) || input.lanePng.length > 0
                || input.warningPng.length > 0 || meaningful(input.warningDistance);
    }

    /**
     * Compares only render-affecting content. It intentionally ignores any
     * frame identity or sender heartbeat because those are not Inputs.
     */
    public static boolean sameContent(Inputs first, Inputs second) {
        return ContentKey.from(first == null ? Inputs.empty() : first)
                .equals(ContentKey.from(second == null ? Inputs.empty() : second));
    }

    private static Result renderUncached(Inputs input) {
        Bitmap eta = null;
        byte[] f8 = null;
        byte[] f7 = null;
        try {
            eta = renderEta(input);
            // A primary-only PNG is not a successful rendering of requested ETA.
            // Reject the partial result so compose() keeps this plane retryable.
            if (isEtaRasterMissing(input, eta != null)) return Result.empty();
            f8 = renderF8(input, eta);
            f7 = renderF7(input, eta);
        } finally {
            if (eta != null && !eta.isRecycled()) eta.recycle();
        }
        return new Result(f8, f7);
    }

    static boolean isEtaRasterMissing(Inputs input, boolean rasterAvailable) {
        return !rasterAvailable && (meaningful(input.arrival)
                || meaningful(input.duration) || meaningful(input.remaining));
    }

    private static byte[] renderF8(Inputs input, Bitmap eta) {
        if (!hasMeaningfulF8Content(input)) return null;
        Bitmap plane = null;
        Bitmap maneuver = null;
        boolean drawn = false;
        try {
            plane = transparentBitmap(HudExperimentalLayout.F8_WIDTH_PX,
                    HudExperimentalLayout.F8_HEIGHT_PX);
            if (plane == null) return null;
            Canvas canvas = new Canvas(plane);
            maneuver = decodePng(input.maneuverPng);
            if (maneuver != null) {
                drawn |= drawPrimaryArt(canvas, maneuver, plane.getWidth(), plane.getHeight(), false);
            }
            if (eta != null) {
                drawn |= drawEtaRows(canvas, eta, false, plane.getHeight(),
                        input.arrival, input.duration, input.remaining);
            }
            return drawn ? encodePng(plane) : null;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            recycle(maneuver);
            recycle(plane);
        }
    }

    private static byte[] renderF7(Inputs input, Bitmap eta) {
        if (!hasMeaningfulF7Content(input)) return null;
        Bitmap plane = null;
        Bitmap lane = null;
        Bitmap warning = null;
        boolean drawn = false;
        try {
            plane = transparentBitmap(HudExperimentalLayout.F7_WIDTH_PX,
                    HudExperimentalLayout.F7_HEIGHT_PX);
            if (plane == null) return null;
            Canvas canvas = new Canvas(plane);
            lane = decodePng(input.lanePng);
            if (lane != null) {
                drawn |= drawPrimaryArt(canvas, lane, plane.getWidth(), plane.getHeight(), true);
            }
            warning = decodePng(input.warningPng);
            drawn |= drawWarning(canvas, warning, input.warningDistance,
                    plane.getWidth(), plane.getHeight(), input.colors.warningDistance);
            if (eta != null) {
                drawn |= drawEtaRows(canvas, eta, true, plane.getHeight(),
                        input.arrival, input.duration, input.remaining);
            }
            return drawn ? encodePng(plane) : null;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            recycle(warning);
            recycle(lane);
            recycle(plane);
        }
    }

    private static Bitmap renderEta(Inputs input) {
        if (!meaningful(input.arrival) && !meaningful(input.duration)
                && !meaningful(input.remaining)) return null;
        Bitmap result = null;
        try {
            Paint metricsPaint = etaPaint(input.colors.arrival);
            float referenceWidth = metricsPaint.measureText(
                    HudExperimentalLayout.DURATION_EDGE_REFERENCE);
            float outerRowsWidth = Math.max(
                    meaningful(input.arrival) ? metricsPaint.measureText(input.arrival) : 0f,
                    meaningful(input.remaining) ? metricsPaint.measureText(input.remaining) : 0f);
            float widestDigit = 0f;
            for (char digit = '0'; digit <= '9'; digit++) {
                widestDigit = Math.max(widestDigit, metricsPaint.measureText(String.valueOf(digit)));
            }
            float durationCapacity = 4f * widestDigit + 3f * metricsPaint.measureText(" ")
                    + Math.max(metricsPaint.measureText("г") + metricsPaint.measureText("хв"),
                    metricsPaint.measureText("h") + metricsPaint.measureText("min"));
            durationCapacity = Math.max(durationCapacity, metricsPaint.measureText(input.duration));
            int rasterWidth = HudExperimentalLayout.valueRasterWidth(
                    outerRowsWidth, durationCapacity, referenceWidth);
            rasterWidth = Math.min(HudExperimentalLayout.MAX_ETA_RASTER_WIDTH, rasterWidth);
            result = Bitmap.createBitmap(rasterWidth,
                    Math.round(HudExperimentalLayout.ETA_LOGICAL_HEIGHT
                            * HudExperimentalLayout.RASTER_SCALE), Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(result);
            drawEtaTextRow(canvas, metricsPaint, input.arrival, 0, rasterWidth,
                    referenceWidth, input.colors.arrival);
            drawEtaTextRow(canvas, metricsPaint, input.duration, 1, rasterWidth,
                    referenceWidth, input.colors.remainingTime);
            drawEtaTextRow(canvas, metricsPaint, input.remaining, 2, rasterWidth,
                    referenceWidth, input.colors.remainingDistance);
            return result;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            recycle(result);
            return null;
        }
    }

    private static void drawEtaTextRow(Canvas canvas, Paint metricsPaint, String value, int row,
                                       int rasterWidth, float referenceWidth, int color) {
        if (!meaningful(value)) return;
        Paint text = etaPaint(color);
        text.setTextAlign(row == 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = HudExperimentalLayout.rowCenter(row)
                * HudExperimentalLayout.RASTER_SCALE
                - (metrics.ascent + metrics.descent) / 2f;
        float rightX = HudExperimentalLayout.rowRasterX(row, rasterWidth, referenceWidth);
        if (row == 1) {
            int separator = value.lastIndexOf(' ');
            if (separator > 0 && separator + 1 < value.length()) {
                String minutes = value.substring(separator + 1);
                String hours = value.substring(0, separator);
                canvas.drawText(minutes, rightX, baseline, text);
                canvas.drawText(hours, HudExperimentalLayout.hoursRightX(
                        rightX, text.measureText(minutes), text.measureText(" ")), baseline, text);
                return;
            }
        }
        canvas.drawText(value, rightX, baseline, text);
    }

    private static boolean drawEtaRows(Canvas canvas, Bitmap eta, boolean lower,
                                       int fieldHeight, String arrival,
                                       String duration, String remaining) {
        boolean drawn = false;
        float centerX = HudExperimentalLayout.etaCenterX(
                HudExperimentalLayout.F8_WIDTH_PX, HudExperimentalLayout.F8_HEIGHT_PX);
        int rasterWidth = eta.getWidth();
        for (int row = 0; row < 3; row++) {
            String value = row == 0 ? arrival : row == 1 ? duration : remaining;
            if (!meaningful(value)) continue;
            int top = HudExperimentalLayout.rowSourceTop(lower, row, eta.getHeight());
            int bottom = HudExperimentalLayout.rowSourceBottom(lower, row, eta.getHeight());
            if (top >= bottom) continue;
            Rect source = new Rect(0, top, rasterWidth, bottom);
            float logicalTop = top / (float) HudExperimentalLayout.RASTER_SCALE;
            float logicalBottom = bottom / (float) HudExperimentalLayout.RASTER_SCALE;
            RectF destination = new RectF(
                    HudExperimentalLayout.valueX(lower, centerX, 0f, rasterWidth, fieldHeight),
                    HudExperimentalLayout.valueY(lower, row, logicalTop, fieldHeight),
                    HudExperimentalLayout.valueX(lower, centerX, rasterWidth, rasterWidth,
                            fieldHeight),
                    HudExperimentalLayout.valueY(lower, row, logicalBottom, fieldHeight));
            canvas.drawBitmap(eta, source, destination,
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            drawn = true;
        }
        return drawn;
    }

    private static boolean drawPrimaryArt(Canvas canvas, Bitmap artwork, int width, int height,
                                          boolean lanes) {
        if (artwork.getWidth() <= 0 || artwork.getHeight() <= 0) return false;
        float top = HudExperimentalLayout.primaryArtTop(height);
        float bottom = HudExperimentalLayout.primaryArtBottom(height);
        float targetHeight = bottom - top;
        float targetWidth = targetHeight * artwork.getWidth() / artwork.getHeight();
        float sidePadding = top;
        float maxWidth = Math.max(1f, width - sidePadding * 2f);
        if (targetWidth > maxWidth) {
            targetWidth = maxWidth;
            targetHeight = targetWidth * artwork.getHeight() / artwork.getWidth();
            top = (height - targetHeight) / 2f;
        }
        float left = lanes
                ? HudExperimentalLayout.laneCenterX(width) - targetWidth / 2f
                : sidePadding;
        canvas.drawBitmap(artwork, null,
                new RectF(left, top, left + targetWidth, top + targetHeight),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return true;
    }

    private static boolean drawWarning(Canvas canvas, Bitmap icon, String warningDistance,
                                      int width, int height, int color) {
        String distance = safeText(warningDistance);
        boolean hasIcon = icon != null && icon.getWidth() > 0 && icon.getHeight() > 0;
        boolean hasDistance = meaningful(distance);
        if (!hasIcon && !hasDistance) return false;

        Paint text = etaPaint(color);
        text.setTextSize(height * 0.38f * HudExperimentalLayout.BASE_WARNING_TEXT_SCALE / 100f);
        float gap = hasIcon && hasDistance ? height * 0.08f : 0f;
        float iconHeight = hasIcon
                ? height * 0.70f * HudExperimentalLayout.BASE_WARNING_ICON_SCALE / 100f : 0f;
        float iconWidth = hasIcon ? iconHeight * icon.getWidth() / icon.getHeight() : 0f;
        float distanceWidth = hasDistance ? text.measureText(distance) : 0f;
        float groupWidth = iconWidth + gap + distanceWidth;
        float centerY = height * HudExperimentalLayout.BASE_WARNING_Y / 100f;
        float left = HudExperimentalLayout.warningCenterX(width) - groupWidth / 2f;
        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (hasIcon) {
            canvas.drawBitmap(icon, null,
                    new RectF(left, centerY - iconHeight / 2f,
                            left + iconWidth, centerY + iconHeight / 2f), bitmapPaint);
        }
        if (hasDistance) {
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(distance, left + iconWidth + gap,
                    centerY - (metrics.ascent + metrics.descent) / 2f, text);
        }
        return true;
    }

    private static Paint etaPaint(int color) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(HudExperimentalLayout.opaqueColor(color));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(HudExperimentalLayout.ETA_LOGICAL_HEIGHT / 3f * 0.80f
                * HudExperimentalLayout.RASTER_SCALE);
        return text;
    }

    private static Bitmap transparentBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        return bitmap;
    }

    private static Bitmap decodePng(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static byte[] encodePng(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null;
        return output.toByteArray();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean meaningful(String value) {
        return !safeText(value).isEmpty();
    }

    /** Four independently persisted colors, normalized to opaque ARGB. */
    public static final class Colors {
        public final int arrival;
        public final int remainingTime;
        public final int remainingDistance;
        public final int warningDistance;

        public Colors(int arrival, int remainingTime, int remainingDistance,
                      int warningDistance) {
            this.arrival = HudExperimentalLayout.opaqueColor(arrival);
            this.remainingTime = HudExperimentalLayout.opaqueColor(remainingTime);
            this.remainingDistance = HudExperimentalLayout.opaqueColor(remainingDistance);
            this.warningDistance = HudExperimentalLayout.opaqueColor(warningDistance);
        }

        public static Colors defaults() {
            return new Colors(Color.WHITE, Color.WHITE, Color.WHITE, Color.YELLOW);
        }

        public static Colors of(int arrival, int remainingTime, int remainingDistance,
                                int warningDistance) {
            return new Colors(arrival, remainingTime, remainingDistance, warningDistance);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Colors)) return false;
            Colors other = (Colors) object;
            return arrival == other.arrival && remainingTime == other.remainingTime
                    && remainingDistance == other.remainingDistance
                    && warningDistance == other.warningDistance;
        }

        @Override
        public int hashCode() {
            int result = arrival;
            result = 31 * result + remainingTime;
            result = 31 * result + remainingDistance;
            return 31 * result + warningDistance;
        }
    }

    /** Immutable render input; primary-art visibility is represented by empty PNGs. */
    public static final class Inputs {
        public final String arrival;
        public final String duration;
        public final String remaining;
        public final byte[] maneuverPng;
        public final byte[] lanePng;
        public final byte[] warningPng;
        public final String warningDistance;
        public final Colors colors;

        public Inputs(String arrival, String duration, String remaining,
                      byte[] maneuverPng, byte[] lanePng, byte[] warningPng,
                      String warningDistance, Colors colors) {
            this.arrival = safeText(arrival);
            this.duration = safeText(duration);
            this.remaining = safeText(remaining);
            this.maneuverPng = cloneBytes(maneuverPng);
            this.lanePng = cloneBytes(lanePng);
            this.warningPng = cloneBytes(warningPng);
            this.warningDistance = safeText(warningDistance);
            this.colors = colors == null ? Colors.defaults() : colors;
        }

        public Inputs(String arrival, String duration, String remaining,
                      byte[] maneuverPng, byte[] lanePng, byte[] warningPng,
                      String warningDistance, int arrivalColor, int remainingTimeColor,
                      int remainingDistanceColor, int warningDistanceColor) {
            this(arrival, duration, remaining, maneuverPng, lanePng, warningPng,
                    warningDistance, Colors.of(arrivalColor, remainingTimeColor,
                            remainingDistanceColor, warningDistanceColor));
        }

        public static Inputs empty() {
            return new Inputs("", "", "", null, null, null, null, Colors.defaults());
        }

        private static byte[] cloneBytes(byte[] bytes) {
            return bytes == null || bytes.length == 0 ? new byte[0] : bytes.clone();
        }
    }

    /** Immutable two-plane result; accessors clone bytes so the cache stays safe. */
    public static final class Result {
        private final byte[] f8Png;
        private final byte[] f7Png;

        public Result(byte[] f8Png, byte[] f7Png) {
            this.f8Png = cloneOrNull(f8Png);
            this.f7Png = cloneOrNull(f7Png);
        }

        public static Result empty() {
            return new Result(null, null);
        }

        public byte[] f8Png() {
            return cloneOrNull(f8Png);
        }

        public byte[] f7Png() {
            return cloneOrNull(f7Png);
        }

        public byte[] getF8Png() {
            return f8Png();
        }

        public byte[] getF7Png() {
            return f7Png();
        }

        public boolean hasF8() {
            return f8Png != null && f8Png.length > 0;
        }

        public boolean hasF7() {
            return f7Png != null && f7Png.length > 0;
        }

        public boolean isEmpty() {
            return !hasF8() && !hasF7();
        }

        private static byte[] cloneOrNull(byte[] bytes) {
            return bytes == null || bytes.length == 0 ? null : bytes.clone();
        }
    }

    /** Small renderer seam used by pure JVM cache tests; not a transport hook. */
    @FunctionalInterface
    public interface Renderer {
        Result render(Inputs input);
    }

    private static final class ContentKey {
        private final String arrival;
        private final String duration;
        private final String remaining;
        private final byte[] maneuverPng;
        private final byte[] lanePng;
        private final byte[] warningPng;
        private final String warningDistance;
        private final Colors colors;

        private ContentKey(Inputs input) {
            arrival = input.arrival;
            duration = input.duration;
            remaining = input.remaining;
            maneuverPng = input.maneuverPng.clone();
            lanePng = input.lanePng.clone();
            warningPng = input.warningPng.clone();
            warningDistance = input.warningDistance;
            colors = input.colors;
        }

        private static ContentKey from(Inputs input) {
            return new ContentKey(input);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof ContentKey)) return false;
            ContentKey other = (ContentKey) object;
            boolean arrivalColorRelevant = meaningful(arrival);
            boolean durationColorRelevant = meaningful(duration);
            boolean remainingColorRelevant = meaningful(remaining);
            boolean warningColorRelevant = meaningful(warningDistance);
            return arrival.equals(other.arrival)
                    && duration.equals(other.duration)
                    && remaining.equals(other.remaining)
                    && warningDistance.equals(other.warningDistance)
                    && Arrays.equals(maneuverPng, other.maneuverPng)
                    && Arrays.equals(lanePng, other.lanePng)
                    && Arrays.equals(warningPng, other.warningPng)
                    && (!arrivalColorRelevant || colors.arrival == other.colors.arrival)
                    && (!durationColorRelevant || colors.remainingTime == other.colors.remainingTime)
                    && (!remainingColorRelevant
                    || colors.remainingDistance == other.colors.remainingDistance)
                    && (!warningColorRelevant
                    || colors.warningDistance == other.colors.warningDistance);
        }

        @Override
        public int hashCode() {
            int result = arrival.hashCode();
            result = 31 * result + duration.hashCode();
            result = 31 * result + remaining.hashCode();
            result = 31 * result + Arrays.hashCode(maneuverPng);
            result = 31 * result + Arrays.hashCode(lanePng);
            result = 31 * result + Arrays.hashCode(warningPng);
            result = 31 * result + warningDistance.hashCode();
            if (meaningful(arrival)) result = 31 * result + colors.arrival;
            if (meaningful(duration)) result = 31 * result + colors.remainingTime;
            if (meaningful(remaining)) result = 31 * result + colors.remainingDistance;
            if (meaningful(warningDistance)) result = 31 * result + colors.warningDistance;
            return result;
        }
    }
}
