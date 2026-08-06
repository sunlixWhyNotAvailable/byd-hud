package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

// Receives authenticated route transitions from the patched Waze process.
public final class WazeRouteLifecycleReceiver extends BroadcastReceiver {
    private static final String EXTRA_EVENT_TYPE = "event_type";
    private static final String EXTRA_SPEED_LIMIT = "speed_limit";
    private static final String EXTRA_SPEED_UNIT = "speed_unit";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !WazeRouteLifecycleStore.ACTION.equals(intent.getAction())) {
            return;
        }
        if (!WazeRouteLifecycleStore.isBridgeSupported(context)) {
            log(context, "ignored reason=capability_missing");
            return;
        }
        if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION)
                || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS)) {
            log(context, "ignored reason=missing_extras");
            return;
        }
        int protocol = intent.getIntExtra(
                WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION, -1);
        if (protocol != WazeRouteLifecycleStore.PROTOCOL_VERSION) {
            log(context, "ignored reason=protocol_mismatch value=" + protocol);
            return;
        }

        if ("speed_limit".equals(intent.getStringExtra(EXTRA_EVENT_TYPE))) {
            int limit = intent.getIntExtra(EXTRA_SPEED_LIMIT, -1);
            String unit = intent.getStringExtra(EXTRA_SPEED_UNIT);
            long eventElapsedMs = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
            long receivedElapsedMs = SystemClock.elapsedRealtime();
            if (!intent.hasExtra(EXTRA_SPEED_LIMIT) || limit < 0 || limit > 300
                    || unit == null || unit.length() > 16) {
                log(context, "speed_limit ignored reason=invalid_payload");
                return;
            }
            log(context, "speed_limit value=" + limit + " unit=" + unit
                    + " senderElapsedMs=" + eventElapsedMs
                    + " receiverElapsedMs=" + receivedElapsedMs
                    + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs));
            NavHudLiveSender.onWazeSpeedLimitEvent(
                    context.getApplicationContext(), limit, unit, eventElapsedMs);
            return;
        }
        if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)) {
            log(context, "ignored reason=missing_extras");
            return;
        }

        boolean navigating = intent.getBooleanExtra(
                WazeRouteLifecycleStore.EXTRA_NAVIGATING, false);
        boolean reasonAvailable = intent.hasExtra(WazeRouteLifecycleStore.EXTRA_REASON_CODE);
        int reasonCode = intent.getIntExtra(
                WazeRouteLifecycleStore.EXTRA_REASON_CODE,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE);
        long bridgeGeneration = intent.getLongExtra(
                WazeRouteLifecycleStore.EXTRA_BRIDGE_GENERATION, 0L);
        long eventElapsedMs = intent.getLongExtra(
                WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
        long receivedElapsedMs = SystemClock.elapsedRealtime();
        WazeRouteLifecycleStore.RecordResult result = WazeRouteLifecycleStore.recordBridge(
                context, navigating, reasonCode, reasonAvailable, eventElapsedMs);
        log(context, "event navigating=" + navigating
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
        if (!result.accepted) return;

        dispatchAccepted(context, eventElapsedMs, result);
    }

    static void dispatchAccepted(Context context, long eventElapsedMs,
            WazeRouteLifecycleStore.RecordResult result) {
        NavHudLiveSender.onWazeRouteLifecycleEvent(
                context.getApplicationContext(), result.snapshot.active, result.terminal,
                eventElapsedMs, result.changed, result.reason);
        if (result.snapshot.active
                && HudPrefs.isBootEnabled(context)
                && !HudPrefs.isUserShutdownActive(context)
                && NavCapturePrefs.isHudEnabled(
                context, WazeRouteLifecycleStore.WAZE_PACKAGE)) {
            HudRuntimeService.startPersistent(context, "waze-route-start");
        }
    }

    static void log(Context context, String value) {
        AppEventLogger.event(context, "waze_route_lifecycle " + value);
    }
}
