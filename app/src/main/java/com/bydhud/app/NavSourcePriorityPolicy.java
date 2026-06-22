package com.bydhud.app;

final class NavSourcePriorityPolicy {
    static final long ACCESSIBILITY_PRIORITY_WINDOW_MS = 2500L;

    private NavSourcePriorityPolicy() {
    }

    static boolean shouldUseNotificationFallback(long nowMs, long lastAccessibilityResultMs) {
        return lastAccessibilityResultMs <= 0
                || nowMs - lastAccessibilityResultMs > ACCESSIBILITY_PRIORITY_WINDOW_MS;
    }
}
