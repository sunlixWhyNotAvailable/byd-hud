package com.bydhud.app;

import com.bydhud.navexport.RouteData;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class NavigatorRouteStoreTest {
    @Test public void realProducerRouteAssemblesOutOfOrderAndPreservesRawFields() throws Exception {
        double[] points = randomRoute(10_000);
        RouteData.Maneuver turn = new RouteData.Maneuver();
        turn.id = "turn-east";
        turn.ordinal = 4;
        turn.latitude = points[80];
        turn.longitude = points[81];
        turn.vertexIndex = 40;
        turn.instructionSegmentIndex = 7;
        turn.navigationSegmentIndex = 12;
        turn.type = "TURN_LEFT";
        turn.turnSide = "LEFT_SIDE";
        turn.incomingBearing = 12.375;
        turn.outgoingBearing = 271.625;
        turn.sourceRotation = 93;
        turn.syntheticPolyline = true;

        RouteData.Maneuver unknown = new RouteData.Maneuver();
        unknown.id = "turn-unknown";
        unknown.ordinal = 5;
        unknown.latitude = 49.123456789;
        unknown.longitude = 30.987654321;
        unknown.type = "UNKNOWN_RAW_TYPE";
        unknown.turnSide = "UNKNOWN_RAW_SIDE";

        RouteData encodedRoute = new RouteData(points,
                new RouteData.Maneuver[] {turn, unknown}, true);
        byte[] encoded = encodedRoute.encode();
        assertTrue("test route must exercise multiple chunks",
                encoded.length > NavigatorRouteStore.CHUNK_BYTES * 2);

        NavigatorRouteStore store = new NavigatorRouteStore();
        NavigatorRouteData.Header header = header("Waze", 22, 1, "r-1", 77);
        NavigatorRouteStore.CurrentStep step = new NavigatorRouteStore.CurrentStep(
                header, 8, 73, null, null, "turn-east", 4, 40,
                "TURN_LEFT", "LEFT_SIDE", turn.latitude, turn.longitude,
                7, 12, 201, 202, "associated-raw-id");
        NavigatorRouteStore.Result stepFirst = store.acceptStep(step, 1_000);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING, stepFirst.outcome);
        assertTrue(stepFirst.stepChanged);
        assertEquals(11_000, stepFirst.deadlineElapsedMs);

        int chunkCount = (encoded.length + NavigatorRouteStore.CHUNK_BYTES - 1)
                / NavigatorRouteStore.CHUNK_BYTES;
        NavigatorRouteStore.Result result = null;
        long now = 1_100;
        for (int index = chunkCount - 1; index >= 0; index--) {
            int start = index * NavigatorRouteStore.CHUNK_BYTES;
            int end = Math.min(encoded.length, start + NavigatorRouteStore.CHUNK_BYTES);
            byte[] payload = Arrays.copyOfRange(encoded, start, end);
            NavigatorRouteStore.Chunk chunk = chunk(header, index, chunkCount,
                    encoded.length, points.length / 2, 2, true, true, payload);
            result = store.acceptChunk(chunk, now++);
            if (index == chunkCount - 1) {
                assertEquals(NavigatorRouteStore.Outcome.COLLECTING, result.outcome);
                long deadline = result.deadlineElapsedMs;
                NavigatorRouteStore.Result duplicate = store.acceptChunk(chunk, now++);
                assertEquals(NavigatorRouteStore.Outcome.DUPLICATE, duplicate.outcome);
                assertEquals("a byte-identical duplicate cannot extend the idle deadline",
                        deadline, duplicate.deadlineElapsedMs);
            }
        }

        assertNotNull(result);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, result.outcome);
        NavigatorRouteData route = result.decodeWork.decode();
        NavigatorRouteStore.Result published = store.publish(result.decodeWork, route, now);
        assertEquals(NavigatorRouteStore.Outcome.PUBLISHED, published.outcome);
        assertTrue(published.newRevision);
        assertEquals(header.routeRevision, route.header().routeRevision);
        assertEquals(points.length / 2, route.pointCount());
        for (int i = 0; i < points.length; i += 2) {
            assertEquals(points[i], route.latitude(i / 2), 0);
            assertEquals(points[i + 1], route.longitude(i / 2), 0);
        }
        assertTrue(route.maneuversAvailable());
        assertEquals(2, route.maneuverCount());
        NavigatorRouteData.Maneuver actual = route.maneuver(0);
        assertEquals("turn-east", actual.id);
        assertEquals(4, actual.ordinal);
        assertEquals(turn.latitude, actual.latitude, 0);
        assertEquals(turn.longitude, actual.longitude, 0);
        assertEquals(40, actual.vertexIndex);
        assertEquals(7, actual.instructionSegmentIndex);
        assertEquals(12, actual.navigationSegmentIndex);
        assertEquals("TURN_LEFT", actual.type);
        assertEquals("LEFT_SIDE", actual.turnSide);
        assertEquals(12.375, actual.incomingBearing, 0);
        assertEquals(271.625, actual.outgoingBearing, 0);
        assertEquals(93, actual.sourceRotation);
        assertTrue(actual.syntheticPolyline);
        NavigatorRouteData.Maneuver absent = route.maneuver(1);
        assertTrue(Double.isNaN(absent.incomingBearing));
        assertTrue(Double.isNaN(absent.outgoingBearing));
        assertEquals(Integer.MIN_VALUE, absent.sourceRotation);
        assertEquals(-1, absent.vertexIndex);
        assertEquals(-1, absent.instructionSegmentIndex);
        assertEquals(-1, absent.navigationSegmentIndex);
        assertFalse(absent.syntheticPolyline);

        NavigatorRouteStore.CurrentSnapshot snapshot = store.snapshot("Waze");
        assertSame(route, snapshot.route);
        assertSame(step, snapshot.step);
        assertNull(snapshot.step.available);
        assertNull(snapshot.step.onRoute);
        assertEquals(Integer.valueOf(201), snapshot.step.currentInstruction);
        assertEquals(Integer.valueOf(202), snapshot.step.nextInstruction);
        assertEquals("associated-raw-id", snapshot.step.association);
    }

    @Test public void newerStepFencesOldDecodeAndLocalClearNeedsReconnect() throws Exception {
        NavigatorRouteStore store = new NavigatorRouteStore();
        NavigatorRouteData.Header first = header("Maps", 8, 1, "route-a", 100);
        EncodedChunks oldRoute = oneChunk(first, new RouteData(
                new double[] {49.5, 30.25}, new RouteData.Maneuver[0], false).encode(),
                1, 0, true, false);
        NavigatorRouteStore.Result pending = store.acceptChunk(oldRoute.chunks[0], 2_000);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, pending.outcome);
        NavigatorRouteData decodedButNotPublished = pending.decodeWork.decode();

        NavigatorRouteData.Header second = header("Maps", 8, 2, "route-b", 120);
        NavigatorRouteStore.CurrentStep step = new NavigatorRouteStore.CurrentStep(second,
                1, 119, true, true, "m-1", 0, 0, "TURN_RIGHT", "RIGHT_SIDE",
                49.6, 30.3, 3, 4, null, null, "source-association");
        NavigatorRouteStore.Result changed = store.acceptStep(step, 2_100);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING, changed.outcome);
        assertTrue(changed.newRevision);
        assertNull("new revision invalidates ready geometry immediately",
                store.snapshot("Maps").route);
        assertEquals(NavigatorRouteStore.Outcome.STALE,
                store.publish(pending.decodeWork, decodedButNotPublished, 2_101).outcome);
        assertEquals(NavigatorRouteStore.Outcome.STALE,
                store.acceptChunk(oldRoute.chunks[0], 2_102).outcome);
        assertSame(step, store.snapshot("Maps").step);

        store.clearSource("Maps");
        assertNull(store.snapshot("Maps").route);
        assertEquals(NavigatorRouteStore.Outcome.STALE,
                store.acceptChunk(oldRoute.chunks[0], 2_200).outcome);
        NavigatorRouteStore.Result reconnected = store.reconnect("Maps", 3_000);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING, reconnected.outcome);
        assertEquals(13_000, reconnected.deadlineElapsedMs);
        assertEquals("the old revision remains fenced even after reconnect",
                NavigatorRouteStore.Outcome.STALE,
                store.acceptChunk(oldRoute.chunks[0], 3_001).outcome);

        EncodedChunks newRoute = oneChunk(second, new RouteData(
                new double[] {49.6, 30.3}, new RouteData.Maneuver[0], false).encode(),
                1, 0, true, false);
        NavigatorRouteStore.Result ready = store.acceptChunk(newRoute.chunks[0], 3_010);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, ready.outcome);
        NavigatorRouteData route = ready.decodeWork.decode();
        assertEquals(NavigatorRouteStore.Outcome.PUBLISHED,
                store.publish(ready.decodeWork, route, 3_011).outcome);
        assertEquals(49.6, store.snapshot("Maps").route.latitude(0), 0);

        NavigatorRouteStore.Chunk conflictingMetadata = chunk(second, 0, 1,
                newRoute.chunks[0].payloadBytes(), 2, 0, true, false,
                newRoute.chunks[0].payload());
        NavigatorRouteStore.Result conflict = store.acceptChunk(conflictingMetadata, 3_012);
        assertEquals(NavigatorRouteStore.Outcome.INVALIDATED, conflict.outcome);
        assertEquals("metadata_mismatch", conflict.reason);
        assertNull(store.snapshot("Maps").route);

        NavigatorRouteStore duplicateStore = new NavigatorRouteStore();
        NavigatorRouteData.Header duplicateHeader = header("Maps", 8, 3, "route-c", 130);
        EncodedChunks duplicateRoute = oneChunk(duplicateHeader, new RouteData(
                new double[] {49.7, 30.4}, new RouteData.Maneuver[0], false).encode(),
                1, 0, true, false);
        NavigatorRouteStore.Chunk original = duplicateRoute.chunks[0];
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE,
                duplicateStore.acceptChunk(original, 4_000).outcome);
        byte[] changedBytes = original.payload();
        changedBytes[0] ^= 1;
        NavigatorRouteStore.Result conflictingDuplicate = duplicateStore.acceptChunk(
                chunk(duplicateHeader, 0, 1, original.payloadBytes(), 1, 0,
                        true, false, changedBytes), 4_001);
        assertEquals(NavigatorRouteStore.Outcome.INVALIDATED, conflictingDuplicate.outcome);
        assertEquals("conflicting_duplicate", conflictingDuplicate.reason);

        NavigatorRouteStore idleStore = new NavigatorRouteStore();
        NavigatorRouteData.Header initialClear = header("Waze", 0, 0, "", 0);
        assertEquals(NavigatorRouteStore.Outcome.CLEARED,
                idleStore.acceptClear(initialClear, "idle", 0).outcome);
        EncodedChunks zeroRevisionChunks = oneChunk(initialClear, new RouteData(
                new double[] {49, 30}, new RouteData.Maneuver[0], false).encode(),
                1, 0, true, false);
        assertEquals("revision-zero idle clear must fence queued route data",
                NavigatorRouteStore.Outcome.STALE,
                idleStore.acceptChunk(zeroRevisionChunks.chunks[0], 1).outcome);
    }

    @Test public void stepOnlyTimeoutGetsOneReplayThenRequiresExplicitReconnect() throws Exception {
        NavigatorRouteStore store = new NavigatorRouteStore();
        NavigatorRouteData.Header header = header("Waze", 5, 9, "route-timeout", 50);
        NavigatorRouteStore.CurrentStep step = new NavigatorRouteStore.CurrentStep(header,
                2, 49, null, null, "turn", -1, -1, "", "", null, null,
                -1, -1, null, null, "");
        NavigatorRouteStore.Result first = store.acceptStep(step, 100);
        assertEquals(10_100, first.deadlineElapsedMs);
        NavigatorRouteStore.Result duplicate = store.acceptStep(step, 5_000);
        assertEquals(NavigatorRouteStore.Outcome.DUPLICATE, duplicate.outcome);
        assertEquals(10_100, duplicate.deadlineElapsedMs);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING,
                store.expire("Waze", 10_099).outcome);
        EncodedChunks lateChunks = oneChunk(header, new RouteData(new double[0],
                new RouteData.Maneuver[0], false).encode(), 0, 0, false, false);
        NavigatorRouteStore.Result replay = store.acceptChunk(lateChunks.chunks[0], 10_100);
        assertEquals(NavigatorRouteStore.Outcome.REPLAY_REQUIRED, replay.outcome);
        assertTrue(replay.replay);
        assertEquals(20_100, replay.deadlineElapsedMs);
        NavigatorRouteStore.CurrentStep lateStep = new NavigatorRouteStore.CurrentStep(header,
                3, 10_100, null, null, "late-turn", -1, -1, "", "", null, null,
                -1, -1, null, null, "");
        NavigatorRouteStore.Result exhausted = store.acceptStep(lateStep, 20_100);
        assertEquals(NavigatorRouteStore.Outcome.RETRY_EXHAUSTED, exhausted.outcome);
        assertEquals(2, exhausted.replayCount);
        assertSame("expired input cannot replace the latest step", step,
                store.snapshot("Waze").step);
        assertEquals(NavigatorRouteStore.Outcome.STALE,
                store.acceptStep(step, 20_101).outcome);

        NavigatorRouteStore newerStore = new NavigatorRouteStore();
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING,
                newerStore.acceptStep(step, 100).outcome);
        NavigatorRouteData.Header newerHeader = header("Waze", 5, 10,
                "route-newer", 60);
        NavigatorRouteStore.CurrentStep newerStep = new NavigatorRouteStore.CurrentStep(
                newerHeader, 1, 60, null, null, "new-turn", -1, -1, "", "",
                null, null, -1, -1, null, null, "");
        NavigatorRouteStore.Result freshRevision = newerStore.acceptStep(newerStep, 70_000);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING, freshRevision.outcome);
        assertTrue(freshRevision.newRevision);
        assertFalse(freshRevision.replay);
        assertEquals(80_000, freshRevision.deadlineElapsedMs);

        NavigatorRouteStore.Result reconnected = store.reconnect("Waze", 30_000);
        assertEquals(NavigatorRouteStore.Outcome.COLLECTING, reconnected.outcome);
        assertEquals(40_000, reconnected.deadlineElapsedMs);
        EncodedChunks chunks = oneChunk(header, new RouteData(new double[0],
                new RouteData.Maneuver[0], false).encode(), 0, 0, false, false);
        NavigatorRouteStore.Result ready = store.acceptChunk(chunks.chunks[0], 30_010);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, ready.outcome);
        NavigatorRouteData route = ready.decodeWork.decode();
        assertEquals(NavigatorRouteStore.Outcome.PUBLISHED,
                store.publish(ready.decodeWork, route, 30_011).outcome);
        assertSame("complete stable routes have no TTL", route, store.snapshot("Waze").route);
        assertEquals(NavigatorRouteStore.Outcome.STALE,
                store.expire("Waze", 90_000).outcome);
        assertSame(route, store.snapshot("Waze").route);
    }

    @Test public void malformedGzipAndTrailingDecodedBytesNeverPublish() throws Exception {
        NavigatorRouteData.Header header = header("Maps", 9, 1, "bad", 1);
        byte[] good = new RouteData(new double[] {10, 20},
                new RouteData.Maneuver[0], false).encode();
        byte[] corruptCrc = good.clone();
        corruptCrc[corruptCrc.length - 1] ^= 1;
        assertDecodeRejected(header, corruptCrc, 1, 0, true, false);
        assertDecodeRejected(header, Arrays.copyOf(good, good.length - 4), 1, 0, true, false);

        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        try (DataOutputStream raw = new DataOutputStream(rawBytes)) {
            raw.writeInt(0x42595231);
            raw.writeInt(0);
            raw.writeBoolean(false);
            raw.writeInt(0);
            raw.writeByte(99);
        }
        assertDecodeRejected(header("Maps", 9, 2, "trailing", 2),
                gzip(rawBytes.toByteArray()), 0, 0, false, false);

        NavigatorRouteStore store = new NavigatorRouteStore();
        NavigatorRouteData.Header tooLarge = header("Maps", 9, 3, "cap", 3);
        NavigatorRouteStore.Result rejected = store.acceptChunk(new NavigatorRouteStore.Chunk(
                tooLarge, 0, 1, NavigatorRouteStore.MAX_ENCODED_BYTES + 1,
                0, 0, false, false, new byte[] {1}), 100);
        assertEquals(NavigatorRouteStore.Outcome.INVALIDATED, rejected.outcome);
        assertEquals("invalid_chunk_metadata", rejected.reason);
        assertNull(store.snapshot("Maps").route);

        NavigatorRouteData.Header decompressionCap = header("Maps", 9, 4, "decoded-cap", 4);
        byte[] oversizedDecoded = oversizedManeuverGzip(8_300, 4_000);
        EncodedChunks oversizedChunks = split(decompressionCap, oversizedDecoded,
                0, 8_300, false, true);
        NavigatorRouteStore capStore = new NavigatorRouteStore();
        NavigatorRouteStore.Result oversized = acceptAll(capStore, oversizedChunks, 200);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, oversized.outcome);
        try {
            oversized.decodeWork.decode();
            fail("decoded route above the stream limit was accepted");
        } catch (IOException expected) {
            assertEquals("decoded route exceeds limit", expected.getMessage());
            assertEquals(NavigatorRouteStore.Outcome.UNAVAILABLE,
                    capStore.fail(oversized.decodeWork, expected.getMessage(), 201).outcome);
        }
    }

    private static void assertDecodeRejected(NavigatorRouteData.Header header, byte[] encoded,
            int points, int maneuvers, boolean geometry, boolean maneuversAvailable)
            throws Exception {
        NavigatorRouteStore store = new NavigatorRouteStore();
        EncodedChunks chunks = split(header, encoded, points, maneuvers,
                geometry, maneuversAvailable);
        NavigatorRouteStore.Result result = acceptAll(store, chunks, 10);
        assertEquals(NavigatorRouteStore.Outcome.READY_TO_DECODE, result.outcome);
        try {
            result.decodeWork.decode();
            fail("malformed gzip/BYR1 payload decoded");
        } catch (IOException expected) {
            assertEquals(NavigatorRouteStore.Outcome.UNAVAILABLE,
                    store.fail(result.decodeWork, expected.getMessage(), 11).outcome);
            assertNull(store.snapshot(header.navigator).route);
        }
    }

    private static double[] randomRoute(int count) {
        double[] points = new double[count * 2];
        Random random = new Random(2516);
        for (int i = 0; i < points.length; i += 2) {
            points[i] = 48 + random.nextDouble();
            points[i + 1] = 29 + random.nextDouble();
        }
        return points;
    }

    private static NavigatorRouteData.Header header(String navigator, long epoch,
            long revision, String routeId, long sourceElapsedMs) {
        return new NavigatorRouteData.Header(navigator, epoch, revision,
                routeId, sourceElapsedMs);
    }

    private static EncodedChunks oneChunk(NavigatorRouteData.Header header, byte[] encoded,
            int points, int maneuvers, boolean geometry, boolean maneuversAvailable) {
        EncodedChunks chunks = split(header, encoded, points, maneuvers,
                geometry, maneuversAvailable);
        if (chunks.chunks.length != 1) throw new IllegalArgumentException("expected one chunk");
        return chunks;
    }

    private static EncodedChunks split(NavigatorRouteData.Header header, byte[] encoded,
            int points, int maneuvers, boolean geometry, boolean maneuversAvailable) {
        int chunkSize = NavigatorRouteStore.CHUNK_BYTES;
        int count = (encoded.length + chunkSize - 1) / chunkSize;
        NavigatorRouteStore.Chunk[] chunks = new NavigatorRouteStore.Chunk[count];
        for (int index = 0; index < count; index++) {
            int start = index * chunkSize;
            int end = Math.min(encoded.length, start + chunkSize);
            chunks[index] = chunk(header, index, count, encoded.length,
                    points, maneuvers, geometry, maneuversAvailable,
                    Arrays.copyOfRange(encoded, start, end));
        }
        return new EncodedChunks(chunks);
    }

    private static NavigatorRouteStore.Result acceptAll(NavigatorRouteStore store,
            EncodedChunks chunks, long nowMs) {
        NavigatorRouteStore.Result result = null;
        for (NavigatorRouteStore.Chunk chunk : chunks.chunks) {
            result = store.acceptChunk(chunk, nowMs++);
        }
        return result;
    }

    private static NavigatorRouteStore.Chunk chunk(NavigatorRouteData.Header header,
            int index, int count, int encodedBytes, int points, int maneuvers,
            boolean geometry, boolean maneuversAvailable, byte[] payload) {
        return new NavigatorRouteStore.Chunk(header, index, count, encodedBytes,
                points, maneuvers, geometry, maneuversAvailable, payload);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(raw);
        }
        return bytes.toByteArray();
    }

    private static byte[] oversizedManeuverGzip(int maneuverCount, int idLength)
            throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        String id = "x".repeat(idLength);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(compressed))) {
            out.writeInt(0x42595231);
            out.writeInt(0);
            out.writeBoolean(true);
            out.writeInt(maneuverCount);
            for (int i = 0; i < maneuverCount; i++) {
                out.writeUTF(id);
                out.writeInt(i);
                out.writeDouble(0);
                out.writeDouble(0);
                out.writeInt(-1);
                out.writeInt(-1);
                out.writeInt(-1);
                out.writeUTF("");
                out.writeUTF("");
                out.writeDouble(Double.NaN);
                out.writeDouble(Double.NaN);
                out.writeInt(Integer.MIN_VALUE);
                out.writeBoolean(false);
            }
        }
        return compressed.toByteArray();
    }

    private static final class EncodedChunks {
        final NavigatorRouteStore.Chunk[] chunks;

        EncodedChunks(NavigatorRouteStore.Chunk[] chunks) {
            this.chunks = chunks;
        }
    }
}
