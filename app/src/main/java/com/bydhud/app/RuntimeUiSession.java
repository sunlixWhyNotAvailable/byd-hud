package com.bydhud.app;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** In-memory UI position only; never retains an Activity, view or Compose state. */
final class RuntimeUiSession {
    static final RuntimeUiSession PROCESS = new RuntimeUiSession();

    private Session current;

    synchronized Session getOrCreate(Supplier<String> initialTab) {
        if (current == null) current = new Session(initialTab.get());
        return current;
    }

    synchronized void clear() {
        // Old UI callbacks keep their detached object and cannot republish it here.
        current = null;
    }

    static final class Session {
        private String selectedTab;
        private String selectedOptionsSection = "runtime-permissions";
        private final Map<String, Viewport> viewports = new HashMap<>();

        Session(String initialTab) {
            selectedTab = initialTab;
        }

        synchronized String selectedTab() {
            return selectedTab;
        }

        synchronized String selectedOptionsSection() {
            return selectedOptionsSection;
        }

        synchronized void select(String tab, String optionsSection) {
            selectedTab = tab;
            selectedOptionsSection = optionsSection;
        }

        synchronized Viewport viewport(String key) {
            Viewport value = viewports.get(key);
            return value == null ? Viewport.TOP : value;
        }

        synchronized void recordViewport(String key, Viewport position) {
            // An unmeasured/empty list has no position to replace the last known one.
            if (position != null) viewports.put(key, position);
        }
    }

    static final class Viewport {
        static final Viewport TOP = new Viewport(0, 0);
        final int index;
        final int offset;

        Viewport(int index, int offset) {
            this.index = Math.max(0, index);
            this.offset = Math.max(0, offset);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Viewport)) return false;
            Viewport value = (Viewport) other;
            return index == value.index && offset == value.offset;
        }

        @Override public int hashCode() {
            return 31 * index + offset;
        }
    }
}
