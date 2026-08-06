package com.waze.bydhud;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.Log;

/** Runtime payload injected into locally patched Waze builds. */
public final class RouteStateBridgeV2 implements Application.ActivityLifecycleCallbacks {
    public static final String ACTION =
            "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    public static final String EXTRA_PROTOCOL = "protocol_version";
    public static final String EXTRA_NAVIGATING = "navigating";
    public static final String EXTRA_REASON_CODE = "reason_code";
    public static final String EXTRA_BRIDGE_GENERATION = "bridge_generation";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_SPEED_LIMIT = "speed_limit";
    public static final String EXTRA_SPEED_UNIT = "speed_unit";
    public static final String EXTRA_ELAPSED_MS = "event_elapsed_ms";
    public static final String EXTRA_IDENTITY = "waze_identity";
    public static final int PROTOCOL_VERSION = 2;

    private static final String TAG = "BYD_WAZE_ROUTE_V2";
    private static final String BYD_HUD_PACKAGE = "com.bydhud.app";
    private static final long BRIDGE_GENERATION = SystemClock.elapsedRealtime();
    private static final RouteStateBridgeV2 ACTIVITY_CALLBACKS = new RouteStateBridgeV2();
    private static Context appContext;
    private static boolean callbacksRegistered;
    private static boolean lastNavigating;
    private static int lastReasonCode;
    private static boolean statePublished;
    private static int lastSpeedLimit = Integer.MIN_VALUE;
    private static String lastSpeedUnit = "";

    private RouteStateBridgeV2() {
    }

    public static synchronized void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (!callbacksRegistered && appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(ACTIVITY_CALLBACKS);
            callbacksRegistered = true;
        }
    }

    public static synchronized void emit(boolean navigating, int reasonCode) {
        Context context = appContext;
        if (context == null || (statePublished && lastNavigating == navigating
                && lastReasonCode == reasonCode)) return;
        if (navigating && (!statePublished || !lastNavigating)) {
            lastSpeedLimit = Integer.MIN_VALUE;
            lastSpeedUnit = "";
        }
        try {
            Intent identityIntent = new Intent("com.waze.bydhud.IDENTITY")
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent identity = PendingIntent.getBroadcast(
                    context, 2, identityIntent, flags);
            Intent event = new Intent(ACTION)
                    .setPackage(BYD_HUD_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PROTOCOL, PROTOCOL_VERSION)
                    .putExtra(EXTRA_NAVIGATING, navigating)
                    .putExtra(EXTRA_REASON_CODE, reasonCode)
                    .putExtra(EXTRA_BRIDGE_GENERATION, BRIDGE_GENERATION)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            context.sendBroadcast(event);
            lastNavigating = navigating;
            lastReasonCode = reasonCode;
            statePublished = true;
            Log.i(TAG, "STATE|navigating=" + navigating + "|reason=" + reasonCode
                    + "|generation=" + BRIDGE_GENERATION);
        } catch (Throwable error) {
            Log.e(TAG, "broadcast send failed", error);
        }
    }

    public static synchronized void emit(boolean navigating) {
        emit(navigating, -1);
    }

    public static synchronized void emitSpeedLimit(int limit, String unit) {
        Context context = appContext;
        int safeLimit = limit > 0 ? limit : 0;
        String safeUnit = safeLimit == 0 || unit == null ? "" : unit;
        if (context == null || (lastSpeedLimit == safeLimit && lastSpeedUnit.equals(safeUnit))) {
            return;
        }
        try {
            Intent identityIntent = new Intent("com.waze.bydhud.IDENTITY")
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent identity = PendingIntent.getBroadcast(
                    context, 3, identityIntent, flags);
            Intent event = new Intent(ACTION)
                    .setPackage(BYD_HUD_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PROTOCOL, PROTOCOL_VERSION)
                    .putExtra(EXTRA_EVENT_TYPE, "speed_limit")
                    .putExtra(EXTRA_SPEED_LIMIT, safeLimit)
                    .putExtra(EXTRA_SPEED_UNIT, safeUnit)
                    .putExtra(EXTRA_BRIDGE_GENERATION, BRIDGE_GENERATION)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            context.sendBroadcast(event);
            lastSpeedLimit = safeLimit;
            lastSpeedUnit = safeUnit;
            Log.i(TAG, "SPEED_LIMIT|value=" + safeLimit + "|unit=" + safeUnit);
        } catch (Throwable error) {
            Log.e(TAG, "speed-limit broadcast failed", error);
        }
    }

    private static synchronized void emitAppForeground() {
        Context context = appContext;
        if (context == null) return;
        try {
            Intent identityIntent = new Intent("com.waze.bydhud.IDENTITY")
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent identity = PendingIntent.getBroadcast(
                    context, 4, identityIntent, flags);
            Intent event = new Intent(ACTION)
                    .setPackage(BYD_HUD_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PROTOCOL, PROTOCOL_VERSION)
                    .putExtra(EXTRA_EVENT_TYPE, "app_foreground")
                    .putExtra(EXTRA_BRIDGE_GENERATION, BRIDGE_GENERATION)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            context.sendBroadcast(event);
            Log.i(TAG, "APP_FOREGROUND|generation=" + BRIDGE_GENERATION);
        } catch (Throwable error) {
            Log.e(TAG, "app-foreground broadcast failed", error);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { emitAppForeground(); }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
