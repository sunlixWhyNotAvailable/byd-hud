package com.bydhud.app;

/** Pure transaction policy for boot-scoped persisted-state cleanup. */
final class BootCleanupPolicy {
    static final int UNKNOWN_BOOT_COUNT = -1;

    enum Trigger {
        INITIALIZE,
        QUICKBOOT,
        BOOT_COMPLETED
    }

    enum Decision {
        RECOVERY_ONLY,
        SEED_OBSERVED,
        RESET_VALID_COUNT,
        RESET_TRUSTED_UNKNOWN
    }

    enum Outcome {
        RECOVERY_ONLY,
        SEEDED,
        SEED_FAILED,
        RESET_COMPLETE,
        PROJECTION_CLEAR_FAILED,
        WAZE_CLEAR_FAILED,
        MARKER_WRITE_FAILED
    }

    static final class State {
        final int observedBootCount;
        final int completedBootCount;

        State(int observedBootCount, int completedBootCount) {
            this.observedBootCount = observedBootCount;
            this.completedBootCount = completedBootCount;
        }
    }

    interface Store {
        State read();
        boolean seedObserved(int bootCount);
        boolean completeValidReset(int bootCount);
        boolean completeUnknownReset();
    }

    interface Clears {
        boolean clearProjection();
        boolean clearWaze();
    }

    interface MarkerBackend {
        State read();
        boolean write(State state);
    }

    private BootCleanupPolicy() {
    }

    static Decision decide(Trigger trigger, int bootCount, State state) {
        if (bootCount >= 0) {
            if (state.observedBootCount < 0) {
                return trigger == Trigger.BOOT_COMPLETED
                        ? Decision.RESET_VALID_COUNT : Decision.SEED_OBSERVED;
            }
            if (state.observedBootCount != bootCount) return Decision.RESET_VALID_COUNT;
            return trigger == Trigger.BOOT_COMPLETED
                    && state.completedBootCount != bootCount
                    ? Decision.RESET_VALID_COUNT : Decision.RECOVERY_ONLY;
        }
        return trigger == Trigger.BOOT_COMPLETED
                ? Decision.RESET_TRUSTED_UNKNOWN : Decision.RECOVERY_ONLY;
    }

    static Outcome execute(Trigger trigger, int bootCount,
            Store store, Clears clears) {
        Decision decision = decide(trigger, bootCount, store.read());
        if (decision == Decision.RECOVERY_ONLY) return Outcome.RECOVERY_ONLY;
        if (decision == Decision.SEED_OBSERVED) {
            return store.seedObserved(bootCount) ? Outcome.SEEDED : Outcome.SEED_FAILED;
        }
        if (!clears.clearProjection()) return Outcome.PROJECTION_CLEAR_FAILED;
        if (!clears.clearWaze()) return Outcome.WAZE_CLEAR_FAILED;
        boolean marked = decision == Decision.RESET_VALID_COUNT
                ? store.completeValidReset(bootCount)
                : store.completeUnknownReset();
        return marked ? Outcome.RESET_COMPLETE : Outcome.MARKER_WRITE_FAILED;
    }

    /** SharedPreferences mutates memory before reporting disk failure; restore the
     * previously confirmed values so an in-process retry cannot deduplicate them. */
    static boolean writeMarkersDurably(MarkerBackend backend, State next) {
        State previous = backend.read();
        if (backend.write(next)) return true;
        backend.write(previous);
        return false;
    }
}
