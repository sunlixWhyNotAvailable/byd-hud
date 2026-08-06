package com.bydhud.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) return;
        PendingIntent identity;
        int protocol;
        boolean navigating;
        long eventElapsedMs;
        try {
            identity = intent.getParcelableExtra(EXTRA_IDENTITY);
            protocol = intent.getIntExtra(
                    WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION, -1);
            navigating = intent.getBooleanExtra(
                    WazeRouteLifecycleStore.EXTRA_NAVIGATING, false);
            eventElapsedMs = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
        } catch (RuntimeException malformed) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=malformed_extras");
            return;
        }
        if (!trustedIdentity(context, identity)) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=untrusted_identity");
            return;
        }
        if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION)
                || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS)) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=missing_extras");
            return;
        }
        if (protocol != PROTOCOL_VERSION) {
            WazeRouteLifecycleReceiver.log(
                    context, "v2 ignored reason=protocol_mismatch value=" + protocol);
            return;
        }
        long bridgeGeneration = intent.getLongExtra(
                WazeRouteLifecycleStore.EXTRA_BRIDGE_GENERATION, 0L);
        if ("app_foreground".equals(intent.getStringExtra(EXTRA_EVENT_TYPE))) {
            long receivedElapsedMs = SystemClock.elapsedRealtime();
            WazeRouteLifecycleReceiver.log(context, "v2 app_foreground"
                    + " senderElapsedMs=" + eventElapsedMs
                    + " receiverElapsedMs=" + receivedElapsedMs
                    + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs)
                    + " bridgeGeneration=" + bridgeGeneration);
            NavHudLiveSender.onWazeAppForegroundEvent(
                    context.getApplicationContext(), eventElapsedMs, bridgeGeneration);
            return;
        }
        if ("speed_limit".equals(intent.getStringExtra(EXTRA_EVENT_TYPE))) {
            int limit = intent.getIntExtra(EXTRA_SPEED_LIMIT, -1);
            String unit = intent.getStringExtra(EXTRA_SPEED_UNIT);
            long receivedElapsedMs = SystemClock.elapsedRealtime();
            if (!intent.hasExtra(EXTRA_SPEED_LIMIT) || limit < 0 || limit > 300
                    || unit == null || unit.length() > 16) {
                WazeRouteLifecycleReceiver.log(
                        context, "v2 speed_limit ignored reason=invalid_payload");
                return;
            }
            WazeRouteLifecycleReceiver.log(context, "v2 speed_limit value=" + limit
                    + " unit=" + unit
                    + " senderElapsedMs=" + eventElapsedMs
                    + " receiverElapsedMs=" + receivedElapsedMs
                    + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs));
            NavHudLiveSender.onWazeSpeedLimitEvent(
                    context.getApplicationContext(), limit, unit, eventElapsedMs);
            return;
        }
        if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=missing_extras");
            return;
        }
        boolean reasonAvailable = intent.hasExtra(WazeRouteLifecycleStore.EXTRA_REASON_CODE);
        int reasonCode = intent.getIntExtra(
                WazeRouteLifecycleStore.EXTRA_REASON_CODE,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE);
        long receivedElapsedMs = SystemClock.elapsedRealtime();
        WazeRouteLifecycleStore.RecordResult result = WazeRouteLifecycleStore.recordBridge(
                context, navigating, reasonCode, reasonAvailable, eventElapsedMs);
        WazeRouteLifecycleReceiver.log(context, "v2 event navigating=" + navigating
                + " reasonCode=" + reasonCode
                + " reasonName=" + result.reasonName
                + " routeActive=" + result.snapshot.active
                + " terminal=" + result.terminal
                + " accepted=" + result.accepted
                + " changed=" + result.changed
                + " decision=" + result.reason
                + " senderElapsedMs=" + eventElapsedMs
                + " receiverElapsedMs=" + receivedElapsedMs
                + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs)
                + " bridgeGeneration=" + bridgeGeneration);
        if (result.accepted) {
            WazeRouteLifecycleReceiver.dispatchAccepted(
                    context, eventElapsedMs, result);
        }
    }

    private static boolean trustedIdentity(Context context, PendingIntent identity) {
        if (identity == null
                || !WazeRouteLifecycleStore.WAZE_PACKAGE.equals(identity.getCreatorPackage())) {
            return false;
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    WazeRouteLifecycleStore.WAZE_PACKAGE, 0);
            return identity.getCreatorUid() == info.uid
                    && NavigatorSigningKey.installedUsesLocalKey(
                    context, WazeRouteLifecycleStore.WAZE_PACKAGE)
                    && NavigatorPatchStore.isInstalledWazeLifecycleV2(context);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
