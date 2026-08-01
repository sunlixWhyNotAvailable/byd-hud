package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Receives authenticated route transitions from the patched Waze process.
public final class WazeRouteLifecycleReceiver extends BroadcastReceiver {
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
                || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)
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

        boolean navigating = intent.getBooleanExtra(
                WazeRouteLifecycleStore.EXTRA_NAVIGATING, false);
        long eventElapsedMs = intent.getLongExtra(
                WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
        WazeRouteLifecycleStore.RecordResult result = WazeRouteLifecycleStore.record(
                context, navigating, eventElapsedMs);
        log(context, "event navigating=" + navigating
                + " accepted=" + result.accepted
                + " changed=" + result.changed
                + " reason=" + result.reason
                + " elapsedMs=" + eventElapsedMs);
        if (!result.accepted) return;

        dispatchAccepted(context, navigating, eventElapsedMs, result);
    }

    static void dispatchAccepted(Context context, boolean navigating, long eventElapsedMs,
            WazeRouteLifecycleStore.RecordResult result) {
        NavHudLiveSender.onWazeRouteLifecycleEvent(
                context.getApplicationContext(), navigating, eventElapsedMs,
                result.changed, result.reason);
        if (navigating
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
