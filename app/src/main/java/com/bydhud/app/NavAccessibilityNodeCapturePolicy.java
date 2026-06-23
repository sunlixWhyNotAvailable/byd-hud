package com.bydhud.app;

//limits accessibility node capture so logs contain useful navigation evidence without excessive noise.

final class NavAccessibilityNodeCapturePolicy {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavAccessibilityNodeCapturePolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldLogNodeForCapture(
            String packageName,
            String viewId,
            String text,
            String description,
            String className) {
        if (!NavTextNormalizer.cleanText(text).isEmpty()
                || !NavTextNormalizer.cleanText(description).isEmpty()) {
            return true;
        }
        if (NavTextNormalizer.sourceApp(packageName) != NavSnapshot.SourceApp.WAZE) {
            return false;
        }
        String idLower = NavTextNormalizer.lower(viewId);
        String classLower = NavTextNormalizer.lower(className);
        return (idLower.contains("nav")
                || idLower.contains("turn")
                || idLower.contains("lane")
                || idLower.contains("arrow"))
                && (classLower.contains("image") || classLower.contains("view"));
    }
}
