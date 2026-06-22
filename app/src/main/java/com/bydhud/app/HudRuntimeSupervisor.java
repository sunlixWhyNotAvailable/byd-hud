package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;

final class HudRuntimeSupervisor {
    private HudRuntimeSupervisor() {
    }

    static void ensureStarted(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String safeReason = safe(reason);
        if (!HudPrefs.isBootEnabled(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.recordLifecycleHook(appContext, "supervisor-disabled", safeReason);
            AppEventLogger.event(appContext,
                    "runtime_supervisor disabled reason=" + safeReason);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!HudRuntimeState.isAlive(appContext, now)) {
            try {
                HudRuntimeState.recordLifecycleHook(appContext, "supervisor-start", safeReason);
                HudRuntimeService.startPersistent(appContext, "supervisor:" + safeReason);
                AppEventLogger.event(appContext, "runtime_supervisor start_service reason="
                        + safeReason + " state=" + HudRuntimeState.summary(appContext, now));
            } catch (RuntimeException e) {
                HudRuntimeState.recordLifecycleHook(appContext, "supervisor-start-failed",
                        safeReason);
                AppEventLogger.event(appContext, "runtime_supervisor start_failed reason="
                        + safeReason + " error=" + e.getClass().getSimpleName()
                        + ":" + safe(e.getMessage()));
            }
        } else {
            HudRuntimeState.recordLifecycleHook(appContext, "supervisor-alive", safeReason);
            AppEventLogger.event(appContext, "runtime_supervisor alive reason="
                    + safeReason + " state=" + HudRuntimeState.summary(appContext, now));
        }
        HudRuntimeWatchdog.schedule(appContext, "supervisor:" + safeReason);
        NavRuntimePermissionRepair.checkAndRepairAsync(
                appContext,
                "supervisor:" + safeReason,
                true,
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
