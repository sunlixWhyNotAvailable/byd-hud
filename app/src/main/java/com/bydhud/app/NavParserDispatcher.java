package com.bydhud.app;

final class NavParserDispatcher {
    private NavParserDispatcher() {
    }

    static boolean isSupportedPackage(String packageName) {
        NavSnapshot.SourceApp source = NavTextNormalizer.sourceApp(packageName);
        return source == NavSnapshot.SourceApp.GOOGLE_MAPS
                || source == NavSnapshot.SourceApp.WAZE;
    }

    static NavParserResult parseNotification(
            String packageName,
            String title,
            String text,
            String subText,
            String bigText,
            String textLines,
            String category,
            boolean ongoing) {
        return parseNotification(packageName, title, text, subText, bigText, textLines,
                category, ongoing, NavManeuverEvidence.NONE, System.currentTimeMillis());
    }

    static NavParserResult parseNotification(
            String packageName,
            String title,
            String text,
            String subText,
            String bigText,
            String textLines,
            String category,
            boolean ongoing,
            NavManeuverEvidence maneuverEvidence,
            long nowElapsedMs) {
        NavSnapshot.SourceApp source = NavTextNormalizer.sourceApp(packageName);
        if (source == NavSnapshot.SourceApp.GOOGLE_MAPS) {
            return GMapsNotificationParser.parse(packageName, title, text, subText, category,
                    ongoing, maneuverEvidence, nowElapsedMs);
        }
        return null;
    }

    static NavParserResult parseAccessibility(String packageName, String payload, HudState baseline) {
        NavSnapshot.SourceApp source = NavTextNormalizer.sourceApp(packageName);
        if (source == NavSnapshot.SourceApp.GOOGLE_MAPS) {
            return GMapsAccessibilityParser.parse(packageName, payload, baseline);
        }
        if (source == NavSnapshot.SourceApp.WAZE) {
            return WazeAccessibilityParser.parse(packageName, payload, baseline);
        }
        return null;
    }
}
