package com.bydhud.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NavTextNormalizer {
    private static final int MAX_DISTANCE_METERS = 99999;
    private static final String UNIT_END = "(?=$|\\s|[.,;:!?])";
    private static final Pattern KM_DISTANCE =
            Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*(?:km|\\u043a\\u043c)" + UNIT_END,
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern METER_DISTANCE =
            Pattern.compile("([0-9]+)\\s*(?:m|meter|meters|metre|metres|\\u043c)" + UNIT_END,
                    Pattern.CASE_INSENSITIVE);

    private NavTextNormalizer() {
    }

    public static NavSnapshot.SourceApp sourceApp(String packageName) {
        String normalized = lower(cleanText(packageName));
        if ("com.google.android.apps.maps".equals(normalized)
                || "app.revanced.android.apps.maps".equals(normalized)) {
            return NavSnapshot.SourceApp.GOOGLE_MAPS;
        }
        if ("com.waze".equals(normalized)) {
            return NavSnapshot.SourceApp.WAZE;
        }
        return NavSnapshot.SourceApp.UNKNOWN;
    }

    public static int distanceMeters(String text, int fallback) {
        String normalized = lower(cleanText(text));
        Matcher km = KM_DISTANCE.matcher(normalized);
        if (km.find()) {
            return safeKilometersToMeters(km.group(1), fallback);
        }

        Matcher meters = METER_DISTANCE.matcher(normalized);
        if (meters.find()) {
            return safeMeters(meters.group(1), fallback);
        }

        return fallback;
    }

    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u00a0', ' ')
                .replace('\u202f', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    public static String lower(String text) {
        return cleanText(text).toLowerCase(Locale.ROOT);
    }

    private static int safeMeters(String digits, int fallback) {
        String normalized = stripLeadingZeros(digits);
        if (normalized.length() > 5) {
            return MAX_DISTANCE_METERS;
        }
        try {
            return clampDistance(Integer.parseInt(normalized));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int safeKilometersToMeters(String number, int fallback) {
        try {
            double kilometers = Double.parseDouble(number.replace(',', '.'));
            if (Double.isNaN(kilometers)) {
                return fallback;
            }
            double meters = kilometers * 1000.0d;
            if (Double.isInfinite(meters) || meters > MAX_DISTANCE_METERS) {
                return MAX_DISTANCE_METERS;
            }
            return clampDistance((int) Math.round(meters));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampDistance(int meters) {
        if (meters < 0) {
            return 0;
        }
        if (meters > MAX_DISTANCE_METERS) {
            return MAX_DISTANCE_METERS;
        }
        return meters;
    }

    private static String stripLeadingZeros(String digits) {
        int index = 0;
        while (index < digits.length() - 1 && digits.charAt(index) == '0') {
            index++;
        }
        return digits.substring(index);
    }
}
