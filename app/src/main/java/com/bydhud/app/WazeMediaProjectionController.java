package com.bydhud.app;

//coordinates Waze MediaProjection consent so crop code only asks for frames.

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;

//keeps projection state process-local because Android screen-capture consent cannot be persisted safely.
final class WazeMediaProjectionController {
    private static final long CONSENT_PROMPT_THROTTLE_MS = 30000L;
    private static final Object LOCK = new Object();

    private static int resultCode;
    private static Intent resultData;
    private static WazeMediaProjectionService service;
    private static long lastPromptElapsedMs;

    private WazeMediaProjectionController() {
    }

    //starts Android consent only when there is no usable mirrored frame source.
    static void ensureReadyOrPrompt(Context context, String reason) {
        if (context == null || isFrameSourceReady()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - lastPromptElapsedMs < CONSENT_PROMPT_THROTTLE_MS) {
                return;
            }
            lastPromptElapsedMs = now;
        }
        Intent intent = new Intent(context.getApplicationContext(),
                WazeMediaProjectionConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("reason", safe(reason));
        context.getApplicationContext().startActivity(intent);
        AppEventLogger.event(context, "waze_frame_capture consent_prompt reason=" + safe(reason));
    }

    //stores the one-use consent payload long enough to start the foreground projection service.
    static void onConsentResult(Context context, int code, Intent data) {
        synchronized (LOCK) {
            resultCode = code;
            resultData = data;
        }
        Intent serviceIntent = new Intent(context.getApplicationContext(),
                WazeMediaProjectionService.class);
        serviceIntent.putExtra(WazeMediaProjectionService.EXTRA_RESULT_CODE, code);
        serviceIntent.putExtra(WazeMediaProjectionService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getApplicationContext().startForegroundService(serviceIntent);
        } else {
            context.getApplicationContext().startService(serviceIntent);
        }
        clearConsent();
    }

    //registers the service so WazeCropCapture can synchronously request the latest frame.
    static void registerService(WazeMediaProjectionService activeService) {
        synchronized (LOCK) {
            service = activeService;
        }
    }

    //drops stale service references when Android stops the projection.
    static void unregisterService(WazeMediaProjectionService stoppedService) {
        synchronized (LOCK) {
            if (service == stoppedService) {
                service = null;
            }
        }
    }

    //reports readiness without launching UI from worker capture code.
    static boolean isFrameSourceReady() {
        synchronized (LOCK) {
            return service != null && service.isReady();
        }
    }

    //returns a caller-owned bitmap copy of the latest main-display frame.
    static Bitmap latestMainFrame() {
        synchronized (LOCK) {
            return service == null ? null : service.copyLatestFrame();
        }
    }

    //restarts from cached consent only if Android still accepts the in-process token.
    static void restartFromCachedConsent(Context context, String reason) {
        Intent data;
        int code;
        synchronized (LOCK) {
            data = resultData;
            code = resultCode;
        }
        if (data == null || code != Activity.RESULT_OK) {
            ensureReadyOrPrompt(context, reason);
            return;
        }
        onConsentResult(context, code, data);
    }

    //clears one-shot consent data so Android token reuse cannot crash recovery paths.
    static void clearConsent() {
        synchronized (LOCK) {
            resultCode = 0;
            resultData = null;
        }
    }

    //clears capture ownership after package replacement so stale ImageReader frames cannot feed Waze parsing.
    static void resetForRuntimeReinit(Context context, String reason) {
        Context appContext = context == null ? null : context.getApplicationContext();
        synchronized (LOCK) {
            resultCode = 0;
            resultData = null;
            lastPromptElapsedMs = 0L;
        }
        if (appContext != null) {
            appContext.stopService(new Intent(appContext, WazeMediaProjectionService.class));
            AppEventLogger.event(appContext, "waze_frame_capture reset_for_runtime_reinit reason="
                    + safe(reason));
        }
    }

    //normalizes log fields so event lines stay single-line JSON-friendly text.
    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
