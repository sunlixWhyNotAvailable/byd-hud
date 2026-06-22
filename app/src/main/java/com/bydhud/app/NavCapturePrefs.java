package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class NavCapturePrefs {
    private static final String PREFS_NAME = "byd_hud_nav_capture_prefs";
    private static final String KEY_CAPTURE_PACKAGES = "capture_packages";
    private static final String KEY_HUD_PACKAGE = "hud_package";
    private static final String KEY_LOG_ONLY_PACKAGES = "log_only_packages";
    private static final String KEY_OBSERVED_PACKAGES = "observed_packages";

    private NavCapturePrefs() {
    }

    static Set<String> getCapturePackages(Context context) {
        pruneHiddenPackages(
                context,
                KEY_CAPTURE_PACKAGES,
                prefs(context).getStringSet(KEY_CAPTURE_PACKAGES, Collections.emptySet()),
                Collections.emptySet());
        String hudPackage = getHudPackage(context);
        Set<String> packages = new TreeSet<>(getLogOnlyPackages(context));
        if (!hudPackage.isEmpty()) {
            packages.add(hudPackage);
        }
        return packages;
    }

    static String getHudPackage(Context context) {
        String normalized = normalizePackage(prefs(context).getString(KEY_HUD_PACKAGE, ""));
        if (normalized.isEmpty() || NavAppFilter.shouldHideFromCaptureList(context, normalized)) {
            if (!normalized.isEmpty()) {
                prefs(context).edit().remove(KEY_HUD_PACKAGE).apply();
            }
            return "";
        }
        if (!isSupportedHudPackage(normalized)) {
            migrateUnsupportedHudPackage(context, normalized);
            return "";
        }
        return normalized;
    }

    static Set<String> getLogOnlyPackages(Context context) {
        Set<String> stored =
                prefs(context).getStringSet(KEY_LOG_ONLY_PACKAGES, Collections.emptySet());
        Set<String> visible = NavAppFilter.visibleCapturePackages(context, stored);
        pruneHiddenPackages(context, KEY_LOG_ONLY_PACKAGES, stored, visible);
        return visible;
    }

    static Set<String> getObservedPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_OBSERVED_PACKAGES, Collections.emptySet());
        Set<String> visible = NavAppFilter.visibleCapturePackages(context, stored);
        pruneHiddenPackages(context, KEY_OBSERVED_PACKAGES, stored, visible);
        return visible;
    }

    static void addObservedPackage(Context context, String packageName) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty() || NavAppFilter.shouldHideFromCaptureList(context, normalized)) {
            return;
        }
        Set<String> packages = new HashSet<>(getObservedPackages(context));
        if (packages.add(normalized)) {
            prefs(context).edit()
                    .putStringSet(KEY_OBSERVED_PACKAGES, packages)
                    .apply();
        }
    }

    static void setCaptureEnabled(Context context, String packageName, boolean enabled) {
        setHudEnabled(context, packageName, enabled);
    }

    static void setHudEnabled(Context context, String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty() || NavAppFilter.shouldHideFromCaptureList(context, normalized)) {
            return;
        }
        if (!isSupportedHudPackage(normalized)) {
            if (enabled) {
                migrateUnsupportedHudPackage(context, normalized);
            } else if (normalized.equals(normalizePackage(
                    prefs(context).getString(KEY_HUD_PACKAGE, "")))) {
                prefs(context).edit().remove(KEY_HUD_PACKAGE).apply();
            }
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        if (enabled) {
            editor.putString(KEY_HUD_PACKAGE, normalized);
            Set<String> logOnlyPackages = new HashSet<>(getLogOnlyPackages(context));
            logOnlyPackages.remove(normalized);
            editor.putStringSet(KEY_LOG_ONLY_PACKAGES, logOnlyPackages);
        } else {
            if (normalized.equals(getHudPackage(context))) {
                editor.remove(KEY_HUD_PACKAGE);
            }
        }
        editor.apply();
    }

    static void setLogOnlyEnabled(Context context, String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty() || NavAppFilter.shouldHideFromCaptureList(context, normalized)) {
            return;
        }
        Set<String> packages = new HashSet<>(getLogOnlyPackages(context));
        SharedPreferences.Editor editor = prefs(context).edit();
        if (enabled) {
            packages.add(normalized);
            if (normalized.equals(getHudPackage(context))) {
                editor.remove(KEY_HUD_PACKAGE);
            }
        } else {
            packages.remove(normalized);
        }
        editor.putStringSet(KEY_LOG_ONLY_PACKAGES, packages).apply();
    }

    static boolean isCaptureEnabled(Context context, String packageName) {
        String normalized = normalizePackage(packageName);
        return !normalized.isEmpty()
                && !NavAppFilter.shouldHideFromCaptureList(context, normalized)
                && (normalized.equals(getHudPackage(context))
                || getLogOnlyPackages(context).contains(normalized));
    }

    static boolean isHudEnabled(Context context, String packageName) {
        String normalized = normalizePackage(packageName);
        return !normalized.isEmpty()
                && isSupportedHudPackage(normalized)
                && normalized.equals(getHudPackage(context));
    }

    static boolean isLogOnlyEnabled(Context context, String packageName) {
        String normalized = normalizePackage(packageName);
        return !normalized.isEmpty() && getLogOnlyPackages(context).contains(normalized);
    }

    static boolean isSupportedHudPackage(String packageName) {
        String normalized = normalizePackage(packageName);
        return "com.google.android.apps.maps".equals(normalized)
                || "app.revanced.android.apps.maps".equals(normalized)
                || "com.waze".equals(normalized);
    }

    private static void migrateUnsupportedHudPackage(Context context, String normalizedPackage) {
        Set<String> logOnlyPackages = new HashSet<>(getLogOnlyPackages(context));
        logOnlyPackages.add(normalizedPackage);
        prefs(context).edit()
                .remove(KEY_HUD_PACKAGE)
                .putStringSet(KEY_LOG_ONLY_PACKAGES, logOnlyPackages)
                .apply();
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    private static void pruneHiddenPackages(
            Context context,
            String key,
            Set<String> stored,
            Set<String> visible) {
        Set<String> normalizedStored = new TreeSet<>();
        for (String packageName : stored) {
            String normalized = normalizePackage(packageName);
            if (!normalized.isEmpty()) {
                normalizedStored.add(normalized);
            }
        }
        if (!normalizedStored.equals(visible)) {
            prefs(context).edit()
                    .putStringSet(key, new HashSet<>(visible))
                    .apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
