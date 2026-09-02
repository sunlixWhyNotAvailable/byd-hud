package com.bydhud.app;

//documents required adb grants so setup can be repeated after installs and updates.

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

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
    final String error;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavPermissionGrantPlan(
            String notificationService,
            String accessibilityService,
            String notificationListenersValue,
            String accessibilityServicesValue,
            List<String> shellCommands,
            String error) {
        this.notificationService = notificationService;
        this.accessibilityService = accessibilityService;
        this.notificationListenersValue = notificationListenersValue;
        this.accessibilityServicesValue = accessibilityServicesValue;
        this.shellCommands = Collections.unmodifiableList(new ArrayList<>(shellCommands));
        this.error = error == null ? "" : error;
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

    //resolves the installed service components before any secure setting is written.
    static NavPermissionGrantPlan fromCurrentSettings(
            Context context,
            String currentNotificationListeners,
            String currentAccessibilityServices) {
        return fromCurrentSettings(
                context,
                context == null ? "" : context.getPackageName(),
                currentNotificationListeners,
                currentAccessibilityServices,
                true,
                true,
                true,
                true);
    }

    //resolves the installed service components before any secure setting is written.
    static NavPermissionGrantPlan fromCurrentSettings(
            Context context,
            String packageName,
            String currentNotificationListeners,
            String currentAccessibilityServices,
            boolean grantNotificationListener,
            boolean grantAccessibilityService,
            boolean grantAccessibilityMaster,
            boolean grantDashboardOverlay) {
        try {
            String normalizedPackage = normalizePackageName(packageName);
            String notificationService = resolveServiceComponent(
                    context, normalizedPackage, "NavNotificationListenerService");
            String accessibilityService = resolveServiceComponent(
                    context, normalizedPackage, "NavAccessibilityService");
            if (notificationService.isEmpty() || accessibilityService.isEmpty()) {
                return invalidPlan("Installed navigation service component could not be resolved");
            }
            String canonicalNotificationListeners = canonicalizeServiceList(
                    currentNotificationListeners);
            String canonicalAccessibilityServices = canonicalizeServiceList(
                    currentAccessibilityServices);
            return buildPlan(
                    normalizedPackage,
                    notificationService,
                    accessibilityService,
                    canonicalNotificationListeners,
                    canonicalAccessibilityServices,
                    grantNotificationListener,
                    grantAccessibilityService,
                    grantAccessibilityMaster,
                    grantDashboardOverlay);
        } catch (RuntimeException e) {
            return invalidPlan(e.getMessage());
        }
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

        return buildPlan(
                normalizedPackage,
                notificationService,
                accessibilityService,
                currentNotificationListeners,
                currentAccessibilityServices,
                grantNotificationListener,
                grantAccessibilityService,
                grantAccessibilityMaster,
                grantDashboardOverlay);
    }

    //builds one validated command batch so all callers share the same shell boundary.
    private static NavPermissionGrantPlan buildPlan(
            String normalizedPackage,
            String notificationService,
            String accessibilityService,
            String currentNotificationListeners,
            String currentAccessibilityServices,
            boolean grantNotificationListener,
            boolean grantAccessibilityService,
            boolean grantAccessibilityMaster,
            boolean grantDashboardOverlay) {

        String notificationValue = joinSettingList(
                addUnique(splitSettingList(currentNotificationListeners), notificationService),
                false);
        String accessibilityValue = joinSettingList(
                addUnique(splitSettingList(currentAccessibilityServices), accessibilityService),
                true);

        List<String> commands = new ArrayList<>();
        if (grantNotificationListener) {
            commands.add(secureSettingPutCommand(
                    NOTIFICATION_LISTENERS, notificationValue));
        }
        if (grantAccessibilityService) {
            commands.add(secureSettingPutCommand(
                    ACCESSIBILITY_SERVICES, accessibilityValue));
        }
        if (grantAccessibilityMaster) {
            commands.add(secureSettingPutCommand(ACCESSIBILITY_ENABLED, "1"));
        }
        if (grantDashboardOverlay) {
            commands.add("appops set " + normalizedPackage + " SYSTEM_ALERT_WINDOW allow");
        }
        return new NavPermissionGrantPlan(
                notificationService,
                accessibilityService,
                notificationValue,
                accessibilityValue,
                commands,
                "");
    }

    //returns an empty command batch when service resolution fails, preventing destructive partial writes.
    private static NavPermissionGrantPlan invalidPlan(String message) {
        String detail = message == null || message.trim().isEmpty()
                ? "invalid navigation permission plan"
                : message.trim();
        return new NavPermissionGrantPlan(
                "", "", "", "", Collections.emptyList(), detail);
    }

    //resolves manifest aliases and preserves valid inner-class names such as Outer$Inner.
    private static String resolveServiceComponent(
            Context context, String packageName, String simpleClassName) {
        if (context == null) {
            return "";
        }
        try {
            ComponentName requested = new ComponentName(
                    packageName, packageName + "." + simpleClassName);
            ServiceInfo info = context.getPackageManager().getServiceInfo(requested, 0);
            if (info == null || !packageName.equals(info.packageName)
                    || info.name == null || info.name.trim().isEmpty()) {
                return "";
            }
            String className = info.name.trim();
            if (className.startsWith(".")) {
                className = packageName + className;
            } else if (className.indexOf('.') < 0) {
                className = packageName + "." + className;
            }
            return new ComponentName(info.packageName, className).flattenToString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    //Preserve saved entries even when they are stale or not visible to PackageManager.
    private static String canonicalizeServiceList(String currentValue) {
        List<String> canonical = new ArrayList<>();
        for (String value : splitSettingList(currentValue)) {
            ComponentName parsed = ComponentName.unflattenFromString(value);
            if (parsed == null) {
                throw new IllegalArgumentException("Invalid enabled service component");
            }
            String packageName = parsed.getPackageName();
            String normalized = normalizeComponentName(parsed.flattenToString());
            if (!normalizePackageName(packageName).equals(packageName)
                    || !isValidServiceClassName(normalized.substring(packageName.length() + 1))) {
                throw new IllegalArgumentException("Invalid enabled service component");
            }
            if (!canonical.contains(normalized)) {
                canonical.add(normalized);
            }
        }
        return joinSettingList(canonical, true);
    }

    private static boolean isValidServiceClassName(String className) {
        boolean segmentStart = true;
        for (int offset = 0; offset < className.length();) {
            int codePoint = className.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '.' && !segmentStart) {
                segmentStart = true;
            } else if (!Character.isISOControl(codePoint) && (segmentStart
                    ? Character.isJavaIdentifierStart(codePoint)
                    : Character.isJavaIdentifierPart(codePoint))) {
                segmentStart = false;
            } else {
                return false;
            }
        }
        return !segmentStart;
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
        commands.add(secureSettingPutCommand(ACCESSIBILITY_SERVICES, disabledValue));
        commands.add(secureSettingPutCommand(ACCESSIBILITY_SERVICES, restoredValue));
        commands.add(secureSettingPutCommand(ACCESSIBILITY_ENABLED, "1"));
        return Collections.unmodifiableList(commands);
    }

    //uses the same canonical component and quoting boundary for every runtime rebind.
    static List<String> accessibilityRuntimeRebindCommands(
            Context context,
            String packageName,
            String currentAccessibilityServices) {
        NavPermissionGrantPlan plan = fromCurrentSettings(
                context,
                packageName,
                "",
                currentAccessibilityServices,
                false,
                false,
                false,
                false);
        if (!plan.isValid()) {
            return Collections.emptyList();
        }
        String accessibilityService = plan.accessibilityService;
        List<String> withoutService = removeEquivalentService(
                splitSettingList(plan.accessibilityServicesValue), accessibilityService);
        String disabledValue = joinSettingList(withoutService, true);
        String restoredValue = joinSettingList(
                addUnique(withoutService, accessibilityService), true);
        List<String> commands = new ArrayList<>();
        commands.add(secureSettingPutCommand(ACCESSIBILITY_SERVICES, disabledValue));
        commands.add(secureSettingPutCommand(ACCESSIBILITY_SERVICES, restoredValue));
        commands.add(secureSettingPutCommand(ACCESSIBILITY_ENABLED, "1"));
        return Collections.unmodifiableList(commands);
    }

    //keeps callers from sending commands produced by an unresolved plan.
    boolean isValid() {
        return error.isEmpty() && !notificationService.isEmpty() && !accessibilityService.isEmpty();
    }

    //keeps command construction behind fixed setting keys and shell quoting.
    static String secureSettingPutCommandForTest(String key, String value) {
        return secureSettingPutCommand(key, value);
    }

    private static String secureSettingPutCommand(String key, String value) {
        if (!NOTIFICATION_LISTENERS.equals(key)
                && !ACCESSIBILITY_SERVICES.equals(key)
                && !ACCESSIBILITY_ENABLED.equals(key)) {
            throw new IllegalArgumentException("Unsupported secure setting key");
        }
        return "settings put secure " + key + " " + shellQuote(value);
    }

    private static String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\\''") + "'";
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
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")) {
            throw new IllegalArgumentException("Unsafe package name for ADB grant");
        }
        return normalized;
    }
}
