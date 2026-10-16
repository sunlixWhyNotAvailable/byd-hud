package com.bydhud.app;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;

/** Pure route assembly, decoding and publication fence for navigator exports. */
public final class NavigatorRouteStore {
    public static final int CHUNK_BYTES = 24 * 1024;
    public static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_DECODED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_POINTS = 1_000_000;
    public static final int MAX_MANEUVERS = 10_000;
    public static final int MAX_CHUNKS = 8_192;
    public static final long CHUNK_IDLE_TIMEOUT_MS = 10_000L;
    public static final long ASSEMBLY_TIMEOUT_MS = 60_000L;
    public static final long NO_DEADLINE = -1L;

    public enum Outcome {
        COLLECTING,
        DUPLICATE,
        STALE,
        READY_TO_DECODE,
        PUBLISHED,
        INVALIDATED,
        CLEARED,
        UNAVAILABLE,
        REPLAY_REQUIRED,
        RETRY_EXHAUSTED
    }

    public static final class Chunk {
        public final NavigatorRouteData.Header header;
        public final int chunkIndex;
        public final int chunkCount;
        public final int encodedBytes;
        public final int pointCount;
        public final int maneuverCount;
        public final boolean geometryAvailable;
        public final boolean maneuversAvailable;
        private final byte[] payload;

        public Chunk(NavigatorRouteData.Header header, int chunkIndex, int chunkCount,
                int encodedBytes, int pointCount, int maneuverCount,
                boolean geometryAvailable, boolean maneuversAvailable, byte[] payload) {
            this.header = Objects.requireNonNull(header, "header");
            this.chunkIndex = chunkIndex;
            this.chunkCount = chunkCount;
            this.encodedBytes = encodedBytes;
            this.pointCount = pointCount;
            this.maneuverCount = maneuverCount;
            this.geometryAvailable = geometryAvailable;
            this.maneuversAvailable = maneuversAvailable;
            this.payload = payload == null || payload.length > CHUNK_BYTES
                    ? payload : payload.clone();
        }

        public int payloadBytes() {
            return payload == null ? -1 : payload.length;
        }

        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }

    /** Raw source fields; absent values stay absent and source indices remain uninterpreted. */
    public static final class CurrentStep {
        public final NavigatorRouteData.Header header;
        public final long stepSerial;
        public final long stepElapsedMs;
        public final Boolean available;
        public final Boolean onRoute;
        public final String id;
        public final int ordinal;
        public final int vertexIndex;
        public final String type;
        public final String turnSide;
        public final Double latitude;
        public final Double longitude;
        public final int currentSegmentIndex;
        public final int nextSegmentIndex;
        public final Integer currentInstruction;
        public final Integer nextInstruction;
        public final String association;

        public CurrentStep(NavigatorRouteData.Header header, long stepSerial, long stepElapsedMs,
                Boolean available, Boolean onRoute, String id, int ordinal, int vertexIndex,
                String type, String turnSide, Double latitude, Double longitude,
                int currentSegmentIndex, int nextSegmentIndex, Integer currentInstruction,
                Integer nextInstruction, String association) {
            this.header = Objects.requireNonNull(header, "header");
            if (stepSerial < 0 || stepElapsedMs < 0) {
                throw new IllegalArgumentException("invalid step identity or source time");
            }
            if ((latitude == null) != (longitude == null)
                    || (latitude != null && (!validCoordinate(latitude, 90)
                    || !validCoordinate(longitude, 180)))) {
                throw new IllegalArgumentException("invalid step coordinates");
            }
            this.stepSerial = stepSerial;
            this.stepElapsedMs = stepElapsedMs;
            this.available = available;
            this.onRoute = onRoute;
            this.id = text(id, 512, "id");
            this.ordinal = ordinal;
            this.vertexIndex = vertexIndex;
            this.type = text(type, 512, "type");
            this.turnSide = text(turnSide, 512, "turnSide");
            this.latitude = latitude;
            this.longitude = longitude;
            this.currentSegmentIndex = currentSegmentIndex;
            this.nextSegmentIndex = nextSegmentIndex;
            this.currentInstruction = currentInstruction;
            this.nextInstruction = nextInstruction;
            this.association = text(association, 256, "association");
        }
    }

