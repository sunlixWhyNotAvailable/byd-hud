package com.bydhud.app;

import java.util.ArrayDeque;

/** Bounded Waze lifecycle-to-direct timing correlation for one cold route. */
final class WazeRouteTiming {
    static final long UNSET = -1L;
    static final long SLOW_PATH_THRESHOLD_MS = 250L;

    private static final long DIRECT_MATCH_WINDOW_MS = 30_000L;
    private static final int MAX_PENDING = 8;
    private static final Object PENDING_LOCK = new Object();
    private static final ArrayDeque<WazeRouteTiming> PENDING = new ArrayDeque<>();

    private final String channel;
    private final String eventType;
    private final long producerElapsedMs;
    private final long bridgeGeneration;
    private final long receiverEntryElapsedMs;
    private final boolean directCorrelationEligible;

    private long preEnqueueElapsedMs = UNSET;
    private long executorStartElapsedMs = UNSET;
    private long trustStartElapsedMs = UNSET;
    private long trustEndElapsedMs = UNSET;
    private long deliveryStartElapsedMs = UNSET;
    private long deliveryEndElapsedMs = UNSET;
    private int queueDepthAtEnqueue = -1;
    private int queueDepthAtExecutorStart = -1;

    private boolean acceptedForDirect;
    private boolean directClaimed;
    private long directStartElapsedMs = UNSET;
    private long bindRequestElapsedMs = UNSET;
    private long bindStartElapsedMs = UNSET;
    private long bindResultElapsedMs = UNSET;
    private long firstSuccessfulBindElapsedMs = UNSET;
    private int bindAttemptCount;
    private boolean bindResultKnown;
    private boolean bindResult;
    private long carAppConnectedElapsedMs = UNSET;
    private long sessionReadyElapsedMs = UNSET;
    private long firstNavigationFrameElapsedMs = UNSET;
    private long firstListenerHandoffElapsedMs = UNSET;
    private long firstListenerCallbackElapsedMs = UNSET;
    private long firstTbtDispatchElapsedMs = UNSET;
    private long firstHudDispatchElapsedMs = UNSET;
    private int directSessionGeneration = -1;
    private String directStartReason = "unknown";

    WazeRouteTiming(String channel, String eventType, long producerElapsedMs,
            long bridgeGeneration, long receiverEntryElapsedMs,
            boolean directCorrelationEligible) {
        this.channel = token(channel);
        this.eventType = token(eventType);
        this.producerElapsedMs = producerElapsedMs;
        this.bridgeGeneration = bridgeGeneration;
        this.receiverEntryElapsedMs = receiverEntryElapsedMs;
        this.directCorrelationEligible = directCorrelationEligible;
    }

    void markAcceptedRouteState(boolean active) {
        synchronized (PENDING_LOCK) {
            if (!active) {
                acceptedForDirect = false;
                PENDING.clear();
                return;
            }
            if (acceptedForDirect || !directCorrelationEligible) return;
            acceptedForDirect = true;
            PENDING.addLast(this);
            while (PENDING.size() > MAX_PENDING) PENDING.removeFirst();
        }
    }

    void cancelDirectCorrelation() {
        synchronized (PENDING_LOCK) {
            acceptedForDirect = false;
            PENDING.remove(this);
        }
    }

    void markPreEnqueue(long elapsedMs, int queueDepth) {
        synchronized (this) {
            preEnqueueElapsedMs = elapsedMs;
            queueDepthAtEnqueue = Math.max(0, queueDepth);
        }
    }

    void markExecutorStart(long elapsedMs, int queueDepth) {
        synchronized (this) {
            executorStartElapsedMs = elapsedMs;
            queueDepthAtExecutorStart = Math.max(0, queueDepth);
        }
    }

    void markTrustStart(long elapsedMs) {
        synchronized (this) {
            trustStartElapsedMs = elapsedMs;
        }
    }

    void markTrustEnd(long elapsedMs) {
        synchronized (this) {
            trustEndElapsedMs = elapsedMs;
        }
    }

    void markDeliveryStart(long elapsedMs) {
        synchronized (this) {
            deliveryStartElapsedMs = elapsedMs;
        }
    }

