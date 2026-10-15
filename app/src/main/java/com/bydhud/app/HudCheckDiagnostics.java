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
        if (sample.isLiveCheck()) {
            DirectTbtFrame frame = sample.liveFrame();
            HudManeuverGeometry.Selection geometry = sample.liveGeometry();
            boolean bitmap = sample.maneuverBitmap;
            boolean current = !sample.legacyBasic;
            String pngSource = bitmap ? Integer.toString(sample.toHudState().turnBitmapId)
                    : current ? Integer.toString(HudState.TURN_BITMAP_BLANK_SOURCE_ID) : "none";
            line += " format=" + (current ? "current" : "no-png")
                    + " maneuver=" + frame.getBydManeuver()
                    + " f28=" + (bitmap && !current ? "omitted" : frame.getBydManeuver())
                    + " f8=" + (bitmap ? "png" : current ? "blank_s72" : "omitted")
                    + " distance_m=" + frame.getDistanceMeters()
                    + " amap=" + frame.getAmapManeuver()
                    + " tbt_broadcast=" + frame.getAmapBroadcastManeuver()
                    + " png_source=" + pngSource
                    + " baseline_geometry=" + geometry.name + " source=HUD_CHECK"
                    + " baseline_f19=" + geometry.longitude + " baseline_f20=" + geometry.latitude
                    + " baseline_f32=" + geometry.heading;
            if (sample.mode == HudCheckState.Mode.EXTENDED) {
                line += " stage_fields=" + token(sample.extendedField())
                        + " stage_value=" + token(sample.extendedValue());
            }
            if (frame.getRoundaboutExit().hasDirection()) {
                line += " " + frame.getRoundaboutExit().diagnostics();
            }
        }
        if (line.equals(lastByPlane.put(channel, line))) return null;
        return line;
    }

    void reset() {
        lastByPlane.clear();
    }

    static String sampleKey(HudCheckState sample) {
        if (sample == null) return "none";
        String stage = sample.mode == HudCheckState.Mode.EXTENDED
                ? "extended-" + (sample.extendedIndex + 1) + "-" : "";
        return stage + (sample.legacyBasic ? "basic-no-png-m" : "basic-m")
                + sample.maneuverIndex + "-l" + sample.laneIndex
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
