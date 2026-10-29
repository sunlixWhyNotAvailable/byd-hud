package com.bydhud.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class DenzaOemRecordingTest {
    private static final String ASSET_SHA256 =
            "d2658b7cebbea6a8d0e8cb826f655b1c15a85dfd75d1e495f51617771b35ef03";

    @Test public void productionRecordingPreservesCapturedSegmentAndParsesRoadInfo() throws Exception {
        byte[] asset = readAsset();
        assertEquals(ASSET_SHA256, sha256(asset));

        DenzaOemRecording recording = DenzaOemRecording.load(new ByteArrayInputStream(asset));
        assertEquals("evidence/nav/external/other/Denza/N9/20261012/logs/logcat/"
                + "shanghai_a8133864-a573-4382-8a04-f3310ad62c18/someip-events.jsonl",
                recording.sourcePath());
        assertEquals("39b333d11064c90535d392a2851332331e80c8d9118636cc309d2c8c502370ad",
                recording.sourceSha256());
        assertEquals(30_000_000_000L, recording.cycleDurationNanos());

        List<DenzaOemRecording.Frame> frames = recording.frames();
        assertEquals(292, frames.size());
        assertEquals(0L, frames.get(0).offsetNanos);
        assertEquals(6, frames.get(0).sourceSequence);
        assertEquals("71442ea2b1bba2f71e4cbc7f2d89e2d667f938f124460940974e02097053c012",
                frames.get(0).payloadSha256);
        assertEquals(29_946_555_386L, frames.get(291).offsetNanos);
        assertEquals(352, frames.get(291).sourceSequence);
        assertEquals("743d8b26cc6c89a25656fa3bf0cc22546ee5909d44256f5e6dd92cdc99ef24d0",
                frames.get(291).payloadSha256);

        long priorOffset = -1L;
        int priorSequence = 0;
        long decodedBytes = 0L;
        for (DenzaOemRecording.Frame frame : frames) {
            assertTrue(frame.offsetNanos > priorOffset);
            assertTrue(frame.sourceSequence > priorSequence);
            assertEquals(9, frame.distanceMeters);
            assertEquals(1, frame.nativeManeuver);
            assertEquals(2, frame.amapManeuver);
            assertEquals(2, frame.amapBroadcastManeuver);
            assertEquals("西藏中路", frame.street);
            byte[] payload = frame.payload();
            assertEquals(frame.payloadSha256, sha256(payload));
            decodedBytes += payload.length;
            priorOffset = frame.offsetNanos;
            priorSequence = frame.sourceSequence;
        }
        assertEquals(1_647_464L, decodedBytes);

        DenzaOemRecording.Frame first = frames.get(0);
        byte[] returnedPayload = first.payload();
        returnedPayload[0] ^= 1;
        assertNotEquals(returnedPayload[0], first.payload()[0]);
        assertFalse(first.payloadSha256.isEmpty());
        assertNotNull(recording.frames());
    }

    private static byte[] readAsset() throws Exception {
        try (InputStream input = DenzaOemRecordingTest.class
                .getResourceAsStream("/" + DenzaOemRecording.ASSET_PATH.substring(
                        DenzaOemRecording.ASSET_PATH.lastIndexOf('/') + 1))) {
            assertNotNull("The production Denza recording asset must be on the test classpath", input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
