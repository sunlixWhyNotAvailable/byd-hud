package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.sentry.SentryEvent;

public final class SentryLogUploaderTest {
    @Test
    public void manualUploadMetadataDistinguishesLogsAndConfiguration() {
        SentryEvent logs = SentryLogUploader.buildManualUploadEvent(
                "logs", "navigation_logs", "20260803,20260804");
        SentryEvent configuration = SentryLogUploader.buildManualUploadEvent(
                "configuration", "vehicle_configuration", "");

        assertEquals("navigation_logs", logs.getTag("upload_type"));
        assertEquals("20260803,20260804", logs.getTag("selected_days"));
        assertEquals("vehicle_configuration", configuration.getTag("upload_type"));
        assertNull(configuration.getTag("selected_days"));
    }

    @Test
    public void navigationUploadCarriesTheSameShortIdAsItsEventMetadata() {
        String uploadId = SentryLogUploader.newUploadId();
        SentryEvent event = SentryLogUploader.buildManualUploadEvent(
                "logs", "navigation_logs", "20260803", uploadId);

        assertTrue(uploadId.matches("[0-9a-f]{8}"));
        assertEquals(uploadId, event.getTag("upload_id"));
        assertEquals(Collections.singletonList("manual-navigation-upload:" + uploadId),
                event.getFingerprints());
    }

    @Test
    public void emptyUploadIdAndConfigurationKeepDefaultGrouping() {
        SentryEvent logs = SentryLogUploader.buildManualUploadEvent(
                "logs", "navigation_logs", "20260803", "");
        SentryEvent configuration = SentryLogUploader.buildManualUploadEvent(
                "configuration", "vehicle_configuration", "", "");

        assertNull(logs.getTag("upload_id"));
        assertNull(logs.getFingerprints());
        assertNull(configuration.getTag("upload_id"));
        assertNull(configuration.getFingerprints());
    }

    @Test
    public void submissionTrackerCoversPendingTimeoutFalseAndTrue() throws Exception {
        SentryLogUploader.SubmissionResultTracker pending =
                new SentryLogUploader.SubmissionResultTracker();
        assertFalse(pending.await(0L));

        SentryLogUploader.SubmissionResultTracker rejected =
                new SentryLogUploader.SubmissionResultTracker();
        rejected.setResult(false);
        assertTrue(rejected.await(1L));
        assertFalse(rejected.isSuccess());

        SentryLogUploader.SubmissionResultTracker accepted =
                new SentryLogUploader.SubmissionResultTracker();
        accepted.setResult(true);
        assertTrue(accepted.await(1L));
        assertTrue(accepted.isSuccess());
    }

    @Test
    public void failedAndTimedOutUploadsRetainArchiveWhileSuccessDeletesIt() throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/SentryLogUploader.java");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/SentryLogUploader.java");
        }
        String source = new String(Files.readAllBytes(path),
                StandardCharsets.UTF_8);
        int upload = source.indexOf("boolean transportSucceeded = false;");
        int finallyStart = source.indexOf("} finally {", upload);
        int cleanup = source.indexOf("LogShareZip.deleteArtifact(archive)", finallyStart);
        assertTrue(upload >= 0 && finallyStart > upload && cleanup > finallyStart);
        assertTrue(source.indexOf("LogShareZip.deleteArtifact(archive)", upload) == cleanup);
        assertTrue(source.indexOf("LogShareZip.deleteArtifact(archive)", cleanup + 1) < 0);
        assertTrue(source.substring(finallyStart, cleanup).contains(
                "if (transportSucceeded)"));
        assertTrue(source.contains("submissionResult.await(30_000L)"));
        assertFalse(source.contains("Sentry.flush("));
        assertTrue(source.contains("HintUtils.setTypeCheckHint(hint, submissionResult)"));
        int capture = source.indexOf("Sentry.captureEvent(event, hint)", upload);
        int accepted = source.indexOf("SentryId.EMPTY_ID.equals(eventId)", capture);
        int await = source.indexOf("submissionResult.await(30_000L)", accepted);
        int requireSuccess = source.indexOf("!submissionResult.isSuccess()", await);
        int confirmed = source.indexOf("transportSucceeded = true;", requireSuccess);
        int success = source.indexOf("return new Result(true, eventId.toString()", confirmed);
        assertTrue(capture > upload && accepted > capture && await > accepted
                && requireSuccess > await
                && confirmed > requireSuccess && success > confirmed);
    }

    @Test
    public void validZipAndHttpsDsnAreAccepted() throws Exception {
        File zip = zipWithOneEntry();
        try {
            assertEquals("", SentryLogUploader.validate("https://public@example/1", zip));
        } finally {
            zip.delete();
        }
    }

    @Test
    public void invalidDsnAndArchiveAreRejected() throws Exception {
        File zip = zipWithOneEntry();
        File text = Files.createTempFile("bydhud-sentry", ".txt").toFile();
        try (FileOutputStream output = new FileOutputStream(text)) {
            output.write("not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            assertEquals("Sentry is not configured", SentryLogUploader.validate("", zip));
            assertEquals("Archive is invalid",
                    SentryLogUploader.validate("https://public@example/1", text));
        } finally {
            zip.delete();
            text.delete();
        }
    }

    @Test
    public void archiveAboveTwentyMiBIsRejectedBeforeUpload() throws Exception {
        File zip = Files.createTempFile("bydhud-sentry-large", ".zip").toFile();
        try (RandomAccessFile output = new RandomAccessFile(zip, "rw")) {
            output.setLength(SentryLogUploader.MAX_ZIP_BYTES + 1L);
        }
        try {
            assertEquals("Archive must be between 1 byte and 20 MiB",
                    SentryLogUploader.validate("https://public@example/1", zip));
        } finally {
            zip.delete();
        }
    }

    private static File zipWithOneEntry() throws Exception {
        File zip = Files.createTempFile("bydhud-sentry", ".zip").toFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("20260803/logs/events.log"));
            output.write("test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return zip;
    }
}
