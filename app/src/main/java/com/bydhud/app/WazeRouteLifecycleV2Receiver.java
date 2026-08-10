package com.bydhud.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/** Receives lifecycle protocol v2 from Waze builds signed by the local patcher key. */
public final class WazeRouteLifecycleV2Receiver extends BroadcastReceiver {
    static final String ACTION = "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    static final String EXTRA_IDENTITY = "waze_identity";
    static final String EXTRA_EVENT_TYPE = "event_type";
    static final String EXTRA_SPEED_LIMIT = "speed_limit";
    static final String EXTRA_SPEED_UNIT = "speed_unit";
    static final int PROTOCOL_VERSION = 2;
    private static final Object TRUST_CACHE_LOCK = new Object();
    private static long trustedVersionCode = Long.MIN_VALUE;
    private static long trustedUpdateMs = Long.MIN_VALUE;
    private static int trustedUid = -1;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) return;
        final Context appContext = context.getApplicationContext();

        final PendingIntent identity;
        final int protocol;
        final String eventType;
        final long eventElapsedMs;
        final long bridgeGeneration;
        final int bridgeCapabilities;
        try {
            if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION)
                    || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS)) {
                WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=missing_extras");
                return;
            }
            identity = intent.getParcelableExtra(EXTRA_IDENTITY);
            protocol = intent.getIntExtra(
                    WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION, -1);
            eventType = intent.getStringExtra(EXTRA_EVENT_TYPE);
            eventElapsedMs = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
            bridgeGeneration = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_BRIDGE_GENERATION, 0L);
            bridgeCapabilities = intent.getIntExtra(
                    WazeRouteLifecycleStore.EXTRA_BRIDGE_CAPABILITIES, 0);
        } catch (RuntimeException malformed) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=malformed_extras");
            return;
        }
        if (protocol != PROTOCOL_VERSION) {
            WazeRouteLifecycleReceiver.log(
                    context, "v2 ignored reason=protocol_mismatch value=" + protocol);
            return;
        }
        if (bridgeGeneration < 0L || bridgeCapabilities < 0) {
            WazeRouteLifecycleReceiver.log(
                    context, "v2 ignored reason=invalid_bridge_metadata");
            return;
        }

        final Runnable delivery;
        try {
            if ("app_foreground".equals(eventType)) {
                delivery = () -> handleForeground(
                        appContext, eventElapsedMs, bridgeGeneration, bridgeCapabilities);
            } else if ("speed_limit".equals(eventType)) {
                if (!intent.hasExtra(EXTRA_SPEED_LIMIT)) {
                    WazeRouteLifecycleReceiver.log(
                            context, "v2 speed_limit ignored reason=invalid_payload");
                    return;
                }
                int limit = intent.getIntExtra(EXTRA_SPEED_LIMIT, -1);
                String unit = intent.getStringExtra(EXTRA_SPEED_UNIT);
                if (limit < 0 || limit > 300 || unit == null || unit.length() > 16) {
                    WazeRouteLifecycleReceiver.log(
                            context, "v2 speed_limit ignored reason=invalid_payload");
                    return;
                }
                delivery = () -> WazeRouteLifecycleReceiver.handleSpeedLimit(
                        appContext, limit, unit, eventElapsedMs, bridgeGeneration,
                        bridgeCapabilities, "v2 ");
            } else {
                if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)) {
                    WazeRouteLifecycleReceiver.log(
                            context, "v2 ignored reason=missing_extras");
                    return;
                }
                boolean navigating = intent.getBooleanExtra(
                        WazeRouteLifecycleStore.EXTRA_NAVIGATING, false);
                boolean reasonAvailable = intent.hasExtra(
                        WazeRouteLifecycleStore.EXTRA_REASON_CODE);
                int reasonCode = intent.getIntExtra(
                        WazeRouteLifecycleStore.EXTRA_REASON_CODE,
                        WazeRouteLifecycleStore.REASON_UNAVAILABLE);
                delivery = () -> WazeRouteLifecycleReceiver.handleRoute(
                        appContext, navigating, reasonCode, reasonAvailable, eventElapsedMs,
                        bridgeGeneration, bridgeCapabilities, "v2 ");
            }
        } catch (RuntimeException malformed) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=malformed_extras");
            return;
        }

        WazeRouteLifecycleReceiver.enqueue(appContext, goAsync(), "v2",
                () -> trustedIdentity(appContext, identity), delivery);
    }

    private static void handleForeground(Context context, long eventElapsedMs,
            long bridgeGeneration, int bridgeCapabilities) {
        long receivedElapsedMs = SystemClock.elapsedRealtime();
        WazeRouteLifecycleReceiver.log(context, "v2 app_foreground"
                + " senderElapsedMs=" + eventElapsedMs
                + " receiverElapsedMs=" + receivedElapsedMs
                + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs)
                + " bridgeGeneration=" + bridgeGeneration
                + " bridgeCapabilities=" + bridgeCapabilities);
        NavHudLiveSender.onWazeAppForegroundEvent(
                context.getApplicationContext(), eventElapsedMs, bridgeGeneration);
    }

    static boolean trustedIdentity(Context context, PendingIntent identity) {
        if (identity == null) return false;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    WazeRouteLifecycleStore.WAZE_PACKAGE, 0);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            int installedUid = applicationInfo != null
                    ? applicationInfo.uid
                    : context.getPackageManager().getApplicationInfo(
                    WazeRouteLifecycleStore.WAZE_PACKAGE, 0).uid;
            if (!matchesIdentityMetadata(identity.getCreatorPackage(), identity.getCreatorUid(),
                    installedUid)) return false;
            long versionCode = packageInfo.getLongVersionCode();
            long updateMs = packageInfo.lastUpdateTime;
            synchronized (TRUST_CACHE_LOCK) {
                if (trustedVersionCode == versionCode
                        && trustedUpdateMs == updateMs
                        && trustedUid == installedUid) {
                    return true;
                }
            }
            if (!NavigatorPatchStore.isInstalledWazeLifecycleV2(context)) return false;
            synchronized (TRUST_CACHE_LOCK) {
                trustedVersionCode = versionCode;
                trustedUpdateMs = updateMs;
                trustedUid = installedUid;
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean matchesIdentityMetadata(String creatorPackage, int creatorUid,
            int installedUid) {
        return WazeRouteLifecycleStore.WAZE_PACKAGE.equals(creatorPackage)
                && creatorUid == installedUid;
    }
}
