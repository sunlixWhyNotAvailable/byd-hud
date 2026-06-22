package com.bydhud.app;

final class WazeCropCandidate {
    final long elapsedRealtimeMs;
    final int displayId;
    final String file;
    final String reason;
    final String direction;
    final String lanes;
    final int confidence;
    final String bucket;
    final String missingFile;
    final String snapshotManeuver;
    final String snapshotLanes;
    final String laneStatus;
    final String laneReason;
    final String laneRaw;
    final int laneDividers;
    final int laneCells;
    final int laneComponents;
    final boolean laneBlocksSingleFallback;

    WazeCropCandidate(
            long elapsedRealtimeMs,
            int displayId,
            String file,
            String reason,
            String direction,
            String lanes,
            int confidence,
            String bucket,
            String missingFile,
            String snapshotManeuver,
            String snapshotLanes) {
        this(
                elapsedRealtimeMs,
                displayId,
                file,
                reason,
                direction,
                lanes,
                confidence,
                bucket,
                missingFile,
                snapshotManeuver,
                snapshotLanes,
                null);
    }

    WazeCropCandidate(
            long elapsedRealtimeMs,
            int displayId,
            String file,
            String reason,
            String direction,
            String lanes,
            int confidence,
            String bucket,
            String missingFile,
            String snapshotManeuver,
            String snapshotLanes,
            WazeVisualCueParser.LaneGuidanceAnalysis laneAnalysis) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.displayId = displayId;
        this.file = file == null ? "" : file;
        this.reason = reason == null ? "" : reason;
        this.direction = direction == null ? "" : direction;
        this.lanes = lanes == null ? "" : lanes;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.bucket = bucket == null ? "" : bucket;
        this.missingFile = missingFile == null ? "" : missingFile;
        this.snapshotManeuver = snapshotManeuver == null ? "" : snapshotManeuver;
        this.snapshotLanes = snapshotLanes == null ? "" : snapshotLanes;
        WazeVisualCueParser.LaneGuidanceAnalysis safeAnalysis = laneAnalysis == null
                ? WazeVisualCueParser.LaneGuidanceAnalysis.none()
                : laneAnalysis;
        this.laneStatus = safeAnalysis.status.name();
        this.laneReason = safeAnalysis.reason.name();
        this.laneRaw = safeAnalysis.laneString;
        this.laneDividers = safeAnalysis.dividerCount;
        this.laneCells = safeAnalysis.cellCount;
        this.laneComponents = safeAnalysis.componentCount;
        this.laneBlocksSingleFallback = safeAnalysis.blocksSingleFallback;
    }

    String toJsonLine() {
        return "{"
                + "\"t\":" + elapsedRealtimeMs
                + ",\"displayId\":" + displayId
                + ",\"file\":\"" + NavCaptureStore.esc(file) + "\""
                + ",\"reason\":\"" + NavCaptureStore.esc(reason) + "\""
                + ",\"direction\":\"" + NavCaptureStore.esc(direction) + "\""
                + ",\"lanes\":\"" + NavCaptureStore.esc(lanes) + "\""
                + ",\"confidence\":" + confidence
                + ",\"bucket\":\"" + NavCaptureStore.esc(bucket) + "\""
                + ",\"missingFile\":\"" + NavCaptureStore.esc(missingFile) + "\""
                + ",\"snapshotManeuver\":\"" + NavCaptureStore.esc(snapshotManeuver) + "\""
                + ",\"snapshotLanes\":\"" + NavCaptureStore.esc(snapshotLanes) + "\""
                + ",\"laneStatus\":\"" + NavCaptureStore.esc(laneStatus) + "\""
                + ",\"laneReason\":\"" + NavCaptureStore.esc(laneReason) + "\""
                + ",\"laneRaw\":\"" + NavCaptureStore.esc(laneRaw) + "\""
                + ",\"laneDividers\":" + laneDividers
                + ",\"laneCells\":" + laneCells
                + ",\"laneComponents\":" + laneComponents
                + ",\"laneBlocksSingleFallback\":" + laneBlocksSingleFallback
                + "}";
    }
}
