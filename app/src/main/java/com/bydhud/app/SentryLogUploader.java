package com.bydhud.app;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

import io.sentry.Attachment;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryId;

// Sends only an explicitly selected diagnostic archive; no automatic telemetry is enabled.
final class SentryLogUploader {
    static final long MAX_ZIP_BYTES = 20L * 1024L * 1024L;

    static final class Result {
        final boolean ok;
        final String eventId;
        final String detail;

        Result(boolean ok, String eventId, String detail) {
            this.ok = ok;
            this.eventId = eventId == null ? "" : eventId;
            this.detail = detail == null ? "" : detail;
        }
    }

    private SentryLogUploader() {
    }

    static String newUploadId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static Result upload(Context context, File archive, List<String> days) {
        return upload(context, archive, days, "");
    }

    static Result upload(Context context, File archive, List<String> days, String uploadId) {
        return upload(context, archive,
                "BYD HUD manual navigation log upload",
                "navigation_logs",
                days == null ? "" : String.join(",", days), uploadId);
    }

    static Result uploadConfiguration(Context context, File archive) {
        return upload(context, archive,
                "BYD HUD manual vehicle configuration upload",
                "vehicle_configuration",
                "", "");
    }

    private static Result upload(Context context, File archive, String messageText,
            String uploadType, String selectedDays, String uploadId) {
        String validation = validate(BuildConfig.SENTRY_DSN, archive);
        if (!validation.isEmpty()) {
            LogShareZip.deleteArtifact(archive);
            return new Result(false, "", validation);
        }
        try {
            SentryAndroid.init(context.getApplicationContext(), options -> {
                options.setDsn(BuildConfig.SENTRY_DSN);
                options.setSampleRate(1.0);
                options.setTracesSampleRate(0.0);
                options.setProfilesSampleRate(0.0);
                options.setSendDefaultPii(false);
                options.setEnableUncaughtExceptionHandler(false);
                options.setAnrEnabled(false);
                options.setReportHistoricalAnrs(false);
                options.setEnableAutoSessionTracking(false);
                options.setEnableActivityLifecycleBreadcrumbs(false);
                options.setEnableAppLifecycleBreadcrumbs(false);
                options.setEnableSystemEventBreadcrumbs(false);
                options.setEnableAppComponentBreadcrumbs(false);
                options.setEnableUserInteractionBreadcrumbs(false);
                options.setEnableAutoActivityLifecycleTracing(false);
                options.setEnableFramesTracking(false);
                options.setAttachScreenshot(false);
                options.setAttachViewHierarchy(false);
                options.getIntegrations().clear();
                options.setBeforeSend((event, hint) ->
                        "true".equals(event.getTag("bydhud_manual_upload")) ? event : null);
            });

            SentryEvent event = buildManualUploadEvent(
                    messageText, uploadType, selectedDays, uploadId);

            Hint hint = new Hint();
            hint.addAttachment(new Attachment(
                    archive.getAbsolutePath(), archive.getName(), "application/zip"));
            SentryId eventId = Sentry.captureEvent(event, hint);
            if (SentryId.EMPTY_ID.equals(eventId)) {
                return new Result(false, "", "Sentry did not accept the upload");
            }
            Sentry.flush(30_000L);
            return new Result(true, eventId.toString(), "uploaded");
        } catch (Throwable error) {
            return new Result(false, "", error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
        } finally {
            try {
                Sentry.close();
            } catch (Throwable ignored) {
            }
            LogShareZip.deleteArtifact(archive);
        }
    }

    static SentryEvent buildManualUploadEvent(
            String messageText, String uploadType, String selectedDays) {
        return buildManualUploadEvent(messageText, uploadType, selectedDays, "");
    }

    static SentryEvent buildManualUploadEvent(
            String messageText, String uploadType, String selectedDays, String uploadId) {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage(messageText);
        event.setMessage(message);
        event.setLevel(SentryLevel.INFO);
        event.setTag("bydhud_manual_upload", "true");
        event.setTag("app_version", BuildConfig.VERSION_NAME);
        event.setTag("version_code", String.valueOf(BuildConfig.VERSION_CODE));
        event.setTag("upload_type", uploadType);
        if (selectedDays != null && !selectedDays.isEmpty()) {
            event.setTag("selected_days", selectedDays);
        }
        if (uploadId != null && !uploadId.isEmpty()) {
            event.setTag("upload_id", uploadId);
        }
        return event;
    }

    static String validate(String dsn, File archive) {
        if (dsn == null || !dsn.startsWith("https://")
                || dsn.contains("\n") || dsn.contains("\r")) {
            return "Sentry is not configured";
        }
        if (archive == null || !archive.isFile()) {
            return "Archive is missing";
        }
        long bytes = archive.length();
        if (bytes <= 0L || bytes > MAX_ZIP_BYTES) {
            return "Archive must be between 1 byte and 20 MiB";
        }
        try (ZipFile zip = new ZipFile(archive)) {
            if (!zip.entries().hasMoreElements()) {
                return "Archive is empty";
            }
        } catch (Exception error) {
            return "Archive is invalid";
        }
        return "";
    }
}
