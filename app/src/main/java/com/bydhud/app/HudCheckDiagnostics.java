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
        if (sample.legacyBasic) {
            HudState state = sample.toHudState();
            line += " format=pre-3.6.3 f2=2 f6=6 maneuver=" + state.maneuverId
                    + " distance_m=" + state.distanceToIntersection
                    + " png_source=" + (sample.maneuverBitmap ? state.turnBitmapId
                    : HudState.TURN_BITMAP_BLANK_SOURCE_ID)
                    + " f19=0 f20=0 f30=empty f31=empty f32=0";
        }
        if (sample.isLiveCheck()) {
            DirectTbtFrame frame = sample.liveFrame();
            HudManeuverGeometry.Selection geometry = sample.liveGeometry();
            int pngSource = sample.mode == HudCheckState.Mode.BASIC && !sample.maneuverBitmap
                    ? HudState.TURN_BITMAP_BLANK_SOURCE_ID : sample.toHudState().turnBitmapId;
            line += " maneuver=" + frame.getBydManeuver()
                    + " distance_m=" + frame.getDistanceMeters()
                    + " amap=" + frame.getAmapManeuver()
                    + " tbt_broadcast=" + frame.getAmapBroadcastManeuver()
                    + " png_source=" + pngSource
                    + " geometry=" + geometry.name + " source=HUD_CHECK"
                    + " f19=" + geometry.longitude + " f20=" + geometry.latitude
                    + " f32=" + geometry.heading;
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
        if (sample.mode == HudCheckState.Mode.EXTENDED) {
            if (sample.isLivePipelineCheck()) {
                return "extended-" + (sample.extendedIndex + 1) + "-" + sample.liveScenarioKey();
            }
            return "extended-" + (sample.extendedIndex + 1);
        }
        return (sample.legacyBasic ? "basic-pre363-m" : "basic-m")
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