    public static final class Result {
        public final Outcome outcome;
        public final NavigatorRouteData.Header header;
        public final String reason;
        public final DecodeWork decodeWork;
        public final boolean newRevision;
        public final boolean replay;
        public final boolean stepChanged;
        public final long deadlineElapsedMs;
        public final int replayCount;

        private Result(Outcome outcome, NavigatorRouteData.Header header, String reason,
                DecodeWork decodeWork, boolean newRevision, boolean replay,
                boolean stepChanged, long deadlineElapsedMs, int replayCount) {
            this.outcome = outcome;
            this.header = header;
            this.reason = reason;
            this.decodeWork = decodeWork;
            this.newRevision = newRevision;
            this.replay = replay;
            this.stepChanged = stepChanged;
            this.deadlineElapsedMs = deadlineElapsedMs;
            this.replayCount = replayCount;
        }
    }

    public static final class CurrentSnapshot {
        public final NavigatorRouteData.Header header;
        public final NavigatorRouteData route;
        public final CurrentStep step;
        public final Outcome outcome;
        public final String reason;

        private CurrentSnapshot(State state) {
            header = state.header;
            route = state.route;
            step = state.step;
            outcome = state.outcome;
            reason = state.reason;
        }
    }

    public static final class DecodeWork {
        public final NavigatorRouteData.Header header;
        public final int encodedBytes;
        public final int chunkCount;
        public final int pointCount;
        public final int maneuverCount;
        public final boolean replay;
        private final Assembly assembly;

        private DecodeWork(Assembly assembly) {
            this.assembly = assembly;
            this.header = assembly.metadata.header;
            this.encodedBytes = assembly.encodedTotal;
            this.chunkCount = assembly.chunks.length;
            this.pointCount = assembly.metadata.pointCount;
            this.maneuverCount = assembly.metadata.maneuverCount;
            this.replay = assembly.replayCount > 0;
        }

        /** Decode on a worker thread; the compressed chunks are streamed in index order. */
        public NavigatorRouteData decode() throws IOException {
            return decodeRoute(this);
        }
    }

    private final Map<String, State> states = new HashMap<>();

    public synchronized Result acceptChunk(Chunk chunk, long nowMs) {
        Objects.requireNonNull(chunk, "chunk");
        checkTime(nowMs);
        NavigatorRouteData.Header header = chunk.header;
        State state = stateFor(header);
        int order = compare(header, state.header);
        if (order < 0) return result(Outcome.STALE, state, null, false, false, false, NO_DEADLINE);
        if (order > 0) beginRevision(state, header, nowMs, null);
        else if (!state.header.sameSnapshot(header)) return invalidate(state, "header_mismatch");
        Result expired = expireIfDue(state, order, nowMs);
        if (expired != null) return expired;
        if (terminal(state.outcome)) return result(Outcome.STALE, state, null, false, false,
                false, NO_DEADLINE);

        if (state.assembly == null) state.assembly = new Assembly(header, nowMs, 0);
        Assembly assembly = state.assembly;
        String invalid = validateChunk(chunk);
        if (invalid != null) return invalidate(state, invalid);
        Metadata incoming = new Metadata(chunk);
        if (assembly.metadata == null) {
            assembly.metadata = incoming;
            assembly.chunks = new byte[chunk.chunkCount][];
        } else if (!assembly.metadata.same(incoming)) {
            return invalidate(state, "metadata_mismatch");
        }

        byte[] bytes = chunk.payload;
        byte[] prior = assembly.chunks[chunk.chunkIndex];
        if (prior != null) {
            if (Arrays.equals(prior, bytes)) {
                return result(Outcome.DUPLICATE, state, null, false,
                        assembly.replayCount > 0, false, deadlineFor(state));
            }
            return invalidate(state, "conflicting_duplicate");
        }
        if ((long) assembly.encodedTotal + bytes.length > chunk.encodedBytes) {
            return invalidate(state, "encoded_length_exceeded");
        }
        assembly.chunks[chunk.chunkIndex] = bytes;
        assembly.received++;
        assembly.encodedTotal += bytes.length;
        assembly.lastNewChunkMs = nowMs;
        if (assembly.received != assembly.chunks.length) {
            state.outcome = Outcome.COLLECTING;
            return result(Outcome.COLLECTING, state, null, order > 0,
                    assembly.replayCount > 0, false, deadline(assembly));
        }
        if (assembly.encodedTotal != chunk.encodedBytes) {
            return invalidate(state, "encoded_length_mismatch");
        }
        assembly.work = new DecodeWork(assembly);
        state.outcome = Outcome.READY_TO_DECODE;
        return result(Outcome.READY_TO_DECODE, state, assembly.work, order > 0,
                assembly.replayCount > 0, false, NO_DEADLINE);
    }

