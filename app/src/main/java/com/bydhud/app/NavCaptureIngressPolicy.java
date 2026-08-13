package com.bydhud.app;

import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cached routing gate for framework capture callbacks. */
final class NavCaptureIngressPolicy {
    enum Mode {
        OFF,
        DISCOVERY,
        FALLBACK
    }

    private static final String WAZE = "com.waze";
    private static final String GMAPS = "app.revanced.android.apps.maps";
    private static final Object LOCK = new Object();
    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BydHudCapturePolicy");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static volatile Snapshot snapshot = Snapshot.empty();

    private NavCaptureIngressPolicy() {
    }

    static Mode mode(String packageName) {
        return snapshot.mode(normalize(packageName));
    }

    static void refreshPreferencesAsync(Context context) {
        Context appContext = context.getApplicationContext();
        REFRESH.execute(() -> refreshPreferences(appContext));
    }

    static void refreshPreferences(Context context) {
        NavCapturePrefs.IngressPreferences preferences =
                NavCapturePrefs.ingressPreferences(context);
        synchronized (LOCK) {
            Snapshot current = snapshot;
            snapshot = current.withPreferences(
                    preferences.hudPackage, preferences.logOnlyPackages,
                    HudPrefs.isWazeScreenCaptureEnabled(context),
                    HudPrefs.isUserShutdownActive(context));
        }
    }

    static void updateRuntime(boolean runtimeActive, String activePackage,
            boolean wazeDirect, boolean wazeFallback,
            boolean gmapsDirect, boolean gmapsFallback) {
        synchronized (LOCK) {
            snapshot = snapshot.withRuntime(runtimeActive, normalize(activePackage),
                    wazeDirect, wazeFallback, gmapsDirect, gmapsFallback);
        }
    }

    static void disarmPackage(String packageName) {
        String normalized = normalize(packageName);
        NavAccessibilityService.cancelPendingCapture(normalized);
        NavNotificationListenerService.cancelPendingPosted(normalized);
    }

    static Mode resolveModeForTest(String packageName, String hudPackage,
            Set<String> logOnlyPackages, boolean shutdown, boolean runtimeActive,
            String activePackage, boolean wazeLegacyEnabled,
            boolean wazeDirect, boolean wazeFallback,
            boolean gmapsDirect, boolean gmapsFallback) {
        return new Snapshot(hudPackage, logOnlyPackages, shutdown, runtimeActive,
                activePackage, wazeLegacyEnabled, wazeDirect, wazeFallback,
                gmapsDirect, gmapsFallback).mode(normalize(packageName));
    }

    private static String normalize(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static final class Snapshot {
        final String hudPackage;
        final Set<String> logOnlyPackages;
        final boolean shutdown;
        final boolean runtimeActive;
        final String activePackage;
        final boolean wazeLegacyEnabled;
        final boolean wazeDirect;
        final boolean wazeFallback;
        final boolean gmapsDirect;
        final boolean gmapsFallback;

        Snapshot(String hudPackage, Set<String> logOnlyPackages,
                boolean shutdown, boolean runtimeActive, String activePackage,
                boolean wazeLegacyEnabled, boolean wazeDirect, boolean wazeFallback,
                boolean gmapsDirect, boolean gmapsFallback) {
            this.hudPackage = normalize(hudPackage);
            this.logOnlyPackages = Collections.unmodifiableSet(new HashSet<>(
                    logOnlyPackages == null ? Collections.emptySet() : logOnlyPackages));
            this.shutdown = shutdown;
            this.runtimeActive = runtimeActive;
            this.activePackage = normalize(activePackage);
            this.wazeLegacyEnabled = wazeLegacyEnabled;
            this.wazeDirect = wazeDirect;
            this.wazeFallback = wazeFallback;
            this.gmapsDirect = gmapsDirect;
            this.gmapsFallback = gmapsFallback;
        }

        static Snapshot empty() {
            return new Snapshot("", Collections.emptySet(), true, false, "",
                    false, false, false, false, false);
        }

        Snapshot withPreferences(String hudPackage, Set<String> logOnlyPackages,
                boolean wazeLegacyEnabled, boolean shutdown) {
            return new Snapshot(hudPackage, logOnlyPackages, shutdown, runtimeActive,
                    activePackage, wazeLegacyEnabled, wazeDirect, wazeFallback,
                    gmapsDirect, gmapsFallback);
        }

        Snapshot withRuntime(boolean runtimeActive, String activePackage,
                boolean wazeDirect, boolean wazeFallback,
                boolean gmapsDirect, boolean gmapsFallback) {
            return new Snapshot(hudPackage, logOnlyPackages, shutdown, runtimeActive,
                    activePackage, wazeLegacyEnabled, wazeDirect, wazeFallback,
                    gmapsDirect, gmapsFallback);
        }

        Mode mode(String packageName) {
            if (shutdown || packageName.isEmpty()) return Mode.OFF;
            boolean selected = packageName.equals(hudPackage);
            boolean logOnly = logOnlyPackages.contains(packageName);
            if (!selected && !logOnly) return Mode.OFF;
            if (WAZE.equals(packageName)) {
                if (wazeDirect) return Mode.OFF;
                if (logOnly) return Mode.FALLBACK;
                if (!runtimeActive || !WAZE.equals(activePackage)) return Mode.OFF;
                if (!wazeLegacyEnabled) return Mode.OFF;
                return wazeFallback ? Mode.FALLBACK : Mode.DISCOVERY;
            }
            if (GMAPS.equals(packageName)) {
                if (gmapsDirect) return Mode.OFF;
                if (logOnly) return Mode.FALLBACK;
                if (!runtimeActive || !GMAPS.equals(activePackage)) return Mode.OFF;
                return gmapsFallback ? Mode.FALLBACK : Mode.DISCOVERY;
            }
            return logOnly || (runtimeActive && packageName.equals(activePackage))
                    ? Mode.FALLBACK : Mode.OFF;
        }
    }
}
