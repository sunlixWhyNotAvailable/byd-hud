package com.bydhud.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Receives lifecycle protocol v2 from Waze builds signed by the local patcher key. */
public final class WazeRouteLifecycleV2Receiver extends BroadcastReceiver {
    static final String ACTION = "com.bydhud.app.action.WAZE_NAVIGATION_STATE_V2";
    static final String EXTRA_IDENTITY = "waze_identity";
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
                || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)
                || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS)) {
            WazeRouteLifecycleReceiver.log(context, "v2 ignored reason=missing_extras");
            return;
        }
        if (protocol != PROTOCOL_VERSION) {
            WazeRouteLifecycleReceiver.log(
                    context, "v2 ignored reason=protocol_mismatch value=" + protocol);
            return;
        }
        WazeRouteLifecycleStore.RecordResult result = WazeRouteLifecycleStore.record(
                context, navigating, eventElapsedMs);
        WazeRouteLifecycleReceiver.log(context, "v2 event navigating=" + navigating
                + " accepted=" + result.accepted
                + " changed=" + result.changed
                + " reason=" + result.reason
                + " elapsedMs=" + eventElapsedMs);
        if (result.accepted) {
            WazeRouteLifecycleReceiver.dispatchAccepted(
                    context, navigating, eventElapsedMs, result);
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