    public synchronized Result acceptStep(CurrentStep step, long nowMs) {
        Objects.requireNonNull(step, "step");
        checkTime(nowMs);
        State state = stateFor(step.header);
        int order = compare(step.header, state.header);
        if (order < 0) return result(Outcome.STALE, state, null, false, false, false, NO_DEADLINE);
        if (order > 0) beginRevision(state, step.header, nowMs, null);
        else if (!state.header.sameSnapshot(step.header)) return invalidate(state, "header_mismatch");
        Result expired = expireIfDue(state, order, nowMs);
        if (expired != null) return expired;
        if (terminal(state.outcome)) return result(Outcome.STALE, state, null, false, false,
                false, NO_DEADLINE);
        if (step.header.routeRevision == 0) return invalidate(state, "step_without_route_revision");
        if (state.step != null && step.stepSerial <= state.step.stepSerial) {
            return result(Outcome.DUPLICATE, state, null, false,
                    state.assembly != null && state.assembly.replayCount > 0,
                    false, deadlineFor(state));
        }
        state.step = step;
        boolean newRevision = order > 0;
        if (state.assembly == null) state.assembly = new Assembly(step.header, nowMs, 0);
        boolean collecting = state.outcome == Outcome.COLLECTING
                || state.outcome == Outcome.REPLAY_REQUIRED;
        long until = collecting ? deadline(state.assembly) : NO_DEADLINE;
        return result(state.outcome, state, null, newRevision,
                state.assembly.replayCount > 0, true, until);
    }

    public synchronized Result acceptClear(NavigatorRouteData.Header header,
            String reason, long nowMs) {
        return acceptTerminalEvent(header, reason, nowMs, Outcome.CLEARED);
    }

    public synchronized Result acceptUnavailable(NavigatorRouteData.Header header,
            String reason, long nowMs) {
        return acceptTerminalEvent(header, reason, nowMs, Outcome.UNAVAILABLE);
    }

    private Result acceptTerminalEvent(NavigatorRouteData.Header header, String reason,
            long nowMs, Outcome outcome) {
        Objects.requireNonNull(header, "header");
        checkTime(nowMs);
        State state = stateFor(header);
        int order = compare(header, state.header);
        if (order < 0) return result(Outcome.STALE, state, null, false, false, false, NO_DEADLINE);
        if (order == 0 && !state.header.sameSnapshot(header)) return invalidate(state, "header_mismatch");
        if (order > 0) {
            state.header = header;
        }
        state.route = null;
        state.step = null;
        state.assembly = null;
        state.published = false;
        state.sourceTerminal = true;
        state.replayCount = 0;
        state.reason = boundedReason(reason);
        state.outcome = outcome;
        return result(outcome, state, null, order > 0, false, false, NO_DEADLINE);
    }

