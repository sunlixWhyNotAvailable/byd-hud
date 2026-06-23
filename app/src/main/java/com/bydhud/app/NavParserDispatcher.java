package com.bydhud.app;

//routes raw app evidence to the right parser so source-specific quirks stay isolated.

final class NavParserDispatcher {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavParserDispatcher() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isSupportedPackage(String packageName) {
        NavSnapshot.SourceApp source = NavTextNormalizer.sourceApp(packageName);
        return source == NavSnapshot.SourceApp.GOOGLE_MAPS
                || source == NavSnapshot.SourceApp.WAZE;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
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

    //parses source data here so downstream HUD code receives normalized navigation fields.
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

    //parses source data here so downstream HUD code receives normalized navigation fields.
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
