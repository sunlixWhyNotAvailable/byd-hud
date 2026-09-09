package com.bydhud.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Key sequences and deadlines, independent of Android so real event traces are testable. */
final class SteeringGestureRecognizer {
    static final String ORIGIN_NATIVE = "native";
    static final String ORIGIN_TIMER = "timer";
    static final String ORIGIN_RELEASE = "release";
    static final String ORIGIN_DOUBLE = "double";

    interface MatchConsumer {
        void accept(SteeringTransferProfile profile, String origin);
    }

    private final long holdTimeout;
    private final long doubleTimeout;
    private final long tailTimeout;
    private final Map<Integer, Press> presses = new HashMap<>();
    private final Map<Integer, Long> pendingUps = new HashMap<>();
    private final Map<Integer, Long> lateNativeTails = new HashMap<>();
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
        return onKey(rawKey, action, repeats, cancelled, eventTime, now, blocked,
                (profile, origin) -> emit.accept(profile));
    }

    boolean onKey(int rawKey, int action, int repeats, boolean cancelled,
            long eventTime, long now, boolean blocked, MatchConsumer emit) {
        int key = SteeringTransferPolicy.canonicalKeyCode(rawKey);
        Press press = presses.get(key);
        if (press != null && !press.expiredTimerHold
                && now >= press.lastSeen + tailTimeout) {
            if (press.timerHeld && press.ordinaryDown) {
                press.expiredTimerHold = true;
            } else {
                presses.remove(key);
                press = null;
            }
        }
        Long lateNativeTail = lateNativeTails.get(key);
        if (lateNativeTail != null && eventTime > lateNativeTail) {
            lateNativeTails.remove(key);
            lateNativeTail = null;
        }
        boolean assigned = false;
        for (SteeringTransferProfile profile : profiles) {
            if (profile.keyCode == key) { assigned = true; break; }
        }
        boolean nativeAlias = SteeringTransferPolicy.isNativeLongAlias(rawKey);
        if (!assigned && press == null && !(nativeAlias && lateNativeTail != null)) return false;
        if (nativeAlias) {
            return onNativeLong(key, action, repeats, cancelled, eventTime, now, blocked, emit);
        }
        if (cancelled) {
            pendingUps.remove(key);
            if (action == SteeringTransferPolicy.ACTION_UP && press != null) {
                press.ordinaryDown = false;
                if (!press.nativeDown) presses.remove(key);
            }
            else if (press != null) press.cancelled = true;
            return true;
        }
        if (action == SteeringTransferPolicy.ACTION_DOWN) {
            if (repeats != 0) return true;
            if (press != null && press.expiredTimerHold) {
                presses.remove(key);
                press = null;
                if (!assigned) return false;
            }
            if (press != null) press.lastSeen = now;
            if (press == null) {
                lateNativeTails.remove(key);
                Long firstUp = pendingUps.remove(key);
                boolean second = firstUp != null && eventTime >= firstUp
                        && eventTime - firstUp <= doubleTimeout;
                if (firstUp != null && !second && !blocked) {
                    emit(key, SteeringTransferPreferences.PRESS_SINGLE, ORIGIN_RELEASE, emit);
                }
                press = new Press(eventTime, now, second, blocked, true, false);
                presses.put(key, press);
            } else {
                press.ordinaryDown = true;
            }
        } else if (action == SteeringTransferPolicy.ACTION_UP
                && press != null && press.ordinaryDown) {
            press.ordinaryDown = false;
            if (!press.nativeDown) presses.remove(key);
            if (press.cancelled || blocked) return true;
            if (press.held) {
                if (press.timerHeld && !press.nativeSeen
                        && SteeringTransferPolicy.hasNativeLongAlias(key)) {
                    lateNativeTails.put(key, eventTime + doubleTimeout);
                }
                return true;
            }
            if (eventTime - press.downAt >= holdTimeout) {
                press.held = true;
                press.timerHeld = true;
                if (SteeringTransferPolicy.hasNativeLongAlias(key)) {
                    lateNativeTails.put(key, eventTime + doubleTimeout);
                }
                emit(key, SteeringTransferPreferences.PRESS_HOLD, ORIGIN_TIMER, emit);
            } else if (press.second) {
                emit(key, SteeringTransferPreferences.PRESS_DOUBLE, ORIGIN_DOUBLE, emit);
            } else {
                // Classify the gesture before looking up this app's matching action.
                pendingUps.put(key, eventTime);
            }
        }
        return true;
    }

    void advance(long now, Consumer<SteeringTransferProfile> emit) {
        advance(now, (profile, origin) -> emit.accept(profile));
    }

    void advance(long now, MatchConsumer emit) {
        Iterator<Map.Entry<Integer, Press>> active = presses.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<Integer, Press> entry = active.next();
            Press press = entry.getValue();
            if (!press.expiredTimerHold && now >= press.lastSeen + tailTimeout) {
                if (press.timerHeld && press.ordinaryDown) {
                    press.expiredTimerHold = true;
                } else {
                    active.remove();
                }
                continue;
            }
            if (!press.cancelled && !press.held
                    && now >= press.downAt + holdTimeout) {
                press.held = true;
                press.timerHeld = true;
                emit(entry.getKey(), SteeringTransferPreferences.PRESS_HOLD, ORIGIN_TIMER, emit);
            }
        }
        Iterator<Map.Entry<Integer, Long>> pending = pendingUps.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, Long> entry = pending.next();
            if (now > entry.getValue() + doubleTimeout) {
                pending.remove();
                emit(entry.getKey(), SteeringTransferPreferences.PRESS_SINGLE, ORIGIN_RELEASE, emit);
            }
        }
    }

    long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (Map.Entry<Integer, Press> entry : presses.entrySet()) {
            Press press = entry.getValue();
            if (!press.expiredTimerHold) {
                next = Math.min(next, press.lastSeen + tailTimeout);
            }
            if (!press.cancelled && !press.held) {
                next = Math.min(next, press.downAt + holdTimeout);
            }
        }
        for (long up : pendingUps.values()) next = Math.min(next, up + doubleTimeout + 1);
        return next;
    }

    private boolean onNativeLong(int key, int action, int repeats, boolean cancelled,
            long eventTime, long now, boolean blocked, MatchConsumer emit) {
        Long lateUntil = lateNativeTails.get(key);
        if (lateUntil != null) {
            if (eventTime <= lateUntil) {
                if (action == SteeringTransferPolicy.ACTION_UP) lateNativeTails.remove(key);
                return true;
            }
            lateNativeTails.remove(key);
        }
        Press press = presses.get(key);
        if (press != null && press.expiredTimerHold
                && action == SteeringTransferPolicy.ACTION_UP) {
            presses.remove(key);
            return true;
        }
        if (cancelled) {
            if (press != null && (action == SteeringTransferPolicy.ACTION_DOWN
                    || press.nativeDown)) {
                press.cancelled = true;
                if (action == SteeringTransferPolicy.ACTION_UP) {
                    press.nativeDown = false;
                    if (!press.ordinaryDown) presses.remove(key);
                }
            }
            return true;
        }
        if (action == SteeringTransferPolicy.ACTION_DOWN) {
            if (press != null) press.lastSeen = now;
            if (repeats != 0) return true;
            if (press == null) {
                Long firstUp = pendingUps.remove(key);
                if (firstUp != null && (eventTime < firstUp
                        || eventTime - firstUp > doubleTimeout) && !blocked) {
                    emit(key, SteeringTransferPreferences.PRESS_SINGLE, ORIGIN_RELEASE, emit);
                }
                press = new Press(eventTime, now, false, blocked, false, true);
                presses.put(key, press);
            } else {
                press.nativeDown = true;
            }
            press.nativeSeen = true;
            if (!press.cancelled && !press.held && !blocked) {
                press.held = true;
                emit(key, SteeringTransferPreferences.PRESS_HOLD, ORIGIN_NATIVE, emit);
            }
        } else if (action == SteeringTransferPolicy.ACTION_UP
                && press != null && press.nativeDown) {
            press.nativeDown = false;
            if (!press.ordinaryDown) presses.remove(key);
        }
        return true;
    }

    private void emit(int key, String mode, String origin, MatchConsumer emit) {
        SteeringTransferProfile profile = SteeringTransferPreferences.find(profiles, key, mode);
        if (profile != null) emit.accept(profile, origin);
    }

    private static final class Press {
        final long downAt;
        final boolean second;
        long lastSeen;
        boolean ordinaryDown;
        boolean nativeDown;
        boolean held;
        boolean timerHeld;
        boolean nativeSeen;
        boolean expiredTimerHold;
        boolean cancelled;

        Press(long downAt, long now, boolean second, boolean cancelled,
                boolean ordinaryDown, boolean nativeDown) {
            this.downAt = downAt;
            this.lastSeen = now;
            this.second = second;
            this.cancelled = cancelled;
            this.ordinaryDown = ordinaryDown;
            this.nativeDown = nativeDown;
        }
    }
}
