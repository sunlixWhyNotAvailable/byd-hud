package com.bydhud.app;

import java.util.Arrays;

/** Keeps the latest navigator map frame fenced to one owner session. */
final class NavigatorMapSessionState {
    static final long FRESHNESS_MS = 3500L;
    static final long FAST_INTERVAL_MS = 200L;
    static final long IDLE_INTERVAL_MS = 1000L;
    private static final long STABLE_CROP_MS = 2000L;

    enum FrameUpdate {
        REJECTED,
        SAME,
        CHANGED
    }

    private String ownerPackage = "";
    private long ownerGeneration = -1L;
    private String session = "";
    private String inputPixelHash = "";
    private String outputPixelHash = "";
    private HudMapProfile.Source source;
    private long profileRevision = -1L;
    private byte[] png;
    private long receivedAtElapsedMs;
    private long revision;
    private long frameSequence;
    private long stableSinceMs = -1L;
    private long lastCadenceReceiptMs = -1L;
    private long requestIntervalMs = FAST_INTERVAL_MS;

    long requestIntervalMs() { return requestIntervalMs; }

    void resetCadence() {
        stableSinceMs = -1L;
        lastCadenceReceiptMs = -1L;
        requestIntervalMs = FAST_INTERVAL_MS;
    }

    private void recordFreshCrop(boolean same, long receivedAt) {
        if (!same || stableSinceMs < 0L || receivedAt < lastCadenceReceiptMs
                || receivedAt - lastCadenceReceiptMs >= FRESHNESS_MS) {
            stableSinceMs = receivedAt;
            requestIntervalMs = FAST_INTERVAL_MS;
        } else if (receivedAt - stableSinceMs >= STABLE_CROP_MS) {
            requestIntervalMs = IDLE_INTERVAL_MS;
        }
        lastCadenceReceiptMs = receivedAt;
    }

    boolean activate(String nextOwnerPackage, long nextOwnerGeneration, String nextSession) {
        if (nextSession.equals(session)
                && nextOwnerGeneration == ownerGeneration
                && nextOwnerPackage.equals(ownerPackage)) {
            return false;
        }
        ownerPackage = nextOwnerPackage;
        ownerGeneration = nextOwnerGeneration;
        session = nextSession;
        clearFrame();
        revision++;
        return true;
    }

    boolean stop() {
        if (session.isEmpty()) {
            return false;
        }
        ownerPackage = "";
        ownerGeneration = -1L;
        session = "";
        clearFrame();
        revision++;
        return true;
    }

    boolean isCurrent(String owner, long generation, String expectedSession) {
        return !session.isEmpty()
                && ownerPackage.equals(owner)
                && ownerGeneration == generation
                && session.equals(expectedSession);
    }

    boolean hasSameInputPixels(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash) {
        return hasSameInputPixels(owner, generation, expectedSession, nextInputPixelHash,
                null, -1L);
    }

    boolean hasSameInputPixels(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash,
            HudMapProfile.Source nextSource,
            long nextProfileRevision) {
        return isCurrent(owner, generation, expectedSession)
                && png != null
                && inputPixelHash.equals(nextInputPixelHash)
                && source == nextSource
                && profileRevision == nextProfileRevision;
    }

    FrameUpdate refreshSameInput(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash,
            long receivedAt,
            long now) {
        return refreshSameInput(owner, generation, expectedSession, nextInputPixelHash,
                null, -1L, receivedAt, now);
    }

    FrameUpdate refreshSameInput(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash,
            HudMapProfile.Source nextSource,
            long nextProfileRevision,
            long receivedAt,
            long now) {
        if (!isFreshReceipt(receivedAt, now)
                || !hasSameInputPixels(owner, generation, expectedSession, nextInputPixelHash,
                        nextSource, nextProfileRevision)) {
            return FrameUpdate.REJECTED;
        }
        receivedAtElapsedMs = receivedAt;
        recordFreshCrop(true, receivedAt);
        frameSequence++;
        return FrameUpdate.SAME;
    }

    FrameUpdate publish(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash,
            String nextOutputPixelHash,
            byte[] nextPng,
            long receivedAt,
            long now) {
        return publish(owner, generation, expectedSession, nextInputPixelHash,
                nextOutputPixelHash, nextPng, null, -1L, receivedAt, now);
    }

    FrameUpdate publish(
            String owner,
            long generation,
            String expectedSession,
            String nextInputPixelHash,
            String nextOutputPixelHash,
            byte[] nextPng,
            HudMapProfile.Source nextSource,
            long nextProfileRevision,
            long receivedAt,
            long now) {
        if (!isFreshReceipt(receivedAt, now)
                || !isCurrent(owner, generation, expectedSession)
                || nextPng == null || nextPng.length == 0) {
            return FrameUpdate.REJECTED;
        }
        inputPixelHash = nextInputPixelHash;
        receivedAtElapsedMs = receivedAt;
        frameSequence++;
        boolean sameOutput = png != null
                && outputPixelHash.equals(nextOutputPixelHash)
                && source == nextSource
                && profileRevision == nextProfileRevision;
        source = nextSource;
        profileRevision = nextProfileRevision;
        recordFreshCrop(sameOutput, receivedAt);
        if (sameOutput) {
            return FrameUpdate.SAME;
        }
        png = Arrays.copyOf(nextPng, nextPng.length);
        outputPixelHash = nextOutputPixelHash;
        revision++;
        return FrameUpdate.CHANGED;
    }

    boolean expireIfDue(String expectedSession, long expectedFrameSequence, long now) {
        if (!session.equals(expectedSession)
                || png == null
                || frameSequence != expectedFrameSequence
                || now - receivedAtElapsedMs < FRESHNESS_MS) {
            return false;
        }
        clearFrame();
        revision++;
        return true;
    }

    boolean expireIfStale(long now) {
        if (png == null || now - receivedAtElapsedMs < FRESHNESS_MS) {
            return false;
        }
        clearFrame();
        revision++;
        return true;
    }

    byte[] pngCopy() {
        return png == null ? null : Arrays.copyOf(png, png.length);
    }

    byte[] pngForSnapshot() {
        return png;
    }

    boolean hasFrame() {
        return png != null;
    }

    long revision() {
        return revision;
    }

    long receivedAtElapsedMs() {
        return png == null ? 0L : receivedAtElapsedMs;
    }

    long frameSequence() {
        return frameSequence;
    }

    HudMapProfile.Source source() {
        return png == null ? null : source;
    }

    long profileRevision() {
        return png == null ? -1L : profileRevision;
    }

    private static boolean isFreshReceipt(long receivedAt, long now) {
        return receivedAt >= 0L && now >= receivedAt && now - receivedAt < FRESHNESS_MS;
    }

    private void clearFrame() {
        resetCadence();
        png = null;
        inputPixelHash = "";
        outputPixelHash = "";
        source = null;
        profileRevision = -1L;
        receivedAtElapsedMs = 0L;
        frameSequence++;
    }
}
