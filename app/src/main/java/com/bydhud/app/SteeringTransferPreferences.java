package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Stores transfer profiles and migrates the previous single binding once. */
final class SteeringTransferPreferences {
    static final int NO_KEY_CODE = -1;
    static final String EMPTY_PACKAGE = "";
    static final String PROFILE_SELECTED = "selected";
    static final String PROFILE_CURRENT = PROFILE_SELECTED;
    static final String PROFILE_PARTIAL = "partial";
    static final String PROFILE_FULL = "full";
    static final String PRESS_SINGLE = "single";
    static final String PRESS_HOLD = "hold";
    static final String PRESS_DOUBLE = "double";

    private static final String PREFS = "bydhud_steering_transfer";
    private static final String KEY_CODE = "key_code";
    private static final String KEY_PACKAGE = "package_name";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_PROFILES = "profiles_v2";

    private SteeringTransferPreferences() {
    }

    static long revision(Context context) {
        return prefs(context).getLong(KEY_REVISION, 0L);
    }

    static synchronized List<SteeringTransferProfile> profiles(Context context) {
        SharedPreferences preferences = prefs(context);
        ensureMigrated(preferences);
        return Collections.unmodifiableList(parseProfiles(
                preferences.getString(KEY_PROFILES, "[]")));
    }

    static synchronized List<SteeringTransferProfile> diagnosticProfiles(Context context) {
        SharedPreferences preferences = prefs(context);
        return preferences.contains(KEY_PROFILES)
                ? parseProfiles(preferences.getString(KEY_PROFILES, "[]"))
                : migrateLegacy(preferences.getInt(KEY_CODE, NO_KEY_CODE),
                        preferences.getString(KEY_PACKAGE, EMPTY_PACKAGE),
                        preferences.getString(KEY_PROFILE, PROFILE_SELECTED));
    }

    static synchronized boolean saveProfile(Context context, SteeringTransferProfile value) {
        List<SteeringTransferProfile> current = new ArrayList<>(profiles(context));
        String id = value == null || value.id.isEmpty()
                ? UUID.randomUUID().toString() : value.id;
        SteeringTransferProfile normalized = value == null ? null
                : new SteeringTransferProfile(id, value.keyCode, value.pressMode,
                value.packageName, value.windowProfile);
        if (normalized == null || !normalized.isValid()
                || findConflict(current, normalized, normalized.id) != null) return false;
        int replace = -1;
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).id.equals(normalized.id)) {
                replace = index;
                break;
            }
        }
        if (replace >= 0) current.set(replace, normalized); else current.add(normalized);
        putProfiles(context, current);
        return true;
    }

    static synchronized boolean deleteProfile(Context context, String id) {
        List<SteeringTransferProfile> current = new ArrayList<>(profiles(context));
        if (!current.removeIf(profile -> profile.id.equals(id == null ? "" : id))) return false;
        putProfiles(context, current);
        return true;
    }

    static SteeringTransferProfile findConflict(List<SteeringTransferProfile> profiles,
            SteeringTransferProfile candidate, String excludedId) {
        if (candidate == null || profiles == null) return null;
        String excluded = excludedId == null ? "" : excludedId;
        for (SteeringTransferProfile profile : profiles) {
            if (!profile.id.equals(excluded)
                    && profile.keyCode == candidate.keyCode
                    && profile.pressMode.equals(candidate.pressMode)) return profile;
        }
        return null;
    }

    static SteeringTransferProfile find(List<SteeringTransferProfile> profiles,
            int keyCode, String pressMode) {
        if (profiles == null) return null;
        int canonical = SteeringTransferPolicy.canonicalKeyCode(keyCode);
        String normalizedMode = normalizePressMode(pressMode);
        for (SteeringTransferProfile profile : profiles) {
            if (profile.keyCode == canonical && profile.pressMode.equals(normalizedMode)) {
                return profile;
            }
        }
        return null;
    }

    static List<SteeringTransferProfile> parseProfiles(String raw) {
        ArrayList<SteeringTransferProfile> result = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) continue;
                SteeringTransferProfile profile = new SteeringTransferProfile(
                        object.optString("id", ""), object.optInt("keyCode", NO_KEY_CODE),
                        object.optString("pressMode", PRESS_SINGLE),
                        object.optString("packageName", EMPTY_PACKAGE),
                        object.optString("windowProfile", PROFILE_SELECTED));
                if (profile.isValid() && findConflict(result, profile, "") == null
                        && ids.add(profile.id)) {
                    result.add(profile);
                }
            }
        } catch (Exception ignored) {
            // A present but malformed v2 value must never resurrect a deleted legacy binding.
        }
        return result;
    }

    static String serializeProfiles(List<SteeringTransferProfile> profiles) {
        JSONArray array = new JSONArray();
        if (profiles != null) for (SteeringTransferProfile profile : profiles) {
            if (profile == null || !profile.isValid()) continue;
            try {
                array.put(new JSONObject()
                        .put("id", profile.id)
                        .put("keyCode", profile.keyCode)
                        .put("pressMode", profile.pressMode)
                        .put("packageName", profile.packageName)
                        .put("windowProfile", profile.windowProfile));
            } catch (Exception ignored) {
                // org.json only sees primitive values here.
            }
        }
        return array.toString();
    }

    private static void ensureMigrated(SharedPreferences preferences) {
        if (preferences.contains(KEY_PROFILES)) return;
        preferences.edit().putString(KEY_PROFILES, serializeProfiles(migrateLegacy(
                preferences.getInt(KEY_CODE, NO_KEY_CODE),
                preferences.getString(KEY_PACKAGE, EMPTY_PACKAGE),
                preferences.getString(KEY_PROFILE, PROFILE_SELECTED)))).apply();
    }

    static List<SteeringTransferProfile> migrateLegacy(int rawKeyCode,
            String rawPackageName, String windowProfile) {
        String packageName = normalizePackage(rawPackageName);
        ArrayList<SteeringTransferProfile> migrated = new ArrayList<>();
        if (rawKeyCode >= 0 && !packageName.isEmpty()) {
            migrated.add(new SteeringTransferProfile(
                    "legacy", rawKeyCode,
                    SteeringTransferPolicy.isNativeLongAlias(rawKeyCode)
                            ? PRESS_HOLD : PRESS_SINGLE,
                    packageName,
                    windowProfile));
        }
        return migrated;
    }

    private static void putProfiles(Context context, List<SteeringTransferProfile> profiles) {
        SharedPreferences preferences = prefs(context);
        preferences.edit()
                .putString(KEY_PROFILES, serializeProfiles(profiles))
                .putLong(KEY_REVISION, preferences.getLong(KEY_REVISION, 0L) + 1L)
                .apply();
        MainActivity.publishSharedUiStateChange();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String normalizePackage(String packageName) {
        return packageName == null ? EMPTY_PACKAGE : packageName.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeProfile(String profile) {
        String normalized = profile == null
                ? ""
                : profile.trim().toLowerCase(Locale.ROOT);
        if (PROFILE_PARTIAL.equals(normalized)) return PROFILE_PARTIAL;
        if (PROFILE_FULL.equals(normalized)) return PROFILE_FULL;
        return PROFILE_SELECTED;
    }

    static String normalizePressMode(String pressMode) {
        String normalized = pressMode == null ? ""
                : pressMode.trim().toLowerCase(Locale.ROOT);
        if (PRESS_HOLD.equals(normalized)) return PRESS_HOLD;
        if (PRESS_DOUBLE.equals(normalized)) return PRESS_DOUBLE;
        return PRESS_SINGLE;
    }
}
