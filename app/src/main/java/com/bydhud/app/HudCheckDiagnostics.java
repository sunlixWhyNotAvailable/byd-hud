package com.bydhud.app;

import java.util.HashMap;
import java.util.Map;

/** Worker-owned, bounded per-plane summaries; never includes payload or street text. */
final class HudCheckDiagnostics {
    private final Map<String, String> lastByPlane = new HashMap<>();

    String changed(HudCheckState sample, String plane, int result, String reason) {
        if (sample == null || !sample.running) return null;
        String channel = token(plane);
        String line = "hud_check case=" + sampleKey(sample) + " plane=" + channel
                + " result=" + (result > 0 ? "sent" : result < 0 ? "unavailable-or-error" : "waiting")
                + " reason=" + token(reason);
        if (line.equals(lastByPlane.put(channel, line))) return null;
        return line;
    }

    void reset() {
        lastByPlane.clear();
    }

    static String sampleKey(HudCheckState sample) {
        if (sample == null) return "none";
        if (sample.mode == HudCheckState.Mode.EXTENDED) {
            return "extended-" + (sample.extendedIndex + 1);
        }
        return "basic-m" + sample.maneuverIndex + "-l" + sample.laneIndex
                + "-d" + sample.distanceIndex + "-s" + sample.streetIndex
                + "-t" + sample.trafficLightIndex + "-mb" + sample.maneuverBitmap
                + "-lb" + sample.laneBitmap + "-tr" + sample.transliterate;
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) return "none";
        // Callers supply fixed stage names, numeric results and exception class names only.
        String bounded = value.substring(0, Math.min(160, value.length()));
        return bounded.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
