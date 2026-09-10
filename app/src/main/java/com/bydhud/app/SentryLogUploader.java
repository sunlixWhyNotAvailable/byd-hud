package com.bydhud.app;

import android.content.Context;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipFile;

import io.sentry.Attachment;
import io.sentry.Hint;
import io.sentry.SentryClient;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.hints.SubmissionResult;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryId;
import io.sentry.util.HintUtils;

// Sends only an explicitly selected diagnostic archive; no automatic telemetry is enabled.
final class SentryLogUploader {
    static final long MAX_ZIP_BYTES = 39_000_000L;

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

    static final class SubmissionResultTracker implements SubmissionResult {
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile boolean success;

        @Override
        public void setResult(boolean result) {
            success = result;
            completion.countDown();
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        void await() throws InterruptedException {
            completion.await();
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
                days == null ? "" : String.join(",", days), uploadId, null);
    }

    static Result upload(Context context, File archive, List<String> days, String uploadId,
            SentryLogReport report) {
        return upload(context, archive, report.getTitle(), "navigation_logs",
                days == null ? "" : String.join(",", days), uploadId, report.getComment());
    }

    private static Result upload(Context context, File archive, String messageText,
            String uploadType, String selectedDays, String uploadId, String userComment) {
        String validation = validate(BuildConfig.SENTRY_DSN, archive);
        if (!validation.isEmpty()) {
            LogShareZip.deleteArtifact(archive);
            return new Result(false, "", validation);
        }
        SentryClient client = null;
        try {
            SentryOptions options = buildOptions(BuildConfig.SENTRY_DSN);
            client = new SentryClient(options);

            SentryEvent event = buildManualUploadEvent(
                    messageText, uploadType, selectedDays, uploadId, userComment);

            Hint hint = new Hint();
            hint.addAttachment(new Attachment(
                    archive.getAbsolutePath(), archive.getName(), "application/zip"));
            SubmissionResultTracker submissionResult = new SubmissionResultTracker();
            HintUtils.setTypeCheckHint(hint, submissionResult);
            SentryId eventId = client.captureEvent(event, hint);
            if (SentryId.EMPTY_ID.equals(eventId)) {
                return new Result(false, "", "Sentry did not accept the upload");
            }
            submissionResult.await();
            if (!submissionResult.isSuccess()) {
                return new Result(false, "", "Sentry did not deliver the upload");
            }
            return new Result(true, eventId.toString(), "uploaded");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(false, "", "Upload interrupted");
        } catch (Throwable error) {
            return new Result(false, "", error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
        } finally {
            try {
                if (client != null) client.close(false);
            } catch (Throwable ignored) {
            }
            LogShareZip.deleteArtifact(archive);
        }
    }

    static SentryOptions buildOptions(String dsn) {
        SentryOptions options = new SentryOptions();
        options.setDsn(dsn);
        options.setSampleRate(1.0);
        options.setTracesSampleRate(0.0);
        options.setProfilesSampleRate(0.0);
        options.setSendDefaultPii(false);
        options.setEnableUncaughtExceptionHandler(false);
        options.setEnableAutoSessionTracking(false);
        options.setEnableShutdownHook(false);
        options.setMaxAttachmentSize(MAX_ZIP_BYTES);
        options.getIntegrations().clear();
        options.setBeforeSend((event, hint) ->
                "true".equals(event.getTag("bydhud_manual_upload")) ? event : null);
        return options;
    }

    static SentryEvent buildManualUploadEvent(
            String messageText, String uploadType, String selectedDays) {
        return buildManualUploadEvent(messageText, uploadType, selectedDays, "");
    }

    static SentryEvent buildManualUploadEvent(
            String messageText, String uploadType, String selectedDays, String uploadId) {
        return buildManualUploadEvent(messageText, uploadType, selectedDays, uploadId, null);
    }

    static SentryEvent buildManualUploadEvent(String messageText, String uploadType,
            String selectedDays, String uploadId, String userComment) {
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
            event.setFingerprints(Collections.singletonList("manual-navigation-upload:" + uploadId));
        }
        if (userComment != null) {
            event.setExtra("user_comment", userComment);
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
        if (bytes <= 0L || bytes >= MAX_ZIP_BYTES) {
            return "Archive must be between 1 byte and 38,999,999 bytes";
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
