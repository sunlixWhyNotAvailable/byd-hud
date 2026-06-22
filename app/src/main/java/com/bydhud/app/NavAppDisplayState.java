package com.bydhud.app;

final class NavAppDisplayState {
    static final int DISPLAY_UNKNOWN = -1;

    final String packageName;
    final int taskId;
    final int displayId;
    final boolean visible;
    final String status;

    NavAppDisplayState(
            String packageName,
            int taskId,
            int displayId,
            boolean visible,
            String status) {
        this.packageName = packageName == null ? "" : packageName;
        this.taskId = taskId;
        this.displayId = displayId;
        this.visible = visible;
        this.status = status == null ? "" : status;
    }

    boolean isOnMainDisplay() {
        return displayId == 0;
    }

    boolean isOnDashboardDisplay() {
        return displayId > 0;
    }

    boolean isUsableForCrop() {
        return taskId >= 0
                && displayId != DISPLAY_UNKNOWN
                && visible;
    }
}
