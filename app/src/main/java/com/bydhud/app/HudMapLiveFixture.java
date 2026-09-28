package com.bydhud.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/** Synthetic route data sent through the production manual and RoadInfo paths. */
final class HudMapLiveFixture {
    static final int SPEED_LIMIT_KPH = 75;
    static final long REMAINING_SECONDS = 99L * 60L * 60L + 59L * 60L;
    static final long REMAINING_METERS = 88_800L;
    static final long SHARED_FIELD_PHASE_MS = 5_000L;

    private HudMapLiveFixture() { }

    static HudState manualState() {
        HudState state = new HudState();
        state.distanceToIntersection = 155;
        state.maneuverId = 2;
        state.turnBitmapId = 3;
        state.currentMaxSpeedLimit = SPEED_LIMIT_KPH;
        state.currentSpeed = 0;
        state.carToDestination = (int) REMAINING_METERS;
        state.timeToDestination = 5_999;
        state.numOfLanes = 4;
        state.roadName = "Typical Street";
        state.directionText = "Turn right";
        state.laneString = "R|R*|L|L";
        state.includeNativeArrow = true;
        state.includeLaneBitmap = true;
        return state;
    }

    static DirectTbtFrame frame(Context context, long nowWallTimeMs, long nowElapsedMs) {
        HudGraphicPayload.setContext(context);
        HudState state = manualState();
        Calendar eta = Calendar.getInstance();
        eta.setTimeInMillis(nowWallTimeMs);
        eta.set(Calendar.HOUR_OF_DAY, 18);
        eta.set(Calendar.MINUTE, 45);
        eta.set(Calendar.SECOND, 0);
        eta.set(Calendar.MILLISECOND, 0);
        long arrival = eta.getTimeInMillis();
        int offsetSeconds = TimeZone.getDefault().getOffset(arrival) / 1000;
        DirectTbtFrame.TravelMetrics metrics = new DirectTbtFrame.TravelMetrics(
                arrival, offsetSeconds, REMAINING_SECONDS, REMAINING_METERS, nowWallTimeMs);
        return new DirectTbtFrame(0, 3, 2, 155,
                "Typical Street", "Turn right", "Typical Street",
                HudGraphicPayload.buildTurnPng(state), HudGraphicPayload.buildLanePng(state), Arrays.asList(
                        new DirectTbtFrame.Lane(3, false, "R"),
                        new DirectTbtFrame.Lane(3, true, "R"),
                        new DirectTbtFrame.Lane(2, false, "L"),
                        new DirectTbtFrame.Lane(2, false, "L")),
                DirectTbtFrame.AlertOverlay.active(1, 100, "Warning", warningPng(context)),
                new DirectTbtFrame.TripMetrics(metrics, metrics),
                new DirectTbtFrame.SpeedLimit(SPEED_LIMIT_KPH, SPEED_LIMIT_KPH, "km/h", nowElapsedMs),
                3, 0);
    }

    static DirectTbtFrame selectFrame(Context context, DirectTbtFrame source, long nowElapsedMs) {
        boolean separate = HudPrefs.wazeAlertField(context) == HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL;
        long elapsed = Math.max(0L, nowElapsedMs - source.getSpeedLimit().getEventElapsedMs());
        boolean warning = HudPrefs.isWazeAlertsEnabled(context)
                && (separate || (elapsed / SHARED_FIELD_PHASE_MS) % 2L == 1L);
        return warning ? source : source.withAlertOverlay(DirectTbtFrame.AlertOverlay.inactive());
    }

    static HudState manualState(Context context, DirectTbtFrame frame) {
        HudState state = manualState();
        if (frame.getAlertOverlay().isActive()
                && HudPrefs.wazeAlertField(context) == HudPrefs.WAZE_ALERT_FIELD_MANEUVER) {
            state.maneuverId = HudState.NATIVE_BLANK_ID;
            state.turnBitmapId = HudState.TURN_BITMAP_BLANK_SOURCE_ID;
            state.distanceToIntersection = frame.getAlertOverlay().getDistanceMeters();
            state.roadName = frame.getAlertOverlay().getDisplayText();
            state.directionText = state.roadName;
        }
        return state;
    }

    private static byte[] warningPng(Context context) {
        Drawable icon = context.getDrawable(android.R.drawable.ic_dialog_alert);
        if (icon == null) throw new IllegalStateException("Missing live-test warning icon");
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            icon.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            icon.draw(new Canvas(bitmap));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Unable to encode live-test warning icon");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }
}
