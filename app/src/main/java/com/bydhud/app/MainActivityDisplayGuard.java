package com.bydhud.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;

/** Keeps the control UI on the tablet without moving the separate Waze Surface task. */
final class MainActivityDisplayGuard {
    private static final String REPAIR = "com.bydhud.app.extra.MAIN_DISPLAY_REPAIR";
    private boolean repairRequested;

    static Bundle tabletOptions() {
        return ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle();
    }

    void check(Activity activity, String phase) {
        Intent incoming = activity.getIntent();
        int displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        String detail = "phase=" + phase + " task=" + activity.getTaskId()
                + " display=" + displayId + " target=" + Display.DEFAULT_DISPLAY
                + " launcher=" + (incoming != null && incoming.hasCategory(Intent.CATEGORY_LAUNCHER))
                + " caller=" + activity.getCallingPackage();
        if (activity.isFinishing() || activity.isDestroyed()) {
            log(activity, detail, "closing");
            return;
        }
        if (displayId == Display.DEFAULT_DISPLAY) {
            log(activity, detail, repairRequested || (incoming != null
                    && incoming.getBooleanExtra(REPAIR, false)) ? "confirmed" : "tablet");
            repairRequested = false;
            if (incoming != null) incoming.removeExtra(REPAIR);
            return;
        }
        if (repairRequested || (incoming != null && incoming.getBooleanExtra(REPAIR, false))) {
            log(activity, detail, "unconfirmed_no_retry");
            return;
        }
        repairRequested = true;
        // Retain the guard across recreation, including a rejected corrective launch.
        if (incoming != null) incoming.putExtra(REPAIR, true);
        Intent repair = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(REPAIR, true);
        try {
            // Main's existing singleTask/affinity keeps this separate from WazeSurfaceActivity.
            activity.startActivity(repair, tabletOptions());
            log(activity, detail, "requested");
        } catch (RuntimeException error) {
            log(activity, detail, "failed error=" + error.getClass().getSimpleName());
        }
    }

    private static void log(Activity activity, String detail, String result) {
        AppEventLogger.event(activity, "main_ui_display " + detail + " result=" + result);
    }
}
