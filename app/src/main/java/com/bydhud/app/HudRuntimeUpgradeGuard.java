package com.bydhud.app;

//guards post-update runtime state so the first nav session does not reuse stale binders or capture frames.

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

//defines the HudRuntimeUpgradeGuard module boundary so update recovery stays separate from normal startup.
final class HudRuntimeUpgradeGuard {
    private static final Object LOCK = new Object();
    private static final String PREFS_NAME = "byd_hud_runtime_upgrade_guard";
    private static final String KEY_LAST_VERSION_CODE = "last_version_code";
    private static final String KEY_LAST_VERSION_NAME = "last_version_name";
    private static final String KEY_REINIT_PENDING = "reinit_pending";
    private static final String KEY_PENDING_REASON = "pending_reason";
    private static final String KEY_PACKAGE_REPLACE_TOKEN = "package_replace_token";
    private static final String KEY_HARD_RESET_PENDING = "hard_reset_pending";
    private static final String KEY_HARD_RESET_REASON = "hard_reset_reason";
    private static final String KEY_LAST_HARD_RESET_PACKAGE_UPDATE_MS =
            "last_hard_reset_package_update_ms";

    enum VersionStartDecision {
        INITIALIZE,
        UNCHANGED,
        HARD_RESET
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudRuntimeUpgradeGuard() {
    }

    //detects version changes when broadcasts were missed, while avoiding a false update on fresh install.
    static void recordVersionStart(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(appContext);
            int previousCode = prefs.getInt(KEY_LAST_VERSION_CODE, 0);
            VersionStartDecision decision = versionStartDecision(
                    previousCode, BuildConfig.VERSION_CODE);
            if (decision == VersionStartDecision.UNCHANGED) {
                return;
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME);
            if (decision == VersionStartDecision.INITIALIZE) {
                boolean persisted = editor
                        .putBoolean(KEY_HARD_RESET_PENDING, false)
                        .putString(KEY_HARD_RESET_REASON, "")
                        .commit();
                AppEventLogger.event(appContext, "runtime_upgrade version_initialized reason="
                        + safe(reason) + " version=" + versionLabel()
                        + " persisted=" + persisted);
                return;
            }
            boolean persisted = editor
                    .putBoolean(KEY_REINIT_PENDING, true)
                    .putString(KEY_PENDING_REASON, "version-change:" + safe(reason))
                    .putBoolean(KEY_HARD_RESET_PENDING, true)
                    .putString(KEY_HARD_RESET_REASON, "version-change:" + safe(reason))
                    .commit();
            AppEventLogger.event(appContext, "runtime_upgrade version_changed reason="
                    + safe(reason) + " previousCode=" + previousCode
                    + " version=" + versionLabel()
                    + " persisted=" + persisted);
        }
    }

    static VersionStartDecision versionStartDecision(int previousCode, int currentCode) {
        if (previousCode == 0) {
            return VersionStartDecision.INITIALIZE;
        }
        return previousCode == currentCode
                ? VersionStartDecision.UNCHANGED
                : VersionStartDecision.HARD_RESET;
    }

    static boolean shouldArmPackageReplaceHardReset(
            long packageUpdateMs,
            long lastResetPackageUpdateMs,
            boolean resetAlreadyPending) {
        return resetAlreadyPending
                || packageUpdateMs <= 0L
                || packageUpdateMs != lastResetPackageUpdateMs;
    }

    //marks package replacement explicitly because this is the strongest signal for stale runtime state.
    static boolean markPackageReplaced(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        boolean persisted;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(appContext);
            long packageUpdateMs = packageUpdateTimeMs(appContext);
            boolean armHardReset = shouldArmPackageReplaceHardReset(
                    packageUpdateMs,
                    prefs.getLong(KEY_LAST_HARD_RESET_PACKAGE_UPDATE_MS, 0L),
                    prefs.getBoolean(KEY_HARD_RESET_PENDING, false));
            SharedPreferences.Editor editor = prefs.edit()
                    .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME)
                    .putLong(KEY_PACKAGE_REPLACE_TOKEN, System.currentTimeMillis());
            if (armHardReset) {
                editor.putBoolean(KEY_REINIT_PENDING, true)
                        .putString(KEY_PENDING_REASON, "package-replaced:" + safe(reason))
                        .putBoolean(KEY_HARD_RESET_PENDING, true)
                        .putString(KEY_HARD_RESET_REASON, "package-replaced:" + safe(reason));
            }
            persisted = editor.commit();
            AppEventLogger.event(appContext,
                    "runtime_upgrade package_replaced_decision armHardReset="
                            + armHardReset + " packageUpdateMs=" + packageUpdateMs);
        }
        AppEventLogger.event(appContext, "runtime_upgrade package_replaced reason="
                + safe(reason) + " version=" + versionLabel()
                + " persisted=" + persisted);
        return persisted;
    }

    static boolean hasPendingHardReset(Context context) {
        synchronized (LOCK) {
            return prefs(context).getBoolean(KEY_HARD_RESET_PENDING, false);
        }
    }

    static boolean completePendingHardReset(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String pendingReason;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(appContext);
            if (!prefs.getBoolean(KEY_HARD_RESET_PENDING, false)) {
                return false;
            }
            pendingReason = prefs.getString(KEY_HARD_RESET_REASON, "");
            long packageUpdateMs = packageUpdateTimeMs(appContext);
            if (!prefs.edit()
                    .putBoolean(KEY_HARD_RESET_PENDING, false)
                    .putString(KEY_HARD_RESET_REASON, "")
                    .putLong(KEY_LAST_HARD_RESET_PACKAGE_UPDATE_MS, packageUpdateMs)
                    .commit()) {
                AppEventLogger.event(appContext,
                        "runtime_upgrade hard_reset_complete_failed reason=" + safe(reason));
                return false;
            }
        }
        AppEventLogger.event(appContext, "runtime_upgrade hard_reset_completed reason="
                + safe(reason) + " pending=" + safe(pendingReason)
                + " version=" + versionLabel());
        return true;
    }

    static void rearmPendingHardReset(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        boolean persisted;
        synchronized (LOCK) {
            persisted = prefs(appContext).edit()
                    .putBoolean(KEY_HARD_RESET_PENDING, true)
                    .putString(KEY_HARD_RESET_REASON, safe(reason))
                    .putLong(KEY_LAST_HARD_RESET_PACKAGE_UPDATE_MS, 0L)
                    .commit();
        }
        AppEventLogger.event(appContext, "runtime_upgrade hard_reset_rearmed reason="
                + safe(reason) + " persisted=" + persisted);
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

    private static long packageUpdateTimeMs(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            AppEventLogger.event(context, "runtime_upgrade package_update_time_failed error="
                    + e.getClass().getSimpleName());
            return 0L;
        }
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
