package com.bydhud.app;

/** Bounded, clock-domain-aware timing snapshot for the GMaps Messenger bridge. */
final class GMapsTimingDiagnostics {
    static final long SLOW_PATH_THRESHOLD_MS = 250L;

    private GMapsTimingDiagnostics() {
    }

    static Frame frame(
            int protocolVersion,
            String channelId,
            int messageWhat,
            long sessionGeneration,
            long messageSession,
            long sequence,
            long bridgeElapsedMs,
            long sourceElapsedMs,
            long handlerEntryElapsedMs) {
        return new Frame(protocolVersion, channelId, messageWhat, sessionGeneration,
                messageSession, sequence, bridgeElapsedMs, sourceElapsedMs,
                handlerEntryElapsedMs, -1L, false, -1L, -1L, -1L, false);
    }

    static String registrationLine(
            String action,
            String channelId,
            long sessionGeneration,
            long beforeElapsedMs,
            long afterElapsedMs,
            boolean sent) {
        return "registration action=" + safe(action)
                + " channelId=" + safe(channelId)
                + " session=" + sessionGeneration
                + " beforeElapsedMs=" + beforeElapsedMs
                + " afterElapsedMs=" + afterElapsedMs
                + " durationMs=" + duration(beforeElapsedMs, afterElapsedMs)
                + " sent=" + sent;
    }

    static long duration(long startElapsedMs, long endElapsedMs) {
        if (startElapsedMs < 0L || endElapsedMs < startElapsedMs) return -1L;
        return endElapsedMs - startElapsedMs;
    }

    static final class Frame {
        final int protocolVersion;
        final String channelId;
        final int messageWhat;
        final long sessionGeneration;
        final long messageSession;
        final long sequence;
        final long bridgeElapsedMs;
        final long sourceElapsedMs;
        final long handlerEntryElapsedMs;
        final long protocolValidatedElapsedMs;
        final boolean protocolAccepted;
        final long parseStartElapsedMs;
        final long parseEndElapsedMs;
        final long listenerHandoffElapsedMs;
        final boolean firstFrame;

        private Frame(
                int protocolVersion,
                String channelId,
                int messageWhat,
                long sessionGeneration,
                long messageSession,
                long sequence,
                long bridgeElapsedMs,
                long sourceElapsedMs,
                long handlerEntryElapsedMs,
                long protocolValidatedElapsedMs,
                boolean protocolAccepted,
                long parseStartElapsedMs,
                long parseEndElapsedMs,
                long listenerHandoffElapsedMs,
                boolean firstFrame) {
            this.protocolVersion = protocolVersion;
            this.channelId = bounded(channelId);
            this.messageWhat = messageWhat;
            this.sessionGeneration = sessionGeneration;
            this.messageSession = messageSession;
            this.sequence = sequence;
            this.bridgeElapsedMs = bridgeElapsedMs;
            this.sourceElapsedMs = sourceElapsedMs;
            this.handlerEntryElapsedMs = handlerEntryElapsedMs;
            this.protocolValidatedElapsedMs = protocolValidatedElapsedMs;
            this.protocolAccepted = protocolAccepted;
            this.parseStartElapsedMs = parseStartElapsedMs;
            this.parseEndElapsedMs = parseEndElapsedMs;
            this.listenerHandoffElapsedMs = listenerHandoffElapsedMs;
            this.firstFrame = firstFrame;
        }

        Frame withProtocolValidated(long elapsedMs, boolean accepted) {
            return copy(elapsedMs, accepted, parseStartElapsedMs, parseEndElapsedMs,
                    listenerHandoffElapsedMs, firstFrame);
        }

        Frame withParse(long startElapsedMs, long endElapsedMs) {
            return copy(protocolValidatedElapsedMs, protocolAccepted,
                    startElapsedMs, endElapsedMs, listenerHandoffElapsedMs, firstFrame);
        }

        Frame withListenerHandoff(long elapsedMs, boolean first) {
            return copy(protocolValidatedElapsedMs, protocolAccepted,
                    parseStartElapsedMs, parseEndElapsedMs, elapsedMs, first);
        }