    /** Expire the active assembly for one source using a monotonic elapsedRealtime timestamp. */
    public synchronized Result expire(String navigator, long nowMs) {
        checkTime(nowMs);
        State state = states.get(navigator);
        if (state == null || state.assembly == null
                || (state.outcome != Outcome.COLLECTING && state.outcome != Outcome.REPLAY_REQUIRED)) {
            return result(Outcome.STALE, state, null, false, false, false, NO_DEADLINE);
        }
        Assembly assembly = state.assembly;
        long until = deadline(assembly);
        if (nowMs < until) return result(state.outcome, state, null, false,
                assembly.replayCount > 0, false, until);
        if (assembly.replayCount == 0) {
            state.assembly = new Assembly(state.header, nowMs, 1);
            state.replayCount = 1;
            state.outcome = Outcome.REPLAY_REQUIRED;
            state.reason = "assembly_timeout";
            return result(Outcome.REPLAY_REQUIRED, state, null, false, true,
                    false, deadline(state.assembly));
        }
        state.assembly = null;
        state.route = null;
        state.published = false;
        state.outcome = Outcome.RETRY_EXHAUSTED;
        state.replayCount = 2;
        state.reason = "replay_timeout";
        return result(Outcome.RETRY_EXHAUSTED, state, null, false, true, false, NO_DEADLINE);
    }

    /** Publish only the decode result belonging to the still-current assembly. */
    public synchronized Result publish(DecodeWork work, NavigatorRouteData route, long nowMs) {
        Objects.requireNonNull(work, "work");
        checkTime(nowMs);
        State state = states.get(work.header.navigator);
        if (state == null || state.assembly != work.assembly || state.assembly.work != work
                || state.outcome != Outcome.READY_TO_DECODE) {
            return result(Outcome.STALE, state, null, false, work.replay, false, NO_DEADLINE);
        }
        if (route == null || !work.header.sameSnapshot(route.header())
                || route.pointCount() != work.pointCount
                || route.maneuverCount() != work.maneuverCount
                || route.geometryAvailable() != state.assembly.metadata.geometryAvailable
                || route.maneuversAvailable() != state.assembly.metadata.maneuversAvailable) {
            return invalidate(state, "decoded_metadata_mismatch");
        }
        boolean firstPublication = !state.published;
        state.route = route;
        state.published = true;
        state.outcome = Outcome.PUBLISHED;
        state.reason = "";
        state.assembly.work = null;
        return result(Outcome.PUBLISHED, state, null, firstPublication,
                work.replay, false, NO_DEADLINE);
    }

    public synchronized Result fail(DecodeWork work, String reason, long nowMs) {
        Objects.requireNonNull(work, "work");
        checkTime(nowMs);
        State state = states.get(work.header.navigator);
        if (state == null || state.assembly != work.assembly || state.assembly.work != work) {
            return result(Outcome.STALE, state, null, false, work.replay, false, NO_DEADLINE);
        }
        state.route = null;
        state.published = false;
        state.outcome = Outcome.UNAVAILABLE;
        state.reason = boundedReason(reason);
        state.assembly = null;
        return result(Outcome.UNAVAILABLE, state, null, false,
                work.replay, false, NO_DEADLINE);
    }

    public synchronized CurrentSnapshot snapshot(String navigator) {
        State state = states.get(navigator);
        return state == null ? null : new CurrentSnapshot(state);
    }

    /** Clear locally while retaining the revision watermark against queued old chunks. */
    public synchronized void clearSource(String navigator) {
        if (navigator == null || navigator.isEmpty()) return;
        State state = states.computeIfAbsent(navigator, ignored -> new State());
        state.route = null;
        state.step = null;
        state.assembly = null;
        state.published = false;
        state.outcome = Outcome.CLEARED;
        state.reason = "source_cleared";
        state.sourceTerminal = false;
        state.replayCount = 0;
    }

