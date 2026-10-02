package com.bydhud.app;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** UI coordinates only; checkpointed without retaining Activity, views or Compose state. */
final class RuntimeUiSession {
    static final RuntimeUiSession PROCESS = new RuntimeUiSession();

    private Session current;

    synchronized Session getOrCreate(Supplier<String> initialTab) {
        if (current == null) current = new Session(initialTab.get());
        return current;
    }

    synchronized void clear() {
        // Old UI callbacks keep their detached object and cannot republish it here.
        if (current != null) current.detached = true;
        current = null;
        ShellRuntimeSession.cacheUiPosition("");
    }

    synchronized void restore(String checkpoint) {
        if (current != null || checkpoint == null || checkpoint.isEmpty()) return;
        try {
            org.json.JSONObject saved = new org.json.JSONObject(checkpoint);
            Session restored = new Session(saved.getString("tab"));
            restored.selectedOptionsSection = saved.optString("section", "runtime-permissions");
            org.json.JSONObject positions = saved.optJSONObject("positions");
            if (positions != null) {
                java.util.Iterator<String> keys = positions.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    org.json.JSONArray value = positions.getJSONArray(key);
                    restored.viewports.put(key, new Viewport(value.getInt(0), value.getInt(1)));
                }
            }
            current = restored;
        } catch (org.json.JSONException ignored) { /* Rebuild only invalid cache. */ }
    }

    static final class Session {
        private volatile boolean detached;
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
            if (java.util.Objects.equals(selectedTab, tab)
                    && java.util.Objects.equals(selectedOptionsSection, optionsSection)) return;
            selectedTab = tab;
            selectedOptionsSection = optionsSection;
            checkpoint();
        }

        synchronized Viewport viewport(String key) {
            Viewport value = viewports.get(key);
            return value == null ? Viewport.TOP : value;
        }

        synchronized void recordViewport(String key, Viewport position) {
            // An unmeasured/empty list has no position to replace the last known one.
            if (position != null && !position.equals(viewports.get(key))) {
                viewports.put(key, position);
                checkpoint();
            }
        }

        private void checkpoint() {
            if (detached) return;
            try {
                org.json.JSONObject positions = new org.json.JSONObject();
                for (Map.Entry<String, Viewport> entry : viewports.entrySet()) {
                    positions.put(entry.getKey(), new org.json.JSONArray()
                            .put(entry.getValue().index).put(entry.getValue().offset));
                }
                ShellRuntimeSession.cacheUiPosition(new org.json.JSONObject().put("tab", selectedTab)
                        .put("section", selectedOptionsSection).put("positions", positions).toString());
            } catch (org.json.JSONException ignored) { }
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
