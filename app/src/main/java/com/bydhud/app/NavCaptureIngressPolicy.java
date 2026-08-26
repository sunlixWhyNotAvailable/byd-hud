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
        LOG_ONLY
    }

    private static final String WAZE = "com.waze";
    private static final String REVANCED_GMAPS = "app.revanced.android.apps.maps";
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
            snapshot = snapshot.withPreferences(
                    preferences.hudPackage, preferences.logOnlyPackages,
                    HudPrefs.isUserShutdownActive(context));
        }
    }

    static void disarmPackage(String packageName) {
        String normalized = normalize(packageName);
        NavAccessibilityService.cancelPendingCapture(normalized);
        NavNotificationListenerService.cancelPendingPosted(normalized);
    }

    static Mode resolveModeForTest(String packageName, String hudPackage,
            Set<String> logOnlyPackages, boolean shutdown) {
        return new Snapshot(hudPackage, logOnlyPackages, shutdown)
                .mode(normalize(packageName));
    }

    private static String normalize(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isDirectHudPackage(String packageName) {
        return WAZE.equals(packageName) || REVANCED_GMAPS.equals(packageName);
    }

    private static final class Snapshot {
        final String hudPackage;
        final Set<String> logOnlyPackages;
        final boolean shutdown;

        Snapshot(String hudPackage, Set<String> logOnlyPackages, boolean shutdown) {
            this.hudPackage = normalize(hudPackage);
            Set<String> normalizedLogOnly = new HashSet<>();
            if (logOnlyPackages != null) {
                for (String packageName : logOnlyPackages) {
                    String normalized = normalize(packageName);
                    if (!normalized.isEmpty()) normalizedLogOnly.add(normalized);
                }
            }
            this.logOnlyPackages = Collections.unmodifiableSet(normalizedLogOnly);
            this.shutdown = shutdown;
        }

        static Snapshot empty() {
            return new Snapshot("", Collections.emptySet(), true);
        }

        Snapshot withPreferences(String hudPackage, Set<String> logOnlyPackages,
                boolean shutdown) {
            return new Snapshot(hudPackage, logOnlyPackages, shutdown);
        }

        Mode mode(String packageName) {
            if (shutdown || packageName.isEmpty()) return Mode.OFF;
            boolean selected = packageName.equals(hudPackage);
            if (selected && isDirectHudPackage(packageName)) return Mode.OFF;
            if (logOnlyPackages.contains(packageName) || selected) return Mode.LOG_ONLY;
            return Mode.OFF;
        }
    }
}
