package com.bydhud.app;

//models app visibility so background and foreground decisions do not depend on raw dumpsys text.

final class NavAppDisplayState {
    static final int DISPLAY_UNKNOWN = -1;

    final String packageName;
    final int taskId;
    final int displayId;
    final boolean visible;
    final String status;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
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

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isOnMainDisplay() {
        return displayId == 0;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isOnDashboardDisplay() {
        return displayId > 0;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isUsableForCrop() {
        return taskId >= 0
                && displayId != DISPLAY_UNKNOWN
                && visible;
    }
}
