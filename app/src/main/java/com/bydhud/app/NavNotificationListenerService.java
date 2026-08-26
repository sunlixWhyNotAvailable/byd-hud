package com.bydhud.app;

//listens to notifications for bounded log-only diagnostics.

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

//anchors the NavNotificationListenerService android entry point so lifecycle recovery stays separate from business logic.
public final class NavNotificationListenerService extends NotificationListenerService {
    private static final int FIELD_CHAR_LIMIT = 300;
    private static final int TEXT_LINES_CHAR_LIMIT = 1200;
    private static final int PAYLOAD_CHAR_LIMIT = 1200;
    private static final int DEFAULT_LARGE_ICON_DIMENSION = 96;
    private static final int MAX_LARGE_ICON_DIMENSION = 192;
    private static final String TRUNCATED_MARKER = "[truncated]";
    private static volatile NavNotificationListenerService activeService;
    private static volatile long lastConnectedElapsedMs;
    private static volatile long lastDisconnectedElapsedMs;
    private static volatile String lastRuntimeDetail = "never connected";
    private final HandlerThread notificationThread =
            new HandlerThread("BydHudNotificationCapture", Process.THREAD_PRIORITY_BACKGROUND);
    private Handler notificationHandler;
    private final Object postedLock = new Object();
    private final LinkedHashMap<String, StatusBarNotification> pendingPosted =
            new LinkedHashMap<>();
    private boolean postedDrainScheduled;
    private final Set<String> observedThisProcess = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        notificationThread.start();
        notificationHandler = new Handler(notificationThread.getLooper());
        NavCaptureIngressPolicy.refreshPreferencesAsync(this);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void requestActiveNotificationScan(Context context, String reason) {
        NavNotificationListenerService service = activeService;
        if (service == null) {
            AppEventLogger.event(context, "notification_active_scan skipped no-listener reason="
                    + safe(reason));
            return;
        }
        service.postNotificationWork(
                () -> service.processActiveNotifications(reason));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isConnectedForRuntimeCheck() {
        return activeService != null;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String runtimeDetailForRuntimeCheck() {
        NavNotificationListenerService service = activeService;
        if (service != null) {
            return "connected elapsedMs=" + lastConnectedElapsedMs;
        }
        return lastRuntimeDetail;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void requestRuntimeRebind(Context context, String reason) {
        if (HudPrefs.isUserShutdownActive(context)) {
            AppEventLogger.event(context, "notification_listener rebind skipped shutdown_active reason="
                    + safe(reason));
            return;
        }
        try {
            android.content.ComponentName component = new android.content.ComponentName(
                    context.getPackageName(),
                    NavNotificationListenerService.class.getName());
            NotificationListenerService.requestRebind(component);
            AppEventLogger.event(context, "notification_listener request_rebind reason="
                    + safe(reason));
        } catch (RuntimeException e) {
            AppEventLogger.event(context, "notification_listener request_rebind failed "
                    + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
        }
    }

    static void cancelPendingPosted(String packageName) {
        NavNotificationListenerService service = activeService;
        if (service == null) return;
        String prefix = safe(packageName).toLowerCase(java.util.Locale.ROOT) + "\n";
        synchronized (service.postedLock) {
            service.pendingPosted.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    static void suspendForUserShutdown(Context context, String reason) {
        NavNotificationListenerService service = activeService;
        if (service == null) {
            return;
        }
        Handler handler = service.notificationHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        service.clearPendingPosted();
        try {
            service.requestUnbind();
            AppEventLogger.event(context, "notification_listener request_unbind reason="
                    + safe(reason));
        } catch (RuntimeException error) {
            AppEventLogger.event(context, "notification_listener request_unbind failed "
                    + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
    }

    static void resumeAfterUserShutdown(Context context, String reason) {
        requestRuntimeRebind(context, "resume-after-shutdown:" + safe(reason));
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onListenerConnected() {
        super.onListenerConnected();
        activeService = this;
        lastConnectedElapsedMs = SystemClock.elapsedRealtime();
        lastRuntimeDetail = "connected; validation pending";
        NavCaptureIngressPolicy.refreshPreferencesAsync(this);
        Handler handler = notificationHandler;
        if (handler != null) handler.post(() -> {
            if (activeService != this) return;
            if (HudPrefs.isUserShutdownActive(this)) {
                lastRuntimeDetail = "connected while shutdown active";
                suspendForUserShutdown(this, "listener-connected-shutdown-active");
                return;
            }
            lastRuntimeDetail = "connected";
            AppEventLogger.event(this, "notification_listener connected");
            processActiveNotifications("listener-connected");
        });
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onListenerDisconnected() {
        if (activeService == this) {
            activeService = null;
        }
        Handler handler = notificationHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        clearPendingPosted();
        lastDisconnectedElapsedMs = SystemClock.elapsedRealtime();
        lastRuntimeDetail = "disconnected elapsedMs=" + lastDisconnectedElapsedMs;
        postNotificationTeardown("listener-disconnected");
        super.onListenerDisconnected();
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        Handler handler = notificationHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        clearPendingPosted();
        lastRuntimeDetail = "destroyed";
        postNotificationTeardown("listener-destroyed");
        notificationThread.quitSafely();
        super.onDestroy();
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String packageName = safe(sbn.getPackageName());
        if (packageName.isEmpty()) return;
        boolean newlyObserved;
        synchronized (observedThisProcess) {
            newlyObserved = observedThisProcess.size() < 128
                    && observedThisProcess.add(packageName);
        }
        if (newlyObserved) postObservedPackage(packageName);
        if (NavCaptureIngressPolicy.mode(packageName)
                != NavCaptureIngressPolicy.Mode.LOG_ONLY) return;
        enqueuePosted(sbn);
    }

    private void postObservedPackage(String packageName) {
        postNotificationWork(() ->
                NavCapturePrefs.addObservedPackageFast(this, packageName));
    }

    private void enqueuePosted(StatusBarNotification sbn) {
        Handler handler = notificationHandler;
        if (handler == null) return;
        String token = postedToken(sbn);
        synchronized (postedLock) {
            pendingPosted.put(token, sbn);
            while (pendingPosted.size() > 32) {
                pendingPosted.remove(pendingPosted.keySet().iterator().next());
            }
            if (postedDrainScheduled) return;
            postedDrainScheduled = true;
        }
        if (!handler.post(this::drainPosted)) {
            synchronized (postedLock) {
                postedDrainScheduled = false;
            }
        }
    }

    private void clearPendingPosted() {
        synchronized (postedLock) {
            pendingPosted.clear();
            postedDrainScheduled = false;
        }
    }

    private void drainPosted() {
        int processed = 0;
        while (processed < 16) {
            StatusBarNotification sbn;
            synchronized (postedLock) {
                if (pendingPosted.isEmpty()) {
                    postedDrainScheduled = false;
                    return;
                }
                Map.Entry<String, StatusBarNotification> entry =
                        pendingPosted.entrySet().iterator().next();
                sbn = entry.getValue();
                pendingPosted.remove(entry.getKey());
            }
            processPostedNotification(sbn, "posted");
            processed++;
        }
        Handler handler = notificationHandler;
        if (handler == null || !handler.post(this::drainPosted)) {
            synchronized (postedLock) {
                postedDrainScheduled = false;
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void processActiveNotifications(String reason) {
        if (HudPrefs.isUserShutdownActive(this)) {
            return;
        }
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            int count = notifications == null ? 0 : notifications.length;
            AppEventLogger.event(this, "notification_active_scan reason=" + safe(reason)
                    + " count=" + count);
            if (notifications == null) {
                return;
            }
            for (StatusBarNotification sbn : notifications) {
                processPostedNotification(
                        sbn, "active-" + safe(reason));
            }
        } catch (RuntimeException e) {
            AppEventLogger.event(this, "notification_active_scan failed "
                    + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void processPostedNotification(StatusBarNotification sbn, String source) {
        if (sbn == null || HudPrefs.isUserShutdownActive(this)) {
            return;
        }
        String packageName = sbn.getPackageName();
        NavCaptureIngressPolicy.Mode mode = NavCaptureIngressPolicy.mode(packageName);
        if (mode != NavCaptureIngressPolicy.Mode.LOG_ONLY) return;
        Notification notification = sbn.getNotification();
        if (isOngoing(notification)) {
            NavCapturePrefs.addObservedPackageFast(this, packageName);
        }
        NotificationFields fields = notificationFields(sbn);
        String payload = buildPostedPayload(fields, source);
        NavCaptureStore.rawEvent(this,
                "posted".equals(source) ? "notification" : "notification_active",
                packageName,
                payload);
        long nowElapsedMs = SystemClock.elapsedRealtime();
        NavManeuverEvidence maneuverEvidence = largeIconEvidence(packageName, notification,
                nowElapsedMs);
        NavParserResult parsed = NavParserDispatcher.parseNotification(
                packageName,
                fields.title,
                fields.text,
                fields.subText,
                fields.bigText,
                fields.textLines,
                fields.category,
                fields.ongoing,
                maneuverEvidence,
                nowElapsedMs);
        if (parsed != null) {
            NavCaptureStore.snapshot(this, parsed.snapshot);
        }
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        synchronized (postedLock) {
            pendingPosted.remove(postedToken(sbn));
        }
        postNotificationWork(() -> processRemovedNotification(sbn));
    }

    private void processRemovedNotification(StatusBarNotification sbn) {
        if (sbn == null || HudPrefs.isUserShutdownActive(this)) {
            return;
        }
        String packageName = sbn.getPackageName();
        if (NavCaptureIngressPolicy.mode(packageName)
                != NavCaptureIngressPolicy.Mode.LOG_ONLY) return;
        String payload = "key=" + safe(sbn.getKey());
        NavCaptureStore.rawEvent(this, "notification_removed", packageName, payload);
    }

    private boolean postNotificationWork(Runnable work) {
        Handler handler = notificationHandler;
        return handler != null && work != null && handler.post(() -> {
            if (activeService == this && !HudPrefs.isUserShutdownActive(this)) {
                work.run();
            }
        });
    }

    private static String postedToken(StatusBarNotification sbn) {
        return safe(sbn == null ? "" : sbn.getPackageName())
                .toLowerCase(java.util.Locale.ROOT)
                + "\n" + safe(sbn == null ? "" : sbn.getKey());
    }

    private void postNotificationTeardown(String reason) {
        Runnable cleanup = () -> {
            WazeCaptureDebugWriter.get().appEvent(
                    this, "notification_listener " + reason);
        };
        Handler handler = notificationHandler;
        if (handler == null || !handler.post(cleanup)) {
            cleanup.run();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static NotificationFields notificationFields(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        return new NotificationFields(
                sbn.getKey(),
                charSequenceExtra(extras, Notification.EXTRA_TITLE),
                charSequenceExtra(extras, Notification.EXTRA_TEXT),
                charSequenceExtra(extras, Notification.EXTRA_SUB_TEXT),
                charSequenceExtra(extras, Notification.EXTRA_BIG_TEXT),
                textLinesExtra(extras),
                notification == null ? "" : notification.category,
                isOngoing(notification));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private NavManeuverEvidence largeIconEvidence(String packageName, Notification notification,
            long nowElapsedMs) {
        if (NavTextNormalizer.sourceApp(packageName) != NavSnapshot.SourceApp.GOOGLE_MAPS) {
            return NavManeuverEvidence.NONE;
        }
        Bitmap bitmap = notificationLargeIconBitmap(packageName, notification);
        NavManeuverEvidence evidence = largeIconMatchEvidence(packageName, bitmap, nowElapsedMs);
        String payload = "Notification.largeIcon "
                + bitmapSummary(bitmap)
                + " " + evidence.summary();
        NavCaptureStore.rawEvent(this, "notification_large_icon", packageName,
                capValue(payload, PAYLOAD_CHAR_LIMIT));
        if (evidence.source == NavManeuverEvidence.Source.NONE) {
            AppEventLogger.event(this, "notification_large_icon unmatched package=" + packageName);
        } else {
            AppEventLogger.event(this, "notification_large_icon matched package=" + packageName
                    + " " + evidence.summary());
        }
        return evidence;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private NavManeuverEvidence largeIconMatchEvidence(String packageName, Bitmap bitmap,
            long nowElapsedMs) {
        try {
            return GMapsLargeIconManeuverMatcher.match(bitmap, nowElapsedMs);
        } catch (RuntimeException e) {
            String message = "Notification.largeIcon match failed: " + safe(e.getMessage());
            NavCaptureStore.rawEvent(this, "notification_large_icon", packageName,
                    capValue(message, PAYLOAD_CHAR_LIMIT));
            AppEventLogger.event(this, "notification_large_icon match failed package="
                    + packageName + " " + safe(e.getMessage()));
            return NavManeuverEvidence.NONE;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Bitmap notificationLargeIconBitmap(String packageName, Notification notification) {
        if (notification == null) {
            return null;
        }
        try {
            Icon icon = notification.getLargeIcon();
            if (icon == null) {
                return null;
            }
            Drawable drawable = icon.loadDrawable(this);
            return drawableToBitmap(drawable);
        } catch (RuntimeException e) {
            NavCaptureStore.rawEvent(this, "notification_large_icon", packageName,
                    "Notification.largeIcon extraction failed: " + safe(e.getMessage()));
            AppEventLogger.event(this, "notification_large_icon extraction failed: "
                    + safe(e.getMessage()));
            return null;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                return bitmapToSafeArgbBitmap(bitmap);
            }
        }
        int width = drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth()
                : DEFAULT_LARGE_ICON_DIMENSION;
        int height = drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight()
                : DEFAULT_LARGE_ICON_DIMENSION;
        int[] scaled = scaleToFit(width, height, MAX_LARGE_ICON_DIMENSION);
        Bitmap bitmap = Bitmap.createBitmap(scaled[0], scaled[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Bitmap bitmapToSafeArgbBitmap(Bitmap source) {
        int[] scaled = scaleToFit(source.getWidth(), source.getHeight(), MAX_LARGE_ICON_DIMENSION);
        Bitmap bitmap = Bitmap.createBitmap(scaled[0], scaled[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Rect sourceRect = new Rect(0, 0, source.getWidth(), source.getHeight());
        Rect targetRect = new Rect(0, 0, scaled[0], scaled[1]);
        canvas.drawBitmap(source, sourceRect, targetRect, null);
        return bitmap;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static int[] scaleToFit(int width, int height, int maxDimension) {
        int safeWidth = width > 0 ? width : DEFAULT_LARGE_ICON_DIMENSION;
        int safeHeight = height > 0 ? height : DEFAULT_LARGE_ICON_DIMENSION;
        int maxSourceDimension = Math.max(safeWidth, safeHeight);
        if (maxSourceDimension <= maxDimension) {
            return new int[] { safeWidth, safeHeight };
        }
        float scale = (float) maxDimension / (float) maxSourceDimension;
        int scaledWidth = Math.max(1, Math.round(safeWidth * scale));
        int scaledHeight = Math.max(1, Math.round(safeHeight * scale));
        return new int[] { scaledWidth, scaledHeight };
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String bitmapSummary(Bitmap bitmap) {
        if (bitmap == null) {
            return "bitmap=none";
        }
        try {
            return "bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight();
        } catch (RuntimeException e) {
            return "bitmap=unreadable";
        }
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static String buildPostedPayload(NotificationFields fields, String source) {
        StringBuilder builder = new StringBuilder(256);
        appendField(builder, "source", source);
        appendField(builder, "key", fields.key);
        appendField(builder, "title", fields.title);
        appendField(builder, "text", fields.text);
        appendField(builder, "subText", fields.subText);
        appendField(builder, "bigText", fields.bigText);
        appendField(builder, "textLines", fields.textLines);
        appendField(builder, "category", fields.category);
        appendField(builder, "ongoing", fields.ongoing ? "true" : "false");
        return capValue(builder.toString(), PAYLOAD_CHAR_LIMIT);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String charSequenceExtra(Bundle extras, String key) {
        if (extras == null) {
            return "";
        }
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String textLinesExtra(Bundle extras) {
        if (extras == null) {
            return "";
        }
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence line : lines) {
            if (line == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(capValue(line.toString(), FIELD_CHAR_LIMIT));
        }
        return capValue(builder.toString(), TEXT_LINES_CHAR_LIMIT);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isOngoing(Notification notification) {
        return notification != null
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendField(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(key).append('=').append(capValue(safe(value), FIELD_CHAR_LIMIT));
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String capValue(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        int prefixLength = Math.max(0, limit - TRUNCATED_MARKER.length());
        return value.substring(0, prefixLength) + TRUNCATED_MARKER;
    }

    //defines the NotificationFields module boundary so related behavior stays readable inside one unit.
    private static final class NotificationFields {
        final String key;
        final String title;
        final String text;
        final String subText;
        final String bigText;
        final String textLines;
        final String category;
        final boolean ongoing;

        NotificationFields(String key, String title, String text, String subText,
                String bigText, String textLines, String category, boolean ongoing) {
            this.key = capParserField(key);
            this.title = capParserField(title);
            this.text = capParserField(text);
            this.subText = capParserField(subText);
            this.bigText = capParserField(bigText);
            this.textLines = capValue(safe(textLines), TEXT_LINES_CHAR_LIMIT);
            this.category = capParserField(category);
            this.ongoing = ongoing;
        }
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static String capParserField(String value) {
        return capValue(safe(value), FIELD_CHAR_LIMIT);
    }
}
