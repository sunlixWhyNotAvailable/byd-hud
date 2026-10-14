package com.bydhud.app;

/** Last known native-HUD limit belongs only to its active route and output mode. */
final class NativeHudSpeedLimit {
    private String owner = "";
    private long session = Long.MIN_VALUE;
    private int limitKph;

    int update(boolean enabled, String packageName, long generation, int currentKph) {
        if (!enabled || packageName == null || packageName.isEmpty() || generation < 0L) {
            reset();
            return 0;
        }
        if (!owner.equals(packageName) || session != generation) {
            reset();
            owner = packageName;
            session = generation;
        }
        if (currentKph > 0) limitKph = currentKph;
        return limitKph;
    }

    void reset() {
        owner = "";
        session = Long.MIN_VALUE;
        limitKph = 0;
    }
}
