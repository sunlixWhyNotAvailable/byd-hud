package com.bydhud.mapcapture;

/** Wire validation and log selection shared by both navigator payloads. */
public final class LifecycleJournalPolicy {
    private LifecycleJournalPolicy() { }

    public static boolean shouldLog(boolean detailed, String name) {
        if (detailed) return true;
        return !(name.startsWith("hud_camera_trace_") && !name.contains("error")
                || name.equals("hud_marker_input") || name.equals("hud_marker_sample")
                || name.equals("hud_follow_input") || name.equals("hud_route_applied")
                || name.equals("hud_camera_observation") || name.equals("following_state")
                || name.equals("navigation_camera_state") || name.equals("hud_camera_render_frame_entry")
                || name.equals("hud_camera_render_frame_return") || name.equals("hud_alert_boundary") || name.equals("hud_camera_seed")
                || name.equals("hud_marker_texture"));
    }

    public static boolean ackWithinBatch(long complete, long partial, int chunk,
            long sentComplete, long sentPartial, int sentChunk) {
        return complete >= 0L && complete <= sentComplete && partial >= 0L && chunk >= 0
                && (partial == 0L ? chunk == 0 : partial > complete && chunk > 0
                && partial == sentPartial && chunk <= sentChunk);
    }
}