        boolean shouldLog(boolean detailed, long callbackEntryElapsedMs,
                long tbtDispatchElapsedMs, long hudDispatchElapsedMs) {
            if (detailed || firstFrame) return true;
            return isSlow(duration(handlerEntryElapsedMs, listenerHandoffElapsedMs))
                    || isSlow(duration(parseStartElapsedMs, parseEndElapsedMs))
                    || isSlow(duration(listenerHandoffElapsedMs, callbackEntryElapsedMs))
                    || isSlow(duration(callbackEntryElapsedMs, tbtDispatchElapsedMs))
                    || isSlow(duration(callbackEntryElapsedMs, hudDispatchElapsedMs))
                    || isSlow(fromSourceTo(sourceElapsedMs, tbtDispatchElapsedMs))
                    || isSlow(fromSourceTo(sourceElapsedMs, hudDispatchElapsedMs));
        }

        boolean shouldLogAtHandoff(boolean detailed) {
            return detailed || firstFrame
                    || isSlow(duration(handlerEntryElapsedMs, listenerHandoffElapsedMs))
                    || isSlow(duration(parseStartElapsedMs, parseEndElapsedMs));
        }

        String dispatchLine(long callbackEntryElapsedMs,
                long tbtDispatchElapsedMs, long hudDispatchElapsedMs,
                boolean tbtDispatched, boolean hudDispatched) {
            return "stage=dispatch"
                    + " protocol=" + protocolVersion
                    + " channelId=" + channelId
                    + " message=" + messageWhat
                    + " session=" + sessionGeneration
                    + " messageSession=" + messageSession
                    + " sequence=" + sequence
                    + " bridgeElapsedMs=" + bridgeElapsedMs
                    + " sourceElapsedMs=" + sourceElapsedMs
                    + " handlerEntryElapsedMs=" + handlerEntryElapsedMs
                    + " protocolValidatedElapsedMs=" + protocolValidatedElapsedMs
                    + " parseStartElapsedMs=" + parseStartElapsedMs
                    + " parseEndElapsedMs=" + parseEndElapsedMs
                    + " listenerHandoffElapsedMs=" + listenerHandoffElapsedMs
                    + " callbackEntryElapsedMs=" + callbackEntryElapsedMs
                    + " tbtDispatchElapsedMs=" + tbtDispatchElapsedMs
                    + " hudDispatchElapsedMs=" + hudDispatchElapsedMs
                    + " handlerToListenerMs="
                    + duration(handlerEntryElapsedMs, listenerHandoffElapsedMs)
                    + " listenerToCallbackMs="
                    + duration(listenerHandoffElapsedMs, callbackEntryElapsedMs)
                    + " callbackToTbtMs="
                    + duration(callbackEntryElapsedMs, tbtDispatchElapsedMs)
                    + " callbackToHudMs="
                    + duration(callbackEntryElapsedMs, hudDispatchElapsedMs)
                    + " firstFrame=" + firstFrame
                    + " tbtDispatched=" + tbtDispatched
                    + " hudDispatched=" + hudDispatched;
        }

        String protocolLine(boolean accepted) {
            return "stage=protocol_validation"
                    + " protocol=" + protocolVersion
                    + " channelId=" + channelId
                    + " message=" + messageWhat
                    + " session=" + sessionGeneration
                    + " messageSession=" + messageSession
                    + " sequence=" + sequence
                    + " bridgeElapsedMs=" + bridgeElapsedMs
                    + " sourceElapsedMs=" + sourceElapsedMs
                    + " handlerEntryElapsedMs=" + handlerEntryElapsedMs
                    + " protocolValidatedElapsedMs=" + protocolValidatedElapsedMs
                    + " accepted=" + accepted;
        }

        private Frame copy(long validatedElapsedMs, boolean accepted,
                long parseStartMs, long parseEndMs, long listenerHandoffMs,
                boolean first) {
            return new Frame(protocolVersion, channelId, messageWhat, sessionGeneration,
                    messageSession, sequence, bridgeElapsedMs, sourceElapsedMs,
                    handlerEntryElapsedMs, validatedElapsedMs, accepted,
                    parseStartMs, parseEndMs, listenerHandoffMs, first);
        }
    }

    private static boolean isSlow(long durationMs) {
        return durationMs >= SLOW_PATH_THRESHOLD_MS;
    }

    private static long fromSourceTo(long sourceElapsedMs, long endElapsedMs) {
        return sourceElapsedMs < 0L ? -1L : duration(sourceElapsedMs, endElapsedMs);
    }

    private static String bounded(String value) {
        String safe = safe(value);
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', '_').replace('\r', '_');
    }
}
