package com.waze.bydhud;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.Log;

/** Runtime payload injected into locally patched Waze builds. */
public final class RouteStateBridgeV2
        extends BroadcastReceiver
        implements Application.ActivityLifecycleCallbacks, Runnable {
    public static final String ACTION =
            "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    public static final String REQUEST_ACTION =
            "com.waze.bydhud.action.REQUEST_NAVIGATION_STATE_V2";
    public static final String EXTRA_PROTOCOL = "protocol_version";
    public static final String EXTRA_NAVIGATING = "navigating";
    public static final String EXTRA_REASON_CODE = "reason_code";
    public static final String EXTRA_BRIDGE_GENERATION = "bridge_generation";
    public static final String EXTRA_BRIDGE_CAPABILITIES = "bridge_capabilities";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_SPEED_LIMIT = "speed_limit";
    public static final String EXTRA_SPEED_UNIT = "speed_unit";
    public static final String EXTRA_ELAPSED_MS = "event_elapsed_ms";
    public static final String EXTRA_IDENTITY = "waze_identity";
    public static final String EXTRA_REQUEST_IDENTITY = "bydhud_identity";
    public static final int PROTOCOL_VERSION = 2;
    public static final int CAP_SPEED_LIMIT_HEARTBEAT = 1;

    private static final String TAG = "BYD_WAZE_ROUTE_V2";
    private static final String BYD_HUD_PACKAGE = "com.bydhud.app";
    private static final long SPEED_LIMIT_HEARTBEAT_MS = 20_000L;
    private static final long BRIDGE_GENERATION = SystemClock.elapsedRealtime();
    private static final RouteStateBridgeV2 ACTIVITY_CALLBACKS = new RouteStateBridgeV2();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static Context appContext;
    private static boolean callbacksRegistered;
    private static boolean requestReceiverRegistered;
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
        if (!requestReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(REQUEST_ACTION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                            ACTIVITY_CALLBACKS, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(ACTIVITY_CALLBACKS, filter);
                }
                requestReceiverRegistered = true;
            } catch (Throwable error) {
                Log.e(TAG, "state-request receiver registration failed", error);
            }
        }
    }

    public static synchronized void emit(boolean navigating, int reasonCode) {
        Context context = appContext;
        if (!navigating) cancelSpeedLimitHeartbeat();
        if (context == null || (statePublished && lastNavigating == navigating
                && lastReasonCode == reasonCode)) return;
        if (navigating && (!statePublished || !lastNavigating)) {
            cancelSpeedLimitHeartbeat();
            lastSpeedLimit = Integer.MIN_VALUE;
            lastSpeedUnit = "";
        }
        if (sendRouteState(context, navigating, reasonCode, "")) {
            lastNavigating = navigating;
            lastReasonCode = reasonCode;
            statePublished = true;
        }
    }

    public static synchronized void emit(boolean navigating) {
        emit(navigating, -1);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !REQUEST_ACTION.equals(intent.getAction())) return;
        PendingIntent identity;
        int protocol;
        try {
            identity = intent.getParcelableExtra(EXTRA_REQUEST_IDENTITY);
            protocol = intent.getIntExtra(EXTRA_PROTOCOL, -1);
        } catch (RuntimeException malformed) {
            Log.w(TAG, "STATE_REQUEST_IGNORED|reason=malformed");
            return;
        }
        if (identity == null
                || !matchesStateRequest(identity.getCreatorPackage(), protocol)) {
            Log.w(TAG, "STATE_REQUEST_IGNORED|reason=untrusted");
            return;
        }
        resendCurrentState();
    }

    public static boolean matchesStateRequest(String creatorPackage, int protocol) {
        return protocol == PROTOCOL_VERSION && BYD_HUD_PACKAGE.equals(creatorPackage);
    }

    private static synchronized void resendCurrentState() {
        Context context = appContext;
        if (context == null || !statePublished) {
            Log.i(TAG, "STATE_REQUEST_IGNORED|reason=no_state");
            return;
        }
        sendRouteState(context, lastNavigating, lastReasonCode, "state_snapshot");
    }

    private static boolean sendRouteState(Context context, boolean navigating,
            int reasonCode, String eventType) {
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
                    .putExtra(EXTRA_BRIDGE_CAPABILITIES, CAP_SPEED_LIMIT_HEARTBEAT)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            if (!eventType.isEmpty()) event.putExtra(EXTRA_EVENT_TYPE, eventType);
            context.sendBroadcast(event);
            Log.i(TAG, (eventType.isEmpty() ? "STATE" : "STATE_SNAPSHOT")
                    + "|navigating=" + navigating + "|reason=" + reasonCode
                    + "|generation=" + BRIDGE_GENERATION);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "broadcast send failed", error);
            return false;
        }
    }

    public static synchronized void emitSpeedLimit(int limit, String unit) {
        Context context = appContext;
        int safeLimit = limit > 0 ? limit : 0;
        String safeUnit = safeLimit == 0 || unit == null ? "" : unit;
        if (safeLimit == 0) cancelSpeedLimitHeartbeat();
        if (context == null) {
            return;
        }
        if (lastSpeedLimit == safeLimit && lastSpeedUnit.equals(safeUnit)) {
            return;
        }
        lastSpeedLimit = safeLimit;
        lastSpeedUnit = safeUnit;
        sendSpeedLimit(context, safeLimit, safeUnit);
        if (safeLimit > 0) scheduleSpeedLimitHeartbeat();
    }

    private static void sendSpeedLimit(Context context, int safeLimit, String safeUnit) {
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
                    .putExtra(EXTRA_BRIDGE_CAPABILITIES, CAP_SPEED_LIMIT_HEARTBEAT)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            context.sendBroadcast(event);
            Log.i(TAG, "SPEED_LIMIT|value=" + safeLimit + "|unit=" + safeUnit);
        } catch (Throwable error) {
            Log.e(TAG, "speed-limit broadcast failed", error);
        }
    }

    private static void scheduleSpeedLimitHeartbeat() {
        MAIN_HANDLER.removeCallbacks(ACTIVITY_CALLBACKS);
        if (statePublished && lastNavigating && lastSpeedLimit > 0) {
            MAIN_HANDLER.postDelayed(ACTIVITY_CALLBACKS, SPEED_LIMIT_HEARTBEAT_MS);
        }
    }

    private static void cancelSpeedLimitHeartbeat() {
        MAIN_HANDLER.removeCallbacks(ACTIVITY_CALLBACKS);
    }

    @Override
    public void run() {
        synchronized (RouteStateBridgeV2.class) {
            Context context = appContext;
            if (context == null || !statePublished || !lastNavigating
                    || lastSpeedLimit <= 0) {
                cancelSpeedLimitHeartbeat();
                return;
            }
            sendSpeedLimit(context, lastSpeedLimit, lastSpeedUnit);
            scheduleSpeedLimitHeartbeat();
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
                    .putExtra(EXTRA_BRIDGE_CAPABILITIES, CAP_SPEED_LIMIT_HEARTBEAT)
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