    void markDeliveryEnd(long elapsedMs) {
        synchronized (this) {
            deliveryEndElapsedMs = elapsedMs;
        }
    }

    static WazeRouteTiming claimForDirect(long nowElapsedMs) {
        synchronized (PENDING_LOCK) {
            prunePending(nowElapsedMs);
            WazeRouteTiming match = null;
            for (WazeRouteTiming candidate : PENDING) {
                if (!candidate.acceptedForDirect || candidate.directClaimed
                        || candidate.producerElapsedMs <= 0L
                        || candidate.producerElapsedMs > nowElapsedMs) {
                    continue;
                }
                if (match == null || candidate.producerElapsedMs > match.producerElapsedMs) {
                    match = candidate;
                }
            }
            if (match == null) return null;
            match.directClaimed = true;
            long matchedProducerElapsedMs = match.producerElapsedMs;
            PENDING.removeIf(candidate ->
                    candidate.producerElapsedMs <= matchedProducerElapsedMs);
            return match;
        }
    }

    static void clearPendingForTest() {
        synchronized (PENDING_LOCK) {
            PENDING.clear();
        }
    }

    synchronized void markDirectStart(long elapsedMs, int sessionGeneration, String reason) {
        directStartElapsedMs = first(directStartElapsedMs, elapsedMs);
        directSessionGeneration = sessionGeneration;
        directStartReason = token(reason);
    }

    synchronized void markBindRequest(long elapsedMs) {
        bindAttemptCount++;
        bindRequestElapsedMs = elapsedMs;
        bindStartElapsedMs = UNSET;
        bindResultElapsedMs = UNSET;
        bindResultKnown = false;
    }

    synchronized void markBindStart(long elapsedMs) {
        bindStartElapsedMs = elapsedMs;
    }

    synchronized void markBindResult(long elapsedMs, boolean result) {
        bindResultElapsedMs = elapsedMs;
        bindResultKnown = true;
        bindResult = result;
        if (result) {
            firstSuccessfulBindElapsedMs = first(firstSuccessfulBindElapsedMs, elapsedMs);
        }
    }

    synchronized void markCarAppConnected(long elapsedMs) {
        carAppConnectedElapsedMs = first(carAppConnectedElapsedMs, elapsedMs);
    }

    synchronized void markSessionReady(long elapsedMs) {
        sessionReadyElapsedMs = first(sessionReadyElapsedMs, elapsedMs);
    }

    synchronized Frame beginFrame(long elapsedMs, String reason) {
        boolean firstFrame = firstNavigationFrameElapsedMs == UNSET;
        firstNavigationFrameElapsedMs = first(firstNavigationFrameElapsedMs, elapsedMs);
        return new Frame(this, elapsedMs, token(reason), firstFrame);
    }

    synchronized boolean markFirstTbtDispatch(long elapsedMs) {
        boolean firstDispatch = firstTbtDispatchElapsedMs == UNSET;
        firstTbtDispatchElapsedMs = first(firstTbtDispatchElapsedMs, elapsedMs);
        return firstDispatch;
    }

    synchronized boolean markFirstHudDispatch(long elapsedMs) {
        boolean firstDispatch = firstHudDispatchElapsedMs == UNSET;
        firstHudDispatchElapsedMs = first(firstHudDispatchElapsedMs, elapsedMs);
        return firstDispatch;
    }

    synchronized String finish(String outcome) {
        return baseLine("receiver")
                + " preEnqueueElapsedMs=" + preEnqueueElapsedMs
                + " executorStartElapsedMs=" + executorStartElapsedMs
                + " trustStartElapsedMs=" + trustStartElapsedMs
                + " trustEndElapsedMs=" + trustEndElapsedMs
                + " deliveryStartElapsedMs=" + deliveryStartElapsedMs
                + " deliveryEndElapsedMs=" + deliveryEndElapsedMs
                + " queueDepthAtEnqueue=" + queueDepthAtEnqueue
                + " queueDepthAtExecutorStart=" + queueDepthAtExecutorStart
                + " producerToReceiverMs=" + delta(producerElapsedMs, receiverEntryElapsedMs)
                + " receiverToEnqueueMs=" + delta(receiverEntryElapsedMs, preEnqueueElapsedMs)
                + " enqueueToExecutorMs=" + delta(preEnqueueElapsedMs, executorStartElapsedMs)
                + " trustDurationMs=" + delta(trustStartElapsedMs, trustEndElapsedMs)
                + " deliveryDurationMs=" + delta(deliveryStartElapsedMs, deliveryEndElapsedMs)
                + " outcome=" + token(outcome);
    }

