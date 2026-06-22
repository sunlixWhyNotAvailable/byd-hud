package com.bydhud.app;

final class WazeLaneParser {
    private WazeLaneParser() {
    }

    static String parseLaneText(String text) {
        String lower = NavTextNormalizer.lower(text);
        if (lower.isEmpty()) {
            return "";
        }
        if (lower.contains("public transportation lane")
                || lower.contains("hov")
                || lower.matches("^[0-9]+\\+$")) {
            return "";
        }
        if (!lower.contains("lane")) {
            return "";
        }
        String token = "";
        if (lower.contains("left")) {
            token = "L*";
        } else if (lower.contains("right")) {
            token = "R*";
        } else if (lower.contains("straight") || lower.contains("continue")) {
            token = "S*";
        }
        if (token.isEmpty()) {
            return "";
        }
        int count = laneCount(lower);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(token);
        }
        return builder.toString();
    }

    static int laneCountFromSignature(String laneString) {
        String clean = laneString == null ? "" : laneString.trim();
        if (clean.isEmpty()) {
            return 0;
        }
        String[] parts = clean.split("\\|", -1);
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    static boolean hasMultiLaneSignature(String laneString) {
        return laneCountFromSignature(laneString) > 1;
    }

    private static int laneCount(String lower) {
        if (lower.contains("2 lane") || lower.contains("two lane")
                || lower.contains("either lane")) {
            return 2;
        }
        if (lower.contains("3 lane") || lower.contains("three lane")) {
            return 3;
        }
        return 1;
    }
}
