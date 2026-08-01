package com.waze.bydhud;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/** Runtime payload injected into locally patched Waze builds. */
public final class RouteStateBridgeV2 {
    public static final String ACTION =
            "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    public static final String EXTRA_PROTOCOL = "protocol_version";
    public static final String EXTRA_NAVIGATING = "navigating";
    public static final String EXTRA_ELAPSED_MS = "event_elapsed_ms";
    public static final String EXTRA_IDENTITY = "waze_identity";
    public static final int PROTOCOL_VERSION = 2;

    private static final String TAG = "BYD_WAZE_ROUTE_V2";
    private static final String BYD_HUD_PACKAGE = "com.bydhud.app";
    private static Context appContext;
    private static boolean lastNavigating;
    private static boolean statePublished;

    private RouteStateBridgeV2() {
    }

    public static synchronized void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static synchronized void emit(boolean navigating) {
        Context context = appContext;
        if (context == null || (statePublished && lastNavigating == navigating)) return;
        try {
            Intent identityIntent = new Intent("com.waze.bydhud.IDENTITY")
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent identity = PendingIntent.getBroadcast(
                    context, 2, identityIntent, flags);
            Intent event = new Intent(ACTION)
                    .setPackage(BYD_HUD_PACKAGE)
                    .putExtra(EXTRA_PROTOCOL, PROTOCOL_VERSION)
                    .putExtra(EXTRA_NAVIGATING, navigating)
                    .putExtra(EXTRA_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .putExtra(EXTRA_IDENTITY, identity);
            context.sendBroadcast(event);
            lastNavigating = navigating;
            statePublished = true;
            Log.i(TAG, navigating ? "START" : "END");
        } catch (Throwable error) {
            Log.e(TAG, "broadcast send failed", error);
        }
    }
}
