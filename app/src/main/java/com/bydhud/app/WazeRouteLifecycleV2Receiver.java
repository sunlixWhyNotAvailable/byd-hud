package com.bydhud.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/** Receives lifecycle protocol v2 from a structurally compatible installed Waze build. */
public final class WazeRouteLifecycleV2Receiver extends BroadcastReceiver {
    static final String ACTION = "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    static final String REQUEST_ACTION =
            "com.waze.bydhud.action.REQUEST_NAVIGATION_STATE_V2";
    static final String EXTRA_IDENTITY = "waze_identity";
    static final String EXTRA_REQUEST_IDENTITY = "bydhud_identity";
    static final String EXTRA_EVENT_TYPE = "event_type";
    static final String EXTRA_SPEED_LIMIT = "speed_limit";
    static final String EXTRA_SPEED_UNIT = "speed_unit";
    static final int PROTOCOL_VERSION = WazeRouteLifecycleStore.V2_PROTOCOL_VERSION;
    static boolean requestCurrentState(Context context, String reason) {
        if (context == null) return false;
        Context appContext = context.getApplicationContext();
        try {
            Intent identityIntent = new Intent(
                    "com.bydhud.app.action.WAZE_STATE_REQUEST_IDENTITY")
                    .setPackage(appContext.getPackageName());
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent identity = PendingIntent.getBroadcast(
                    appContext, 5, identityIntent, pendingFlags);
            Intent request = new Intent(REQUEST_ACTION)
                    .setPackage(WazeRouteLifecycleStore.WAZE_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION,
                            PROTOCOL_VERSION)
                    .putExtra(EXTRA_REQUEST_IDENTITY, identity);
            appContext.sendBroadcast(request);
            WazeRouteLifecycleReceiver.log(appContext,
                    "v2 state request sent trigger=" + cleanReason(reason));
            return true;
        } catch (RuntimeException error) {
            WazeRouteLifecycleReceiver.log(appContext,
                    "v2 state request failed error=" + error.getClass().getSimpleName()
                            + " trigger=" + cleanReason(reason));
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final long receiverEntryElapsedMs = SystemClock.elapsedRealtime();
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
        final WazeRouteTiming timing;
        try {
            if ("app_foreground".equals(eventType)) {
                timing = new WazeRouteTiming(
                        "v2", eventType, eventElapsedMs, bridgeGeneration,
                        receiverEntryElapsedMs, false);
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
                timing = new WazeRouteTiming(
                        "v2", eventType, eventElapsedMs, bridgeGeneration,
                        receiverEntryElapsedMs, false);
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
                timing = new WazeRouteTiming(
                        "v2", eventType, eventElapsedMs, bridgeGeneration,
                        receiverEntryElapsedMs, navigating);
                delivery = () -> WazeRouteLifecycleReceiver.handleRoute(
                        appContext, navigating, reasonCode, reasonAvailable, eventElapsedMs,
                        bridgeGeneration, bridgeCapabilities, "v2 ", timing, eventType);
            }
        } catch (RuntimeException malformed) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=malformed_extras");
            return;
        }

        // Keep only the protocol-bound caller identity fence; no signer/catalog trust
        // decision is performed on the event executor or on the direct route path.
        if (!trustedIdentity(appContext, identity)) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=untrusted_identity");
            return;
        }
        WazeRouteLifecycleStore.noteV2BridgeObserved(appContext);
        NavHudLiveSender.noteWazeV2BridgeObserved(appContext);
        WazeRouteLifecycleReceiver.enqueue(appContext, goAsync(), "v2", delivery, timing);
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
            int installedUid = context.getPackageManager().getApplicationInfo(
                    WazeRouteLifecycleStore.WAZE_PACKAGE, 0).uid;
            return matchesIdentityMetadata(identity.getCreatorPackage(),
                    identity.getCreatorUid(), installedUid);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean matchesIdentityMetadata(String creatorPackage, int creatorUid,
            int installedUid) {
        return WazeRouteLifecycleStore.WAZE_PACKAGE.equals(creatorPackage)
                && creatorUid == installedUid;
    }

    private static String cleanReason(String reason) {
        if (reason == null) return "";
        return reason.replace('\n', '_').replace('\r', '_');
    }
}
