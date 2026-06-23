package com.bydhud.app;

//documents required adb grants so setup can be repeated after installs and updates.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//defines the NavPermissionGrantPlan module boundary so related behavior stays readable inside one unit.
final class NavPermissionGrantPlan {
    static final String NOTIFICATION_LISTENERS = "enabled_notification_listeners";
    static final String ACCESSIBILITY_SERVICES = "enabled_accessibility_services";
    static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";

    final String notificationService;
    final String accessibilityService;
    final String notificationListenersValue;
    final String accessibilityServicesValue;
    final List<String> shellCommands;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavPermissionGrantPlan(
            String notificationService,
            String accessibilityService,
            String notificationListenersValue,
            String accessibilityServicesValue,
            List<String> shellCommands) {
        this.notificationService = notificationService;
        this.accessibilityService = accessibilityService;
        this.notificationListenersValue = notificationListenersValue;
        this.accessibilityServicesValue = accessibilityServicesValue;
        this.shellCommands = Collections.unmodifiableList(new ArrayList<>(shellCommands));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavPermissionGrantPlan fromCurrentSettings(
            String packageName,
            String currentNotificationListeners,
            String currentAccessibilityServices) {
        return fromCurrentSettings(
                packageName,
                currentNotificationListeners,
                currentAccessibilityServices,
                true,
                true,
                true,
                true);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavPermissionGrantPlan fromCurrentSettings(
            String packageName,
            String currentNotificationListeners,
            String currentAccessibilityServices,
            boolean grantNotificationListener,
            boolean grantAccessibilityService,
            boolean grantAccessibilityMaster) {
        return fromCurrentSettings(
                packageName,
                currentNotificationListeners,
                currentAccessibilityServices,
                grantNotificationListener,
                grantAccessibilityService,
                grantAccessibilityMaster,
                true);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavPermissionGrantPlan fromCurrentSettings(
            String packageName,
            String currentNotificationListeners,
            String currentAccessibilityServices,
            boolean grantNotificationListener,
            boolean grantAccessibilityService,
            boolean grantAccessibilityMaster,
            boolean grantDashboardOverlay) {
        String normalizedPackage = normalizePackageName(packageName);
        String notificationService = normalizedPackage + "/"
                + normalizedPackage + ".NavNotificationListenerService";
        String accessibilityService = normalizedPackage + "/"
                + normalizedPackage + ".NavAccessibilityService";

        String notificationValue = joinSettingList(
                addUnique(splitSettingList(currentNotificationListeners), notificationService),
                false);
        String accessibilityValue = joinSettingList(
                addUnique(splitSettingList(currentAccessibilityServices), accessibilityService),
                true);

        List<String> commands = new ArrayList<>();
        if (grantNotificationListener) {
            commands.add("settings put secure " + NOTIFICATION_LISTENERS + " " + notificationValue);
        }
        if (grantAccessibilityService) {
            commands.add("settings put secure " + ACCESSIBILITY_SERVICES + " " + accessibilityValue);
        }
        if (grantAccessibilityMaster) {
            commands.add("settings put secure " + ACCESSIBILITY_ENABLED + " 1");
        }
        if (grantDashboardOverlay) {
            commands.add("appops set " + normalizedPackage + " SYSTEM_ALERT_WINDOW allow");
        }
        return new NavPermissionGrantPlan(
                notificationService,
                accessibilityService,
                notificationValue,
                accessibilityValue,
                commands);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static boolean containsService(String currentValue, String service) {
        return containsEquivalentService(splitSettingList(currentValue), service);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static List<String> accessibilityRuntimeRebindCommands(
            String packageName,
            String currentAccessibilityServices) {
        String normalizedPackage = normalizePackageName(packageName);
        String accessibilityService = normalizedPackage + "/"
                + normalizedPackage + ".NavAccessibilityService";
        List<String> withoutService = removeEquivalentService(
                splitSettingList(currentAccessibilityServices),
                accessibilityService);
        String disabledValue = joinSettingList(withoutService, true);
        String restoredValue = joinSettingList(
                addUnique(withoutService, accessibilityService),
                true);

        List<String> commands = new ArrayList<>();
        commands.add("settings put secure " + ACCESSIBILITY_SERVICES + " " + disabledValue);
        commands.add("settings put secure " + ACCESSIBILITY_SERVICES + " " + restoredValue);
        commands.add("settings put secure " + ACCESSIBILITY_ENABLED + " 1");
        return Collections.unmodifiableList(commands);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static List<String> splitSettingList(String currentValue) {
        List<String> values = new ArrayList<>();
        if (currentValue == null) {
            return values;
        }
        String trimmed = currentValue.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return values;
        }
        String[] pieces = trimmed.split(":");
        for (String piece : pieces) {
            String value = piece == null ? "" : piece.trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static List<String> addUnique(List<String> values, String service) {
        List<String> updated = new ArrayList<>(values);
        if (!containsEquivalentService(updated, service)) {
            updated.add(service);
        }
        return updated;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static List<String> removeEquivalentService(List<String> values, String service) {
        List<String> updated = new ArrayList<>();
        String normalizedService = normalizeComponentName(service);
        for (String value : values) {
            if (!normalizeComponentName(value).equals(normalizedService)) {
                updated.add(value);
            }
        }
        return updated;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean containsEquivalentService(List<String> values, String service) {
        String normalizedService = normalizeComponentName(service);
        for (String value : values) {
            if (value.equals(service)
                    || normalizeComponentName(value).equals(normalizedService)) {
                return true;
            }
        }
        return false;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizeComponentName(String component) {
        String value = component == null ? "" : component.trim();
        int slash = value.indexOf('/');
        if (slash <= 0 || slash >= value.length() - 1) {
            return value;
        }
        String packageName = value.substring(0, slash);
        String className = value.substring(slash + 1);
        if (className.startsWith(".")) {
            className = packageName + className;
        } else if (className.indexOf('.') < 0) {
            className = packageName + "." + className;
        }
        return packageName + "/" + className;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String joinSettingList(List<String> values, boolean leadingColon) {
        StringBuilder builder = new StringBuilder();
        if (leadingColon) {
            builder.append(':');
        }
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackageName(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.isEmpty()
                || normalized.indexOf(' ') >= 0
                || normalized.indexOf(';') >= 0
                || normalized.indexOf('&') >= 0
                || normalized.indexOf('|') >= 0
                || normalized.indexOf('`') >= 0
                || normalized.indexOf('$') >= 0
                || normalized.indexOf('\'') >= 0
                || normalized.indexOf('"') >= 0) {
            throw new IllegalArgumentException("Unsafe package name for ADB grant");
        }
        return normalized;
    }
}