    public synchronized void clearAll() {
        for (State state : states.values()) {
            state.route = null;
            state.step = null;
            state.assembly = null;
            state.published = false;
            state.outcome = Outcome.CLEARED;
            state.reason = "store_cleared";
            state.sourceTerminal = false;
            state.replayCount = 0;
        }
    }

    /** Re-open collection after an explicit source reconnect without dropping a ready route. */
    public synchronized Result reconnect(String navigator, long nowMs) {
        checkTime(nowMs);
        State state = states.get(navigator);
        if (state == null) return result(Outcome.STALE, null, null, false, false, false, NO_DEADLINE);
        if (state.route != null) {
            if (state.assembly != null) state.assembly.replayCount = 0;
            state.replayCount = 0;
            state.reason = "";
            return result(Outcome.PUBLISHED, state, null, false, false, false, NO_DEADLINE);
        }
        if (state.header == null || state.sourceTerminal) {
            return result(state.outcome, state, null, false, false, false, NO_DEADLINE);
        }
        state.assembly = new Assembly(state.header, nowMs, 0);
        state.outcome = Outcome.COLLECTING;
        state.sourceTerminal = false;
        state.replayCount = 0;
        state.reason = "";
        return result(Outcome.COLLECTING, state, null, false, false,
                false, deadline(state.assembly));
    }

    private State stateFor(NavigatorRouteData.Header header) {
        return states.computeIfAbsent(header.navigator, ignored -> new State());
    }

    private Result expireIfDue(State state, int revisionOrder, long nowMs) {
        if (revisionOrder == 0 && state.assembly != null
                && (state.outcome == Outcome.COLLECTING
                || state.outcome == Outcome.REPLAY_REQUIRED)
                && nowMs >= deadline(state.assembly)) {
            return expire(state.header.navigator, nowMs);
        }
        return null;
    }

    private void beginRevision(State state, NavigatorRouteData.Header header,
            long nowMs, CurrentStep step) {
        state.header = header;
        state.route = null;
        state.step = step;
        state.assembly = new Assembly(header, nowMs, 0);
        state.published = false;
        state.sourceTerminal = false;
        state.replayCount = 0;
        state.outcome = Outcome.COLLECTING;
        state.reason = "";
    }

    private Result invalidate(State state, String reason) {
        if (state != null) {
            state.route = null;
            state.published = false;
            state.outcome = Outcome.INVALIDATED;
            state.sourceTerminal = false;
            state.replayCount = 0;
            state.reason = reason;
            state.assembly = null;
        }
        return result(Outcome.INVALIDATED, state, null, false, false, false, NO_DEADLINE);
    }

