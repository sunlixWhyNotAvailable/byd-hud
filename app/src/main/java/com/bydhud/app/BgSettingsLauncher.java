package com.bydhud.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

final class BgSettingsLauncher {
    private static final String BYD_SETTINGS_PACKAGE = "com.byd.appstartmanagement";
    private static final String BYD_SETTINGS_CLASS =
            "com.byd.appstartmanagement.frame.AppStartManagement";

    private BgSettingsLauncher() {
    }

    static void open(Context context) {
        AppEventLogger.event(context, "bg_settings_open_requested");
        boolean opened = false;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(BYD_SETTINGS_PACKAGE, BYD_SETTINGS_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            opened = true;
            AppEventLogger.event(context, "bg_settings_open_success byd");
        } catch (RuntimeException e) {
            AppEventLogger.event(context, "bg_settings_open_byd_failed " + e.getMessage());
        }
        if (opened) {
            return;
        }
        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            fallback.setData(Uri.parse("package:" + context.getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            AppEventLogger.event(context, "bg_settings_open_fallback_app_details");
            Toast.makeText(context, "Opened app settings fallback", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            AppEventLogger.event(context, "bg_settings_open_fail " + e.getMessage());
            Toast.makeText(context, "Cannot open background settings", Toast.LENGTH_LONG).show();
        }
    }
}
