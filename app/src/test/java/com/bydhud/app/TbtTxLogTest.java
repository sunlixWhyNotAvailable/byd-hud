package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TbtTxLogTest {
    @Test
    public void jsonContainsTransportAndSemanticFields() {
        TbtTxLog.Entry entry = TbtTxLog.Entry.builder()
                .source("manual")
                .owner("com.waze")
                .generation(12L)
                .transactionId("tx-7")
                .reason("manual_frame")
                .plane("instrument_fid")
                .operation("set")
                .target("F3")
                .nativeId(7)
                .intermediateAmapIcon(11)
                .amapIcon(8)
                .roundaboutExit(3)
                .distanceMeters(420L)
                .road("Main \"Street\"")
                .routeEtaMs(600000L)
                .routeDurationSeconds(600L)
                .routeDistanceMeters(8200L)
                .nextStopEtaMs(120000L)
                .nextStopDurationSeconds(120L)
                .nextStopDistanceMeters(1600L)
                .argumentBytes(new byte[]{1, 2, 3, 4})
                .result(0)
                .durationMs(9L)
                .build();

        String json = TbtTxLog.toJson(entry, "send", 7L, 11L, 1700000000000L);

        assertTrue(json.contains("\"event\":\"send\""));
        assertTrue(json.contains("\"source\":\"manual\""));
        assertTrue(json.contains("\"owner\":\"com.waze\""));
        assertTrue(json.contains("\"plane\":\"instrument_fid\""));
        assertTrue(json.contains("\"nativeId\":7"));
        assertTrue(json.contains("\"intermediateAmapIcon\":11"));
        assertTrue(json.contains("\"amapIcon\":8"));
        assertTrue(json.contains("\"roundaboutExit\":3"));
        assertTrue(json.contains("\"distanceMeters\":420"));
        assertTrue(json.contains("\"road\":\"Main \\\"Street\\\"\""));
        assertTrue(json.contains("\"routeDistanceMeters\":8200"));
        assertTrue(json.contains("\"argumentsBase64\":\"AQIDBA==\""));
        assertTrue(json.contains("\"result\":0"));
        assertTrue(json.contains("\"durationMs\":9"));
    }

    @Test
    public void repeatIdentityIgnoresTransactionAndTimingButIncludesPayloadSemantics() {
        TbtTxLog.Entry first = baseEntry()
                .transactionId("tx-1")
                .durationMs(4L)
                .build();
        TbtTxLog.Entry second = baseEntry()
                .transactionId("tx-2")
                .durationMs(19L)
                .build();
        TbtTxLog.Entry changed = baseEntry()
                .transactionId("tx-3")
                .distanceMeters(421L)
                .build();

        assertTrue(TbtTxLog.sameIdentity(first, second));
        assertFalse(TbtTxLog.sameIdentity(first, changed));
        assertTrue(first.successful());
    }

    @Test
    public void failedEntriesAreNeverSuccessful() {
        TbtTxLog.Entry failed = baseEntry()
                .result(1)
                .error("transport rejected")
                .build();

        assertFalse(failed.successful());
    }

    private static TbtTxLog.Entry.Builder baseEntry() {
        return TbtTxLog.Entry.builder()
                .source("manual")
                .owner("com.waze")
                .generation(12L)
                .reason("manual_frame")
                .plane("instrument_fid")
                .operation("set")
                .target("F3")
                .nativeId(7)
                .intermediateAmapIcon(11)
                .amapIcon(8)
                .distanceMeters(420L)
                .road("Main Street")
                .argumentBytes(new byte[]{1, 2, 3})
                .result(0);
    }
}
