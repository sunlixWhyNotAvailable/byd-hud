package com.bydhud.app;

//keeps supported package rules centralized so app detection stays consistent across sources.

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

//defines the NavAppFilter module boundary so related behavior stays readable inside one unit.
final class NavAppFilter {
    private static final String DEFAULT_SELF_PACKAGE = "com.bydhud.app";
    private static final Set<String> HIDDEN_PACKAGES = hiddenPackages();

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavAppFilter() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldHideFromCaptureList(String packageName) {
        String normalized = normalize(packageName);
        if (normalized.isEmpty()) {
            return true;
        }
        if (HIDDEN_PACKAGES.contains(normalized)) {
            return true;
        }
        return "android".equals(normalized)
                || normalized.startsWith("com.android.")
                || normalized.contains("input")
                || normalized.contains("launcher");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldHideFromCaptureList(Context context, String packageName) {
        String normalized = normalize(packageName);
        if (context != null && normalized.equals(normalize(context.getPackageName()))) {
            return true;
        }
        if (shouldHideFromCaptureList(normalized)) {
            return true;
        }
        if (isKnownNavigationPackage(normalized)) {
            return false;
        }
        return isInstalledSystemPackage(context, normalized);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static Set<String> visibleCapturePackages(Collection<String> packages) {
        Set<String> visible = new TreeSet<>();
        if (packages == null) {
            return visible;
        }
        for (String packageName : packages) {
            String normalized = normalize(packageName);
            if (!shouldHideFromCaptureList(normalized)) {
                visible.add(normalized);
            }
        }
        return visible;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static Set<String> visibleCapturePackages(Context context, Collection<String> packages) {
        Set<String> visible = new TreeSet<>();
        if (packages == null) {
            return visible;
        }
        for (String packageName : packages) {
            String normalized = normalize(packageName);
            if (!shouldHideFromCaptureList(context, normalized)) {
                visible.add(normalized);
            }
        }
        return visible;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static Set<String> curatedNavigationPackages() {
        Set<String> packages = new TreeSet<>();
        packages.add("app.revanced.android.apps.maps");
        packages.add("com.google.android.apps.maps");
        packages.add("com.iternio.abrpapp");
        packages.add("com.waze");
        return Collections.unmodifiableSet(packages);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isCuratedNavigationPackage(String packageName) {
        return isKnownNavigationPackage(normalize(packageName));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Set<String> hiddenPackages() {
        Set<String> packages = new HashSet<>();
        packages.add(DEFAULT_SELF_PACKAGE);
        packages.add("android");
        packages.add("com.byd.appstartmanagement");
        packages.add("com.google.android.inputmethod.latin");
        packages.add("com.byd.avc");
        packages.add("com.android.launcher3");
        packages.add("com.byd.mycar");
        packages.add("com.byd.dilinkaccount");
        packages.add("com.android.systemui");
        packages.add("com.byd.multipletheme");
        packages.add("com.byd.carsettings");
        packages.add("com.byd.auto_camera");
        packages.add("com.byd.cdr");
        packages.add("com.byd.suynclink");
        packages.add("com.byd.synclink");
        packages.add("com.byd.filemanager");
        packages.add("com.byd.launchermap");
        packages.add("com.byd.mediacenter");
        packages.add("com.byd.etc");
        return Collections.unmodifiableSet(packages);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isKnownNavigationPackage(String normalizedPackage) {
        return "com.waze".equals(normalizedPackage)
                || "com.google.android.apps.maps".equals(normalizedPackage)
                || "app.revanced.android.apps.maps".equals(normalizedPackage)
                || "com.iternio.abrpapp".equals(normalizedPackage);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isInstalledSystemPackage(Context context, String packageName) {
        if (context == null || packageName.isEmpty()) {
            return false;
        }
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            return false;
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            return (info.flags & systemFlags) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalize(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }
}
