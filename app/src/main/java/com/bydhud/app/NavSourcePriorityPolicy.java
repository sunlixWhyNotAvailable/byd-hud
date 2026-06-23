package com.bydhud.app;

//orders navigation sources so fresher and richer evidence wins without source-specific hacks.

final class NavSourcePriorityPolicy {
    static final long ACCESSIBILITY_PRIORITY_WINDOW_MS = 2500L;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavSourcePriorityPolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldUseNotificationFallback(long nowMs, long lastAccessibilityResultMs) {
        return lastAccessibilityResultMs <= 0
                || nowMs - lastAccessibilityResultMs > ACCESSIBILITY_PRIORITY_WINDOW_MS;
    }
}
