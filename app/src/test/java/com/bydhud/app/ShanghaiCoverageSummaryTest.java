package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ShanghaiCoverageSummaryTest {
    @Test
    public void liveCaptureReportsTopicGapsAndLossWithoutBlockingReadiness() throws Exception {
        ShanghaiDiagnostics.Coverage coverage = coverage("capturing", 26, 3, 0, 0,
                stream("streaming", true), stream("streaming", true));
        assertTrue(coverage.ready);
        assertTrue(coverage.hasPartialCoverage());
    }

    @Test
    public void fullLiveAndFinalCaptureCanBeComplete() throws Exception {
        assertFalse(coverage("capturing", 26, 26, 0, 0,
                stream("streaming", false), stream("streaming", false)).hasPartialCoverage());
        assertFalse(coverage("stopped", 26, 26, 0, 0,
                stream("stopped", true), stream("stopped", true)).hasPartialCoverage());
    }

    @Test
    public void finalIncompleteStreamAndJournalLossArePartial() throws Exception {
        assertTrue(coverage("stopped", 26, 26, 0, 0,
                stream("stopped", false), stream("stopped", true)).hasPartialCoverage());
        assertTrue(coverage("capturing", 26, 26, 1, 0,
                stream("streaming", false), stream("streaming", false)).hasPartialCoverage());
        assertTrue(coverage("capturing", 26, 26, 0, 1,
                stream("streaming", false), stream("streaming", false)).hasPartialCoverage());
    }

    @Test
    public void liveStreamLossIsPartialBeforeCaptureStops() throws Exception {
        JSONObject adas = stream("streaming", false).put("droppedBytes", 1);
        assertTrue(coverage("capturing", 26, 26, 0, 0,
                adas, stream("streaming", false)).hasPartialCoverage());
    }

    private static ShanghaiDiagnostics.Coverage coverage(String state, int requested, int subscribed,
            long dropped, int sessionDropped, JSONObject adas, JSONObject pcap) throws Exception {
        JSONObject channels = new JSONObject()
                .put("someIpRequestedTopics", requested)
                .put("someIpSubscribedTopics", subscribed)
                .put("someIpDropped", dropped)
                .put("sessionEventsDropped", sessionDropped)
                .put("adb", new JSONObject().put("adas", adas).put("pcap", pcap));
        return new ShanghaiDiagnostics.Coverage(state, true, true, true, "", channels);
    }

    private static JSONObject stream(String status, boolean complete) throws Exception {
        return new JSONObject().put("status", status)
                .put("captureComplete", complete)
                .put("ready", true)
                .put("bytes", 32)
                .put("droppedBytes", 0)
                .put("error", "")
                .put("stopError", "");
    }
}
