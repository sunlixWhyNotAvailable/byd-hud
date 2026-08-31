package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the optional steering-button transfer binding without coupling it to the UI. */
final class SteeringTransferPreferences {
    static final int NO_KEY_CODE = -1;
    static final String EMPTY_PACKAGE = "";
    static final String PROFILE_SELECTED = "selected";
    static final String PROFILE_CURRENT = PROFILE_SELECTED;
    static final String PROFILE_PARTIAL = "partial";
    static final String PROFILE_FULL = "full";

    private static final String PREFS = "bydhud_steering_transfer";
    private static final String KEY_CODE = "key_code";
    private static final String KEY_PACKAGE = "package_name";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_REVISION = "revision";

    private SteeringTransferPreferences() {
    }

    static int keyCode(Context context) {
        int value = prefs(context).getInt(KEY_CODE, NO_KEY_CODE);
        return value < 0 ? NO_KEY_CODE : value;
    }

    static String packageName(Context context) {
        return normalizePackage(prefs(context).getString(KEY_PACKAGE, EMPTY_PACKAGE));
    }

    static String profile(Context context) {
        String value = prefs(context).getString(KEY_PROFILE, PROFILE_SELECTED);
        if (PROFILE_PARTIAL.equals(value) || PROFILE_FULL.equals(value)) {
            return value;
        }
        return PROFILE_SELECTED;
    }

    static long revision(Context context) {
        return prefs(context).getLong(KEY_REVISION, 0L);
    }

    static void setKeyCode(Context context, int keyCode) {
        put(context, prefs(context).edit().putInt(KEY_CODE,
                keyCode < 0 ? NO_KEY_CODE : keyCode));
    }

    static void setPackageName(Context context, String packageName) {
        put(context, prefs(context).edit().putString(KEY_PACKAGE, normalizePackage(packageName)));
    }

    static void setProfile(Context context, String profile) {
        put(context, prefs(context).edit().putString(KEY_PROFILE, normalizeProfile(profile)));
    }

    static void save(Context context, int keyCode, String packageName, String profile) {
        put(context, prefs(context).edit()
                .putInt(KEY_CODE, keyCode < 0 ? NO_KEY_CODE : keyCode)
                .putString(KEY_PACKAGE, normalizePackage(packageName))
                .putString(KEY_PROFILE, normalizeProfile(profile)));
    }

    static void reset(Context context) {
        put(context, prefs(context).edit()
                .putInt(KEY_CODE, NO_KEY_CODE)
                .putString(KEY_PACKAGE, EMPTY_PACKAGE)
                .putString(KEY_PROFILE, PROFILE_SELECTED));
    }

    private static void put(Context context, SharedPreferences.Editor editor) {
        editor.putLong(KEY_REVISION, revision(context) + 1L).apply();
        MainActivity.publishSharedUiStateChange();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? EMPTY_PACKAGE : packageName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeProfile(String profile) {
        String normalized = profile == null
                ? ""
                : profile.trim().toLowerCase(java.util.Locale.ROOT);
        if (PROFILE_PARTIAL.equals(normalized)) return PROFILE_PARTIAL;
        if (PROFILE_FULL.equals(normalized)) return PROFILE_FULL;
        return PROFILE_SELECTED;
    }
}
