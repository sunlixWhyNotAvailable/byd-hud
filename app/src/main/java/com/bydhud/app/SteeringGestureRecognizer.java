package com.bydhud.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Key sequences and deadlines, independent of Android so real event traces are testable. */
final class SteeringGestureRecognizer {
    private final long holdTimeout;
    private final long doubleTimeout;
    private final long tailTimeout;
    private final Map<Integer, Press> presses = new HashMap<>();
    private final Map<Integer, Long> pendingUps = new HashMap<>();
    private List<SteeringTransferProfile> profiles = Collections.emptyList();
    private long revision = -1;

    SteeringGestureRecognizer(long holdTimeout, long doubleTimeout, long tailTimeout) {
        this.holdTimeout = holdTimeout;
        this.doubleTimeout = doubleTimeout;
        this.tailTimeout = tailTimeout;
    }

    long revision() { return revision; }

    void configure(List<SteeringTransferProfile> values, long newRevision) {
        if (revision == newRevision) return;
        cancel();
        profiles = values;
        revision = newRevision;
    }

    // Preserve consumed DOWN tails across Save/Delete, learning and shutdown.
    void cancel() {
        pendingUps.clear();
        for (Press press : presses.values()) press.cancelled = true;
    }

    boolean onKey(int rawKey, int action, int repeats, boolean cancelled,
            long eventTime, long now, boolean blocked, Consumer<SteeringTransferProfile> emit) {
        int key = SteeringTransferPolicy.canonicalKeyCode(rawKey);
        Press press = presses.get(key);
        if (press != null && now >= press.lastSeen + tailTimeout) {
            presses.remove(key);
            press = null;
        }
        boolean assigned = false;
        for (SteeringTransferProfile profile : profiles) {
            if (profile.keyCode == key) { assigned = true; break; }
        }
        if (!assigned && press == null) return false;
        // OEM semantic aliases are consumed as part of the assigned family only.
        // They neither start a gesture nor release/cancel the ordinary key's press.
        if (SteeringTransferPolicy.isNativeLongAlias(rawKey)) return true;
        if (cancelled) {
            pendingUps.remove(key);
            if (action == SteeringTransferPolicy.ACTION_UP) presses.remove(key);
            else if (press != null) press.cancelled = true;
            return true;
        }
        if (action == SteeringTransferPolicy.ACTION_DOWN) {
            if (press != null) press.lastSeen = now;
            if (repeats != 0) return true;
            if (press == null) {
                Long firstUp = pendingUps.remove(key);
                boolean second = firstUp != null && eventTime >= firstUp
                        && eventTime - firstUp <= doubleTimeout;
                if (firstUp != null && !second && !blocked) {
                    emit(key, SteeringTransferPreferences.PRESS_SINGLE, emit);
                }
                press = new Press(eventTime, now, second, blocked);
                presses.put(key, press);
            }
        } else if (action == SteeringTransferPolicy.ACTION_UP && press != null) {
            presses.remove(key);
            if (press.cancelled || blocked || press.held) return true;
            if (eventTime - press.downAt >= holdTimeout) {
                emit(key, SteeringTransferPreferences.PRESS_HOLD, emit);
            } else if (press.second) {
                emit(key, SteeringTransferPreferences.PRESS_DOUBLE, emit);
            } else {
                // Classify the gesture before looking up this app's matching action.
                pendingUps.put(key, eventTime);
            }
        }
        return true;
    }

    void advance(long now, Consumer<SteeringTransferProfile> emit) {
        Iterator<Map.Entry<Integer, Press>> active = presses.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<Integer, Press> entry = active.next();
            Press press = entry.getValue();
            if (now >= press.lastSeen + tailTimeout) { active.remove(); continue; }
            if (!press.cancelled && !press.held
                    && now >= press.downAt + holdTimeout) {
                press.held = true;
                emit(entry.getKey(), SteeringTransferPreferences.PRESS_HOLD, emit);
            }
        }
        Iterator<Map.Entry<Integer, Long>> pending = pendingUps.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, Long> entry = pending.next();
            if (now > entry.getValue() + doubleTimeout) {
                pending.remove();
                emit(entry.getKey(), SteeringTransferPreferences.PRESS_SINGLE, emit);
            }
        }
    }

    long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (Map.Entry<Integer, Press> entry : presses.entrySet()) {
            Press press = entry.getValue();
            next = Math.min(next, press.lastSeen + tailTimeout);
            if (!press.cancelled && !press.held) {
                next = Math.min(next, press.downAt + holdTimeout);
            }
        }
        for (long up : pendingUps.values()) next = Math.min(next, up + doubleTimeout + 1);
        return next;
    }

    private void emit(int key, String mode, Consumer<SteeringTransferProfile> emit) {
        SteeringTransferProfile profile = SteeringTransferPreferences.find(profiles, key, mode);
        if (profile != null) emit.accept(profile);
    }

    private static final class Press {
        final long downAt;
        final boolean second;
        long lastSeen;
        boolean held;
        boolean cancelled;

        Press(long downAt, long now, boolean second, boolean cancelled) {
            this.downAt = downAt;
            this.lastSeen = now;
            this.second = second;
            this.cancelled = cancelled;
        }
    }
}
