package com.bydhud.app;

import java.util.Objects;

/** Immutable persisted steering-button action. */
final class SteeringTransferProfile {
    final String id;
    final int keyCode;
    final String pressMode;
    final String packageName;
    final String windowProfile;

    SteeringTransferProfile(String id, int keyCode, String pressMode,
            String packageName, String windowProfile) {
        this.id = id == null ? "" : id;
        this.keyCode = SteeringTransferPolicy.canonicalKeyCode(keyCode);
        this.pressMode = SteeringTransferPreferences.normalizePressMode(pressMode);
        this.packageName = SteeringTransferPreferences.normalizePackage(packageName);
        this.windowProfile = SteeringTransferPreferences.normalizeProfile(windowProfile);
    }

    boolean isValid() {
        return !id.isEmpty() && keyCode >= 0 && !packageName.isEmpty();
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SteeringTransferProfile)) return false;
        SteeringTransferProfile other = (SteeringTransferProfile) value;
        return keyCode == other.keyCode
                && id.equals(other.id)
                && pressMode.equals(other.pressMode)
                && packageName.equals(other.packageName)
                && windowProfile.equals(other.windowProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, keyCode, pressMode, packageName, windowProfile);
    }
}
