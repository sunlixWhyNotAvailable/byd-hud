package com.bydhud.app;

//keeps route evidence thresholds explicit so short parser gaps are not mistaken for route end.

final class NavRouteEvidencePolicy {
    //models RawRouteState data here so transport and parser layers share a stable contract.
    enum RawRouteState {
        ACTIVE_ROUTE,
        PREVIEW,
        NO_ROUTE,
        ARRIVAL_CANDIDATE,
        UNKNOWN
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavRouteEvidencePolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasRawRouteEvidence(String packageName, String payload) {
        return classifyRawPayload(packageName, payload) == RawRouteState.ACTIVE_ROUTE;
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    static RawRouteState classifyRawPayload(String packageName, String payload) {
        String safePayload = payload == null ? "" : payload;
        if (safePayload.trim().isEmpty()) {
            return RawRouteState.NO_ROUTE;
        }
        NavSnapshot.SourceApp sourceApp = NavTextNormalizer.sourceApp(packageName);
        if (sourceApp == NavSnapshot.SourceApp.WAZE) {
            if (!isRelevantAccessibilityPayload(packageName, safePayload)) {
                return RawRouteState.UNKNOWN;
            }
            if (hasWazeRouteEvidence(safePayload)) {
                return RawRouteState.ACTIVE_ROUTE;
            }
            if (isWazeArrivalCandidate(safePayload)) {
                return RawRouteState.ARRIVAL_CANDIDATE;
            }
            return RawRouteState.NO_ROUTE;
        }
        if (sourceApp == NavSnapshot.SourceApp.GOOGLE_MAPS) {
            return classifyGoogleMapsPayload(safePayload);
        }
        return hasGenericRouteEvidence(safePayload)
                ? RawRouteState.ACTIVE_ROUTE
                : RawRouteState.UNKNOWN;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isRelevantAccessibilityPayload(String packageName, String payload) {
        NavSnapshot.SourceApp sourceApp = NavTextNormalizer.sourceApp(packageName);
        if (sourceApp == NavSnapshot.SourceApp.WAZE) {
            return WazeAccessibilityParser.isUsableWazePayload(payload);
        }
        return true;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasWazeRouteEvidence(String payload) {
        return hasWazeRouteNodeEvidence(payload);
    }

    //requires current-step distance plus route context so onboarding and settings text stay idle.
    static boolean hasWazeRouteNodeEvidence(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        boolean hasCurrentDistance = lower.contains(":id/navbardistance");
        boolean hasRouteContext = lower.contains(":id/navbarstreetline")
                || lower.contains(":id/lbldistancetodestination")
                || lower.contains(":id/lbltimetodestination")
                || lower.contains(":id/lblarrivaltime");
        return hasCurrentDistance && hasRouteContext;
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    private static RawRouteState classifyGoogleMapsPayload(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        if (isGoogleMapsNoRouteOrPreview(lower)) {
            return RawRouteState.PREVIEW;
        }
        if (hasGoogleMapsRouteEvidence(payload)) {
            return RawRouteState.ACTIVE_ROUTE;
        }
        return RawRouteState.NO_ROUTE;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasGoogleMapsRouteEvidence(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        boolean hasDistance = NavTextNormalizer.distanceMeters(payload, -1) >= 0
                || lower.contains("distance remaining is")
                || lower.contains("distance_text")
                || lower.contains("km")
                || lower.contains(" m");
        boolean hasInstruction = hasGoogleMapsInstructionPanel(lower)
                || lower.contains("turn ")
                || lower.contains("left")
                || lower.contains("right")
                || lower.contains("u-turn")
                || lower.contains("uturn")
                || lower.contains("roundabout")
                || lower.contains("head ")
                || lower.contains("continue")
                || lower.contains("straight");
        boolean hasEta = lower.contains("navigation_time_remaining_label")
                || lower.contains("eta")
                || lower.contains("arrival")
                || lower.contains(" min");
        return hasDistance && (hasInstruction || hasEta);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isGoogleMapsNoRouteOrPreview(String lower) {
        boolean previewMarker = lower.contains("route preview")
                || lower.contains("selected route")
                || lower.contains("alternate route")
                || lower.contains("driving mode")
                || lower.contains("choose destination")
                || lower.contains("add stop")
                || lower.contains("desc=start")
                || lower.contains("text=start");
        return previewMarker && !hasGoogleMapsInstructionPanel(lower);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasGoogleMapsInstructionPanel(String lower) {
        return lower.contains("navigation_instruction_panel")
                || lower.contains("step_instruction_container")
                || lower.contains("next_step_instruction")
                || lower.contains("top_cue_text")
                || lower.contains("bottom_cue_text")
                || lower.contains("close navigation");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isWazeArrivalCandidate(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        return lower.contains("arriving at")
                || lower.contains("arrival")
                || lower.contains("you have arrived");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasGenericRouteEvidence(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        boolean hasDistance = NavTextNormalizer.distanceMeters(payload, -1) >= 0
                || lower.contains("km")
                || lower.contains(" m");
        boolean hasNavigationText = lower.contains("turn")
                || lower.contains("left")
                || lower.contains("right")
                || lower.contains("street")
                || lower.contains("road")
                || lower.contains("eta");
        return hasDistance && hasNavigationText;
    }
}