    synchronized String directLine(String stage) {
        return baseLine(token(stage))
                + " preEnqueueElapsedMs=" + preEnqueueElapsedMs
                + " executorStartElapsedMs=" + executorStartElapsedMs
                + " trustStartElapsedMs=" + trustStartElapsedMs
                + " trustEndElapsedMs=" + trustEndElapsedMs
                + " deliveryStartElapsedMs=" + deliveryStartElapsedMs
                + " deliveryEndElapsedMs=" + deliveryEndElapsedMs
                + " queueDepthAtEnqueue=" + queueDepthAtEnqueue
                + " queueDepthAtExecutorStart=" + queueDepthAtExecutorStart
                + " directStartElapsedMs=" + directStartElapsedMs
                + " directStartReason=" + directStartReason
                + " directSessionGeneration=" + directSessionGeneration
                + " bindRequestElapsedMs=" + bindRequestElapsedMs
                + " bindStartElapsedMs=" + bindStartElapsedMs
                + " bindResultElapsedMs=" + bindResultElapsedMs
                + " bindAttemptCount=" + bindAttemptCount
                + " firstSuccessfulBindElapsedMs=" + firstSuccessfulBindElapsedMs
                + " bindResultKnown=" + bindResultKnown
                + " bindResult=" + bindResult
                + " carAppConnectedElapsedMs=" + carAppConnectedElapsedMs
                + " sessionReadyElapsedMs=" + sessionReadyElapsedMs
                + " firstNavigationFrameElapsedMs=" + firstNavigationFrameElapsedMs
                + " firstListenerHandoffElapsedMs=" + firstListenerHandoffElapsedMs
                + " firstListenerCallbackElapsedMs=" + firstListenerCallbackElapsedMs
                + " firstTbtDispatchElapsedMs=" + firstTbtDispatchElapsedMs
                + " firstHudDispatchElapsedMs=" + firstHudDispatchElapsedMs
                + " producerToDirectStartMs=" + delta(producerElapsedMs, directStartElapsedMs)
                + " receiverToBindRequestMs=" + delta(receiverEntryElapsedMs, bindRequestElapsedMs)
                + " bindStartToResultMs=" + delta(bindStartElapsedMs, bindResultElapsedMs)
                + " producerToReceiverMs=" + delta(producerElapsedMs, receiverEntryElapsedMs)
                + " receiverToEnqueueMs=" + delta(receiverEntryElapsedMs, preEnqueueElapsedMs)
                + " enqueueToExecutorMs=" + delta(preEnqueueElapsedMs, executorStartElapsedMs)
                + " trustDurationMs=" + delta(trustStartElapsedMs, trustEndElapsedMs)
                + " deliveryDurationMs=" + delta(deliveryStartElapsedMs, deliveryEndElapsedMs)
                + " connectedToSessionReadyMs=" + delta(
                carAppConnectedElapsedMs, sessionReadyElapsedMs)
                + " sessionReadyToFirstFrameMs=" + delta(
                sessionReadyElapsedMs, firstNavigationFrameElapsedMs)
                + " firstFrameToListenerHandoffMs=" + delta(
                firstNavigationFrameElapsedMs, firstListenerHandoffElapsedMs)
                + " listenerToTbtMs=" + delta(
                firstListenerCallbackElapsedMs, firstTbtDispatchElapsedMs)
                + " listenerToHudMs=" + delta(
                firstListenerCallbackElapsedMs, firstHudDispatchElapsedMs);
    }

    private synchronized String baseLine(String stage) {
        return "waze_timing"
                + " stage=" + stage
                + " channel=" + channel
                + " eventType=" + eventType
                + " eventElapsedMs=" + producerElapsedMs
                + " receiverEntryElapsedMs=" + receiverEntryElapsedMs
                + " bridgeGeneration=" + bridgeGeneration;
    }

