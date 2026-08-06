package com.bydhud.app;

import java.util.Locale;

final class DirectSpeedLimitStore {
    private static DirectTbtFrame.SpeedLimit waze = DirectTbtFrame.SpeedLimit.inactive();
    private static DirectTbtFrame.SpeedLimit gmaps = DirectTbtFrame.SpeedLimit.inactive();

    private DirectSpeedLimitStore() {
    }

    static synchronized boolean update(
            String packageName, int displayValue, int kph, String unit, long eventElapsedMs) {
        DirectTbtFrame.SpeedLimit next = normalized(displayValue, kph, unit, eventElapsedMs);
        DirectTbtFrame.SpeedLimit previous = snapshot(packageName);
        set(packageName, next);
        return previous.getDisplayValue() != next.getDisplayValue()
                || previous.getKph() != next.getKph()
                || !previous.getUnit().equals(next.getUnit());
    }

    static synchronized boolean clear(String packageName) {
        return update(packageName, 0, 0, "", 0L);
    }

    static synchronized DirectTbtFrame.SpeedLimit snapshot(String packageName) {
        return GMapsDirectChannel.PACKAGE_NAME.equals(packageName) ? gmaps : waze;
    }

    private static void set(String packageName, DirectTbtFrame.SpeedLimit value) {
        if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) gmaps = value;
        else waze = value;
    }

    private static DirectTbtFrame.SpeedLimit normalized(
            int displayValue, int kph, String unit, long eventElapsedMs) {
        if (displayValue <= 0 || displayValue > 300) {
            return DirectTbtFrame.SpeedLimit.inactive();
        }
        String normalizedUnit = unit == null ? "" : unit.trim().toLowerCase(Locale.US);
        if (normalizedUnit.contains("mph")) normalizedUnit = "mph";
        else normalizedUnit = "km/h";
        int normalizedKph = kph > 0 && kph <= 300 ? kph
                : "mph".equals(normalizedUnit)
                ? Math.round((float) (displayValue * 1.609344d)) : displayValue;
        return new DirectTbtFrame.SpeedLimit(
                displayValue, normalizedKph, normalizedUnit, eventElapsedMs);
    }
}
