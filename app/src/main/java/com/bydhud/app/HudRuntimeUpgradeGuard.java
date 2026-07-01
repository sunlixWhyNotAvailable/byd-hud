package com.bydhud.app;

//guards post-update runtime state so the first nav session does not reuse stale binders or capture frames.

import android.content.Context;
import android.content.SharedPreferences;

//defines the HudRuntimeUpgradeGuard module boundary so update recovery stays separate from normal startup.
final class HudRuntimeUpgradeGuard {
    private static final String PREFS_NAME = "byd_hud_runtime_upgrade_guard";
    private static final String KEY_LAST_VERSION_CODE = "last_version_code";
    private static final String KEY_LAST_VERSION_NAME = "last_version_name";
    private static final String KEY_REINIT_PENDING = "reinit_pending";
    private static final String KEY_PENDING_REASON = "pending_reason";
    private static final String KEY_PACKAGE_REPLACE_TOKEN = "package_replace_token";

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudRuntimeUpgradeGuard() {
    }

    //detects version changes when broadcasts were missed, while avoiding a false update on fresh install.
    static void recordVersionStart(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = prefs(appContext);
        int previousCode = prefs.getInt(KEY_LAST_VERSION_CODE, 0);
        if (previousCode == 0) {
            prefs.edit()
                    .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME)
                    .apply();
            AppEventLogger.event(appContext, "runtime_upgrade version_initialized reason="
                    + safe(reason) + " version=" + versionLabel());
            return;
        }
        if (previousCode == BuildConfig.VERSION_CODE) {
            return;
        }
        prefs.edit()
                .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME)
                .putBoolean(KEY_REINIT_PENDING, true)
                .putString(KEY_PENDING_REASON, "version-change:" + safe(reason))
                .apply();
        AppEventLogger.event(appContext, "runtime_upgrade version_changed reason="
                + safe(reason) + " previousCode=" + previousCode
                + " version=" + versionLabel());
    }

    //marks package replacement explicitly because this is the strongest signal for stale runtime state.
    static boolean markPackageReplaced(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        boolean persisted = prefs(appContext).edit()
                .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME)
                .putBoolean(KEY_REINIT_PENDING, true)
                .putString(KEY_PENDING_REASON, "package-replaced:" + safe(reason))
                .putLong(KEY_PACKAGE_REPLACE_TOKEN, System.currentTimeMillis())
                .commit();
        AppEventLogger.event(appContext, "runtime_upgrade package_replaced reason="
                + safe(reason) + " version=" + versionLabel()
                + " persisted=" + persisted);
        return persisted;
    }

    //exposes the install/update token so user-facing reminders can repeat after same-version reinstalls.
    static long packageReplaceToken(Context context) {
        return prefs(context).getLong(KEY_PACKAGE_REPLACE_TOKEN, 0L);
    }

    //lets startup paths decide whether post-update repair should run after the service is alive.
    static boolean isPendingReinit(Context context) {
        return prefs(context).getBoolean(KEY_REINIT_PENDING, false);
    }

    //consumes the one-shot reset flag at the first real navigation start after update.
    static boolean consumePendingReinit(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = prefs(appContext);
        if (!prefs.getBoolean(KEY_REINIT_PENDING, false)) {
            return false;
        }
        String pendingReason = prefs.getString(KEY_PENDING_REASON, "");
        prefs.edit()
                .putBoolean(KEY_REINIT_PENDING, false)
                .putString(KEY_PENDING_REASON, "")
                .apply();
        AppEventLogger.event(appContext, "runtime_upgrade reinit_consumed reason="
                + safe(reason) + " pending=" + safe(pendingReason)
                + " version=" + versionLabel());
        return true;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    //normalizes version labels so event logs can be compared across app starts.
    private static String versionLabel() {
        return BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE;
    }
}
