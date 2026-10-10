package com.bydhud.app;

import java.util.ArrayDeque;

/** Bounded comparison history, not a limit on the persisted receive journal. */
final class RoadInfoTxHistory {
    static final int MAX_ENTRIES = 256;
    static final long MAX_AGE_MS = 60_000L;
    private final ArrayDeque<Attempt> entries = new ArrayDeque<>();
    private long sequence;

    static final class Attempt {
        final long sequence, elapsedMs, wallMs;
        final RoadInfoDiagnosticFrame frame;
        volatile Integer result;
        volatile boolean completed;
        private Runnable receiveConfirmation;
        private boolean confirmed;

        Attempt(long sequence, long elapsedMs, long wallMs, RoadInfoDiagnosticFrame frame) {
            this.sequence = sequence;
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
            this.frame = frame;
        }

        void complete(Integer result) {
            Runnable notify;
            synchronized (this) {
                this.result = result;
                completed = true;
                notify = takeConfirmation();
            }
            if (notify != null) notify.run();
        }

        void observeReceive(Runnable onConfirmed) {
            Runnable notify;
            synchronized (this) {
                if (confirmed) return;
                receiveConfirmation = onConfirmed;
                notify = takeConfirmation();
            }
            if (notify != null) notify.run();
        }

        private Runnable takeConfirmation() {
            if (!completed || !Integer.valueOf(0).equals(result) || receiveConfirmation == null) return null;
            confirmed = true;
            Runnable notify = receiveConfirmation;
            receiveConfirmation = null;
            return notify;
        }
    }

    synchronized Attempt add(RoadInfoDiagnosticFrame frame, long elapsedMs, long wallMs) {
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES
                || elapsedMs - entries.peekFirst().elapsedMs > MAX_AGE_MS)) entries.removeFirst();
        Attempt attempt = new Attempt(++sequence, elapsedMs, wallMs, frame);
        entries.addLast(attempt);
        return attempt;
    }

    synchronized Attempt match(RoadInfoDiagnosticFrame frame, long receivedElapsedMs) {
        java.util.Iterator<Attempt> iterator = entries.descendingIterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next();
            long age = receivedElapsedMs - attempt.elapsedMs;
            if (age >= 0 && age <= MAX_AGE_MS && frame.matches(attempt.frame)) return attempt;
        }
        return null;
    }

    synchronized Attempt latest(long receivedElapsedMs) {
        java.util.Iterator<Attempt> iterator = entries.descendingIterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next();
            if (attempt.elapsedMs <= receivedElapsedMs) return attempt;
        }
        return null;
    }
}
