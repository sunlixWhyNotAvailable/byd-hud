package com.bydhud.app;

final class NavAccessibilityNodeCapturePolicy {
    private NavAccessibilityNodeCapturePolicy() {
    }

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