    private static void prunePending(long nowElapsedMs) {
        while (!PENDING.isEmpty()) {
            WazeRouteTiming timing = PENDING.peekFirst();
            if (timing.directClaimed || nowElapsedMs - timing.producerElapsedMs
                    > DIRECT_MATCH_WINDOW_MS) {
                PENDING.removeFirst();
            } else {
                break;
            }
        }
    }

    private static long first(long current, long value) {
        return current == UNSET ? value : current;
    }

    private static long delta(long start, long end) {
        if (start < 0L || end < start) return UNSET;
        return end - start;
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        int length = Math.min(value.length(), 64);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.') {
                result.append(ch);
            } else {
                result.append('-');
            }
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }

    static final class Frame {
        private final WazeRouteTiming parent;
        private final long frameElapsedMs;
        private final String reason;
        private final boolean firstFrame;
        private long listenerHandoffElapsedMs = UNSET;
        private long listenerCallbackElapsedMs = UNSET;

        private Frame(WazeRouteTiming parent, long frameElapsedMs,
                String reason, boolean firstFrame) {
            this.parent = parent;
            this.frameElapsedMs = frameElapsedMs;
            this.reason = reason;
            this.firstFrame = firstFrame;
        }

        synchronized void markListenerHandoff(long elapsedMs) {
            listenerHandoffElapsedMs = first(listenerHandoffElapsedMs, elapsedMs);
            synchronized (parent) {
                parent.firstListenerHandoffElapsedMs = first(
                        parent.firstListenerHandoffElapsedMs, elapsedMs);
            }
        }

        synchronized void markListenerCallback(long elapsedMs) {
            listenerCallbackElapsedMs = first(listenerCallbackElapsedMs, elapsedMs);
            synchronized (parent) {
                parent.firstListenerCallbackElapsedMs = first(
                        parent.firstListenerCallbackElapsedMs, elapsedMs);
            }
        }

        synchronized boolean shouldLog(boolean detailed,
                long tbtDispatchElapsedMs, long hudDispatchElapsedMs) {
            return detailed || firstFrame
                    || slow(frameElapsedMs, listenerHandoffElapsedMs)
                    || slow(listenerHandoffElapsedMs, listenerCallbackElapsedMs)
                    || slow(listenerCallbackElapsedMs, tbtDispatchElapsedMs)
                    || slow(listenerCallbackElapsedMs, hudDispatchElapsedMs);
        }

        boolean markFirstTbtDispatch(long elapsedMs) {
            return parent.markFirstTbtDispatch(elapsedMs);
        }

        boolean markFirstHudDispatch(long elapsedMs) {
            return parent.markFirstHudDispatch(elapsedMs);
        }

        String directLine(String stage) {
            return parent.directLine(stage);
        }

        synchronized String line(long tbtDispatchElapsedMs,
                long hudDispatchElapsedMs, boolean tbtDispatched,
                boolean hudDispatched) {
            return parent.directLine("frame")
                    + " frameReason=" + reason
                    + " frameElapsedMs=" + frameElapsedMs
                    + " listenerHandoffElapsedMs=" + listenerHandoffElapsedMs
                    + " listenerCallbackElapsedMs=" + listenerCallbackElapsedMs
                    + " tbtDispatchElapsedMs=" + tbtDispatchElapsedMs
                    + " hudDispatchElapsedMs=" + hudDispatchElapsedMs
                    + " frameToListenerMs=" + delta(frameElapsedMs, listenerHandoffElapsedMs)
                    + " listenerToCallbackMs=" + delta(
                    listenerHandoffElapsedMs, listenerCallbackElapsedMs)
                    + " callbackToTbtMs=" + delta(
                    listenerCallbackElapsedMs, tbtDispatchElapsedMs)
                    + " callbackToHudMs=" + delta(
                    listenerCallbackElapsedMs, hudDispatchElapsedMs)
                    + " firstFrame=" + firstFrame
                    + " tbtDispatched=" + tbtDispatched
                    + " hudDispatched=" + hudDispatched;
        }

        boolean isFirstFrame() {
            return firstFrame;
        }

        private static boolean slow(long start, long end) {
            return delta(start, end) >= SLOW_PATH_THRESHOLD_MS;
        }
    }
}
