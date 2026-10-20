package com.bydhud.app;

import java.util.HashMap;
import java.util.Map;

/** Worker-owned, bounded per-plane summaries; never includes payload or street text. */
final class HudCheckDiagnostics {
    private final Map<String, String> lastByPlane = new HashMap<>();

    String changed(HudCheckState sample, String plane, int result, String reason) {
        return changed(sample, plane, result, reason, null, "");
    }

    String changed(HudCheckState sample, String plane, int result, String reason,
            NativeEtaTransportPolicy.Endpoint endpoint, String selectionReason) {
        if (sample == null || !sample.running) return null;
        String channel = token(plane);
        String line = "hud_check case=" + sampleKey(sample) + " plane=" + channel
                + " result=" + (result == -2 ? "skipped"
                        : result > 0 ? ("AMap".equals(plane) ? "dispatched" : "sent")
                        : result < 0 ? "unavailable-or-error" : "waiting")
                + " reason=" + token(reason);
        if (endpoint != null) {
            line += " amap_endpoint=" + endpoint.name().toLowerCase(java.util.Locale.ROOT)
                    + " amap_selection=" + token(selectionReason);
        }
        if (sample.isLiveCheck()) {
            DirectTbtFrame frame = sample.liveFrame();
            HudManeuverGeometry.Selection geometry = sample.liveGeometry();
            boolean bitmap = sample.effectiveManeuverBitmap();
            int code = sample.liveNativeManeuver();
            boolean list = sample.format == HudCheckState.Format.B
                    || sample.format == HudCheckState.Format.C;
            String pngSource = bitmap ? Integer.toString(sample.toHudState().turnBitmapId) : "none";
            String lanes = "omitted";
            if (sample.format == HudCheckState.Format.NO_PNG) {
                lanes = sample.effectiveLaneBitmap() ? "png" : "empty_bytes";
            } else if (sample.format == HudCheckState.Format.C) {
                StringBuilder codes = new StringBuilder("packed[");
                for (HudLaneModel.LaneSpec lane : HudLaneModel.parse(sample.toHudState())) {
                    if (codes.length() > 7) codes.append(',');
                    codes.append(lane.instrumentCode());
                }
                lanes = codes.append(']').toString();
            }
            line += " format=" + sample.format.name()
                    + " code_table=" + sample.effectiveCodeTable().name()
                    + " maneuver=" + frame.getBydManeuver()
                    + " f28=" + code
                    + " f7=" + lanes
                    + " f8=" + (bitmap ? "png" : list ? "packed[" + code + "]" : "omitted")
                    + " distance_m=" + frame.getDistanceMeters()
                    + " amap=" + frame.getAmapManeuver()
                    + " tbt_broadcast=" + frame.getAmapBroadcastManeuver()
                    + " png_source=" + pngSource + " source=HUD_CHECK";
            if (sample.format == HudCheckState.Format.D) {
                line += " baseline_geometry=omitted baseline_f2=2 baseline_route_m=12345"
                        + " baseline_route_s=1200 baseline_limit=60";
            } else {
                line += " baseline_geometry=" + geometry.name
                        + " baseline_f19=" + geometry.longitude + " baseline_f20=" + geometry.latitude
                        + " baseline_f32=" + geometry.heading;
            }
            if (sample.mode == HudCheckState.Mode.EXTENDED) {
                line += " stage_fields=" + token(sample.extendedField())
                        + " stage_value=" + token(sample.extendedValue());
            } else {
                String f6 = sample.streetTestRoadClass ? "7"
                        : sample.format == HudCheckState.Format.D ? "omitted"
                        : HudLaneModel.parse(sample.toHudState()).length == 0 ? "1" : "6";
                String offOptionalField = sample.format == HudCheckState.Format.D ? "omitted" : "empty";
                line += " street_test_destination=" + (sample.streetTestDestination ? "on" : "off")
                        + " street_test_guide_point=" + (sample.streetTestGuidePoint ? "on" : "off")
                        + " street_test_map_state=" + (sample.streetTestMapState ? "on" : "off")
                        + " street_test_road_class=" + (sample.streetTestRoadClass ? "on" : "off")
                        + " f2=" + (sample.streetTestMapState || sample.format == HudCheckState.Format.D ? 2 : 0)
                        + " f6=" + f6
                        + " f25=" + (sample.streetTestDestination
                                ? HudCheckPayload.STREET_TEST_DESTINATION : offOptionalField)
                        + " f26=" + HudCheckPayload.STREET_TEST_ARRIVAL_TIME
                        + " f31=" + (sample.streetTestGuidePoint
                                ? HudCheckPayload.STREET_TEST_GUIDE_POINT : offOptionalField);
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
        return stage + "basic-" + sample.format.name() + "-" + sample.effectiveCodeTable().name() + "-m"
                + sample.maneuverIndex + "-l" + sample.laneIndex
                + "-d" + sample.distanceIndex + "-s" + sample.streetIndex
                + "-t" + sample.trafficLightIndex + "-mb" + sample.effectiveManeuverBitmap()
                + "-lb" + sample.effectiveLaneBitmap() + "-tr" + sample.transliterate
                + (sample.mode == HudCheckState.Mode.BASIC
                ? "-st" + sample.streetTestDestination + sample.streetTestGuidePoint
                        + sample.streetTestMapState + sample.streetTestRoadClass : "");
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) return "none";
        // Callers supply fixed stage names, numeric results and exception class names only.
        String bounded = value.substring(0, Math.min(160, value.length()));
        return bounded.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