    private static String validateChunk(Chunk chunk) {
        int payloadBytes = chunk.payloadBytes();
        if (chunk.header.routeRevision == 0
                || chunk.encodedBytes <= 0 || chunk.encodedBytes > MAX_ENCODED_BYTES
                || chunk.chunkCount <= 0 || chunk.chunkCount > MAX_CHUNKS
                || chunk.chunkCount > chunk.encodedBytes
                || chunk.chunkIndex < 0 || chunk.chunkIndex >= chunk.chunkCount
                || payloadBytes <= 0 || payloadBytes > CHUNK_BYTES
                || chunk.pointCount < 0 || chunk.pointCount > MAX_POINTS
                || chunk.maneuverCount < 0 || chunk.maneuverCount > MAX_MANEUVERS
                || (!chunk.geometryAvailable && chunk.pointCount != 0)
                || (!chunk.maneuversAvailable && chunk.maneuverCount != 0)) {
            return "invalid_chunk_metadata";
        }
        int expectedChunks = (chunk.encodedBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (chunk.chunkCount != expectedChunks) return "invalid_chunk_count";
        int expectedBytes = chunk.chunkIndex == chunk.chunkCount - 1
                ? chunk.encodedBytes - CHUNK_BYTES * (chunk.chunkCount - 1) : CHUNK_BYTES;
        if (payloadBytes != expectedBytes) return "invalid_chunk_size";
        return null;
    }

    private static int compare(NavigatorRouteData.Header left,
            NavigatorRouteData.Header right) {
        if (right == null) return 1;
        int epoch = Long.compare(left.producerEpoch, right.producerEpoch);
        return epoch == 0 ? Long.compare(left.routeRevision, right.routeRevision) : epoch;
    }

    private static boolean terminal(Outcome outcome) {
        return outcome == Outcome.CLEARED || outcome == Outcome.UNAVAILABLE
                || outcome == Outcome.INVALIDATED || outcome == Outcome.RETRY_EXHAUSTED;
    }

    private static long deadlineFor(State state) {
        return state != null && state.assembly != null
                && (state.outcome == Outcome.COLLECTING || state.outcome == Outcome.REPLAY_REQUIRED)
                ? deadline(state.assembly) : NO_DEADLINE;
    }

    private static long deadline(Assembly assembly) {
        long idleBase = assembly.lastNewChunkMs >= 0
                ? assembly.lastNewChunkMs : assembly.startedAtMs;
        return Math.min(addTime(idleBase, CHUNK_IDLE_TIMEOUT_MS),
                addTime(assembly.startedAtMs, ASSEMBLY_TIMEOUT_MS));
    }

    private static long addTime(long base, long duration) {
        return base > Long.MAX_VALUE - duration ? Long.MAX_VALUE : base + duration;
    }

    private static void checkTime(long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative monotonic time");
    }

    private static Result result(Outcome outcome, State state, DecodeWork work,
            boolean newRevision, boolean replay, boolean stepChanged, long deadlineMs) {
        return new Result(outcome, state == null ? null : state.header,
                state == null ? "" : state.reason, work, newRevision, replay,
                stepChanged, deadlineMs, state == null ? 0 : state.replayCount);
    }

    private static String boundedReason(String reason) {
        if (reason == null) return "";
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }

    private static String text(String value, int max, String field) {
        if (value == null) return "";
        if (value.length() > max) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    private static NavigatorRouteData decodeRoute(DecodeWork work) throws IOException {
        Assembly assembly = work.assembly;
        byte[][] chunks = assembly.chunks;
        if (chunks == null || assembly.received != chunks.length
                || assembly.encodedTotal != work.encodedBytes) {
            throw new IOException("incomplete route assembly");
        }
        Enumeration<InputStream> streams = new Enumeration<InputStream>() {
            private int index;

            @Override public boolean hasMoreElements() {
                return index < chunks.length;
            }

            @Override public InputStream nextElement() {
                return new ByteArrayInputStream(chunks[index++]);
            }
        };
        try (DataInputStream in = new DataInputStream(new BoundedInputStream(
                new GZIPInputStream(new SequenceInputStream(streams)), MAX_DECODED_BYTES))) {
            if (in.readInt() != 0x42595231) throw new IOException("invalid BYR1 magic");
            int points = in.readInt();
            if (points < 0 || points > MAX_POINTS || points != work.pointCount
                    || (!assembly.metadata.geometryAvailable && points != 0)) {
                throw new IOException("invalid point count");
            }
            double[] coordinates = new double[points * 2];
            for (int i = 0; i < points; i++) {
                double latitude = in.readDouble();
                double longitude = in.readDouble();
                if (!validCoordinate(latitude, 90) || !validCoordinate(longitude, 180)) {
                    throw new IOException("invalid route coordinate");
                }
                coordinates[i * 2] = latitude;
                coordinates[i * 2 + 1] = longitude;
            }
            boolean maneuversAvailable = readBoolean(in);
            int maneuverCount = in.readInt();
            if (maneuversAvailable != assembly.metadata.maneuversAvailable
                    || maneuverCount < 0 || maneuverCount > MAX_MANEUVERS
                    || maneuverCount != work.maneuverCount
                    || (!maneuversAvailable && maneuverCount != 0)) {
                throw new IOException("invalid maneuver count or availability");
            }
            NavigatorRouteData.Maneuver[] maneuvers =
                    new NavigatorRouteData.Maneuver[maneuverCount];
            for (int i = 0; i < maneuverCount; i++) {
                String id = in.readUTF();
                int ordinal = in.readInt();
                double latitude = in.readDouble();
                double longitude = in.readDouble();
                if (!validCoordinate(latitude, 90) || !validCoordinate(longitude, 180)) {
                    throw new IOException("invalid maneuver coordinate");
                }
                int vertexIndex = in.readInt();
                int instructionIndex = in.readInt();
                int navigationIndex = in.readInt();
                String type = in.readUTF();
                String turnSide = in.readUTF();
                double incomingBearing = in.readDouble();
                double outgoingBearing = in.readDouble();
                int sourceRotation = in.readInt();
                boolean synthetic = readBoolean(in);
                maneuvers[i] = new NavigatorRouteData.Maneuver(id, ordinal, latitude,
                        longitude, vertexIndex, instructionIndex, navigationIndex, type,
                        turnSide, incomingBearing, outgoingBearing, sourceRotation, synthetic);
            }
            if (in.read() != -1) throw new IOException("trailing BYR1 data");
            return new NavigatorRouteData(work.header, assembly.metadata.geometryAvailable,
                    maneuversAvailable, coordinates, maneuvers);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("invalid route payload", e);
        }
    }

    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("invalid BYR1 boolean");
        return value == 1;
    }

    private static boolean validCoordinate(double value, double maximum) {
        return Double.isFinite(value) && Math.abs(value) <= maximum;
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream input;
        private final int limit;
        private int count;

        BoundedInputStream(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            if (count == limit) {
                if (input.read() < 0) return -1;
                throw new IOException("decoded route exceeds limit");
            }
            int value = input.read();
            if (value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || length > bytes.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (count == limit) return read();
            int read = input.read(bytes, offset, Math.min(length, limit - count));
            if (read > 0) count += read;
            return read;
        }

        @Override public void close() throws IOException {
            input.close();
        }
    }

    private static final class Metadata {
        final NavigatorRouteData.Header header;
        final int chunkCount;
        final int encodedBytes;
        final int pointCount;
        final int maneuverCount;
        final boolean geometryAvailable;
        final boolean maneuversAvailable;

        Metadata(Chunk chunk) {
            header = chunk.header;
            chunkCount = chunk.chunkCount;
            encodedBytes = chunk.encodedBytes;
            pointCount = chunk.pointCount;
            maneuverCount = chunk.maneuverCount;
            geometryAvailable = chunk.geometryAvailable;
            maneuversAvailable = chunk.maneuversAvailable;
        }

        boolean same(Metadata other) {
            return header.sameSnapshot(other.header) && chunkCount == other.chunkCount
                    && encodedBytes == other.encodedBytes && pointCount == other.pointCount
                    && maneuverCount == other.maneuverCount
                    && geometryAvailable == other.geometryAvailable
                    && maneuversAvailable == other.maneuversAvailable;
        }
    }

    private static final class Assembly {
        final NavigatorRouteData.Header header;
        final long startedAtMs;
        int replayCount;
        long lastNewChunkMs = -1;
        Metadata metadata;
        byte[][] chunks;
        int received;
        int encodedTotal;
        DecodeWork work;

        Assembly(NavigatorRouteData.Header header, long startedAtMs, int replayCount) {
            this.header = header;
            this.startedAtMs = startedAtMs;
            this.replayCount = replayCount;
        }
    }

    private static final class State {
        NavigatorRouteData.Header header;
        NavigatorRouteData route;
        CurrentStep step;
        Assembly assembly;
        Outcome outcome = Outcome.COLLECTING;
        String reason = "";
        boolean published;
        boolean sourceTerminal;
        int replayCount;
    }
}
