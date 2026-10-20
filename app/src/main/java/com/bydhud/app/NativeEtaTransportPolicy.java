package com.bydhud.app;

/** Pure policy and payload checks for the mutually exclusive native ETA writers. */
final class NativeEtaTransportPolicy {
    static final String AMAP_PACKAGE_NAME = "com.byd.amapservice";
    static final String LEGACY_AMAP_PACKAGE_NAME = "com.example.amapservice";
    static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    static final long PERIODIC_DISPATCH_INTERVAL_MS = 1_500L;
    static final long DIRECT_SAME_VALUE_INTERVAL_MS = 3_500L;
    static final int INSTRUMENT_VALUE_COUNT = 10;
    private static final String[] ETA_PREFERENCE_KEYS = {
            "eta_output_mode", "route_metrics_mode", "whole_route_metrics",
            "output_eta", "output_remaining_time", "output_remaining_distance",
            "ui_language", "ua_language"
    };

    enum ReceiverState { AVAILABLE, UNAVAILABLE, UNKNOWN }

    enum Writer { NONE, AMAP, INSTRUMENT, UNRESOLVED }

    enum Endpoint { NEW, LEGACY, NONE, UNKNOWN }

    private NativeEtaTransportPolicy() { }

    static Endpoint endpointFor(ReceiverState newPackage, ReceiverState legacyPackage) {
        if (newPackage == ReceiverState.AVAILABLE) return Endpoint.NEW;
        if (newPackage == ReceiverState.UNKNOWN) return Endpoint.UNKNOWN;
        if (legacyPackage == ReceiverState.AVAILABLE) return Endpoint.LEGACY;
        if (legacyPackage == ReceiverState.UNKNOWN) return Endpoint.UNKNOWN;
        return Endpoint.NONE;
    }

    static boolean includesInstrumentNavigationFields(Endpoint endpoint) {
        return endpoint != Endpoint.NEW;
    }

    static String packageNameFor(Endpoint endpoint) {
        if (endpoint == Endpoint.NEW) return AMAP_PACKAGE_NAME;
        if (endpoint == Endpoint.LEGACY) return LEGACY_AMAP_PACKAGE_NAME;
        return "";
    }

    static boolean isAmapPackageName(String packageName) {
        return AMAP_PACKAGE_NAME.equals(packageName)
                || LEGACY_AMAP_PACKAGE_NAME.equals(packageName);
    }

    static Writer writerFor(int etaOutputMode, Endpoint endpoint) {
        if (etaOutputMode != HudPrefs.ETA_OUTPUT_NATIVE) return Writer.NONE;
        if (endpoint == Endpoint.NEW) return Writer.AMAP;
        if (endpoint == Endpoint.LEGACY || endpoint == Endpoint.NONE) {
            return Writer.INSTRUMENT;
        }
        return Writer.UNRESOLVED;
    }

    static boolean isEtaPreferenceKey(String key) {
        if (key == null) return false;
        for (String candidate : ETA_PREFERENCE_KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    /** Applies the retained OEM's independent Instrument field presence rules. */
    static int[] instrumentValues(NativeEtaFormatter.Formatted value) {
        if (value == null) {
            int[] missing = new int[INSTRUMENT_VALUE_COUNT];
            java.util.Arrays.fill(missing, -1);
            return missing;
        }
        // The OEM writes duration days only for 0..10, and writes minutes only when positive.
        int days = value.durationDays >= 0 && value.durationDays <= 10
                ? value.durationDays : -1;
        int hours = value.durationHours >= 0 ? value.durationHours : -1;
        int minutes = value.durationMinutes > 0 && value.durationMinutes <= 59
                ? value.durationMinutes : -1;
        int under30 = value.remainingUnder30 == 0 || value.remainingUnder30 == 1
                ? value.remainingUnder30 : -1;
        int distance = value.distanceMeters >= 0 ? value.distanceMeters : -1;
        int arrivalDay = value.arrivalDay >= 1 && value.arrivalDay <= 10
                ? value.arrivalDay : -1;
        int arrivalHour = value.arrivalHour >= 0 && value.arrivalHour <= 23
                ? value.arrivalHour : -1;
        int arrivalMinute = value.arrivalMinute >= 0 && value.arrivalMinute <= 59
                ? value.arrivalMinute : -1;
        int arrivalSecond = value.arrivalSecond >= 0 && value.arrivalSecond <= 59
                ? value.arrivalSecond : -1;
        int dayPeriod = validDayPeriod(value.dayPeriod) ? value.dayPeriod : -1;
        return new int[] {days, hours, minutes, under30, distance,
                arrivalDay, arrivalHour, arrivalMinute, arrivalSecond, dayPeriod};
    }

    /** values: duration days/hours/minutes/under30, distance, arrival day/hour/minute/second/dayPeriod. */
    static boolean validInstrumentValues(int[] values) {
        if (values == null || values.length != INSTRUMENT_VALUE_COUNT) return false;
        return bounded(values[0], 0, 10)
                && bounded(values[1], 0, Integer.MAX_VALUE)
                && bounded(values[2], 1, 59)
                && bounded(values[3], 0, 1)
                && bounded(values[4], 0, Integer.MAX_VALUE)
                && bounded(values[5], 1, 10)
                && bounded(values[6], 0, 23)
                && bounded(values[7], 0, 59)
                && bounded(values[8], 0, 59)
                && validDayPeriod(values[9]);
    }

    static boolean shouldDispatchDirectValue(boolean hasSuccessfulValue,
            int lastSuccessfulValue, long lastSuccessElapsedMs,
            int value, long nowElapsedMs) {
        if (value < 0) return false;
        if (!hasSuccessfulValue || value != lastSuccessfulValue) return true;
        return nowElapsedMs >= lastSuccessElapsedMs
                && nowElapsedMs - lastSuccessElapsedMs >= DIRECT_SAME_VALUE_INTERVAL_MS;
    }

    private static boolean bounded(int value, int minimum, int maximum) {
        return value == -1 || (value >= minimum && value <= maximum);
    }

    private static boolean validDayPeriod(int value) {
        return value == -1 || value == 0 || value == 2 || value == 4;
    }
}
