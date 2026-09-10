package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.sentry.Attachment;
import io.sentry.Hint;
import io.sentry.SentryClient;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryEvent;
import io.sentry.SentryItemType;
import io.sentry.SentryOptions;
import io.sentry.hints.SubmissionResult;
import io.sentry.protocol.SentryId;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import io.sentry.util.HintUtils;

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
    public void reportSetsMessageAndOptionalFullCommentWithoutChangingFingerprint() {
        String uploadId = "0123abcd";
        String comment = "Full comment\nwith context";
        SentryLogReport report = new SentryLogReport(
                comment, "BYD HUD 3.3.0 — Full comment with context");
        SentryEvent event = SentryLogUploader.buildManualUploadEvent(
                report.getTitle(), "navigation_logs", "20260803", uploadId,
                report.getComment());

        assertEquals(report.getTitle(), event.getMessage().getMessage());
        assertEquals(comment, event.getExtra("user_comment"));
        assertEquals(Collections.singletonList("manual-navigation-upload:" + uploadId),
                event.getFingerprints());
    }

    @Test
    public void reportWithoutCommentOmitsUserCommentExtra() {
        SentryLogReport report = new SentryLogReport(
                null, "BYD HUD 3.3.0 — Logs — Sep 10, 12:00");
        SentryEvent event = SentryLogUploader.buildManualUploadEvent(
                report.getTitle(), "navigation_logs", "20260803", "0123abcd",
                report.getComment());

        assertNull(event.getExtra("user_comment"));
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
    public void submissionTrackerWaitsForLateTransportResultWithoutADeadline() throws Exception {
        SentryLogUploader.SubmissionResultTracker rejected =
                new SentryLogUploader.SubmissionResultTracker();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                rejected.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        waiting.await();
        Thread.sleep(25L);
        assertTrue(waiter.isAlive());
        rejected.setResult(false);
        waiter.join(1_000L);
        assertFalse(waiter.isAlive());
        assertFalse(rejected.isSuccess());

        SentryLogUploader.SubmissionResultTracker accepted =
                new SentryLogUploader.SubmissionResultTracker();
        accepted.setResult(true);
        accepted.await();
        assertTrue(accepted.isSuccess());
    }

    @Test
    public void everyTerminalUploadDeletesItsArchiveAndUsesTransportCallback() throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/SentryLogUploader.java");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/SentryLogUploader.java");
        }
        String source = new String(Files.readAllBytes(path),
                StandardCharsets.UTF_8);
        int upload = source.indexOf("SentryClient client = null;");
        int finallyStart = source.indexOf("} finally {", upload);
        int cleanup = source.indexOf("LogShareZip.deleteArtifact(archive)", finallyStart);
        assertTrue(upload >= 0 && finallyStart > upload && cleanup > finallyStart);
        assertTrue(source.indexOf("LogShareZip.deleteArtifact(archive)", upload) == cleanup);
        assertTrue(source.indexOf("LogShareZip.deleteArtifact(archive)", cleanup + 1) < 0);
        int guardedClientClose = source.indexOf(
                "if (client != null) client.close(false);", finallyStart);
        assertTrue(guardedClientClose > finallyStart && cleanup > guardedClientClose);
        assertTrue(source.contains("submissionResult.await()"));
        assertFalse(source.contains("submissionResult.await(30_000L)"));
        assertFalse(source.contains("Sentry.flush("));
        assertFalse(source.contains("SentryAndroid.init("));
        assertFalse(source.contains("Sentry.close("));
        assertTrue(source.contains("HintUtils.setTypeCheckHint(hint, submissionResult)"));
        int capture = source.indexOf("client.captureEvent(event, hint)", upload);
        int accepted = source.indexOf("SentryId.EMPTY_ID.equals(eventId)", capture);
        int await = source.indexOf("submissionResult.await()", accepted);
        int requireSuccess = source.indexOf("!submissionResult.isSuccess()", await);
        int success = source.indexOf("return new Result(true, eventId.toString()", requireSuccess);
        assertTrue(capture > upload && accepted > capture && await > accepted
                && requireSuccess > await && success > requireSuccess);
    }

    @Test
    public void parallelClientsKeepEnvelopesAttachmentsAndResultsIsolated() throws Exception {
        File firstAttachment = Files.createTempFile("bydhud-first", ".zip").toFile();
        File secondAttachment = Files.createTempFile("bydhud-second", ".zip").toFile();
        Files.write(firstAttachment.toPath(), "first archive".getBytes(StandardCharsets.UTF_8));
        Files.write(secondAttachment.toPath(), "second archive".getBytes(StandardCharsets.UTF_8));

        SentryOptions firstOptions = SentryLogUploader.buildOptions("https://public@example/1");
        SentryOptions secondOptions = SentryLogUploader.buildOptions("https://public@example/1");
        assertEquals(SentryLogUploader.MAX_ZIP_BYTES, firstOptions.getMaxAttachmentSize());
        FakeTransport firstTransport = new FakeTransport(firstOptions);
        FakeTransport secondTransport = new FakeTransport(secondOptions);
        firstOptions.setTransportFactory((options, request) -> firstTransport);
        secondOptions.setTransportFactory((options, request) -> secondTransport);
        SentryClient firstClient = new SentryClient(firstOptions);
        SentryClient secondClient = new SentryClient(secondOptions);
        SentryLogUploader.SubmissionResultTracker firstResult =
                new SentryLogUploader.SubmissionResultTracker();
        SentryLogUploader.SubmissionResultTracker secondResult =
                new SentryLogUploader.SubmissionResultTracker();
        Thread secondWaiter = null;

        try {
            SentryId firstId = firstClient.captureEvent(
                    SentryLogUploader.buildManualUploadEvent(
                            "first report", "navigation_logs", "20260908", "1111aaaa"),
                    hint(firstAttachment, firstResult));
            assertFalse(SentryId.EMPTY_ID.equals(firstId));
            assertEnvelope(firstTransport.envelope, firstOptions, "1111aaaa", "20260908",
                    "first report", firstAttachment, "first archive");

            firstClient.close(false);
            assertTrue(firstTransport.closed);
            assertFalse(secondTransport.closed);

            SentryId secondId = secondClient.captureEvent(
                    SentryLogUploader.buildManualUploadEvent(
                            "second report", "navigation_logs", "20260909", "2222bbbb"),
                    hint(secondAttachment, secondResult));
            assertFalse(SentryId.EMPTY_ID.equals(secondId));
            assertFalse(firstId.equals(secondId));
            assertEnvelope(secondTransport.envelope, secondOptions, "2222bbbb", "20260909",
                    "second report", secondAttachment, "second archive");

            CountDownLatch secondWaiting = new CountDownLatch(1);
            secondWaiter = new Thread(() -> {
                secondWaiting.countDown();
                try {
                    secondResult.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            secondWaiter.start();
            secondWaiting.await();
            firstTransport.complete(false);
            firstResult.await();
            assertFalse(firstResult.isSuccess());
            assertTrue(secondWaiter.isAlive());
            secondTransport.complete(true);
            secondWaiter.join(1_000L);
            assertFalse(secondWaiter.isAlive());
            assertTrue(secondResult.isSuccess());
        } finally {
            if (secondWaiter != null && secondWaiter.isAlive()) {
                secondWaiter.interrupt();
                secondWaiter.join(1_000L);
            }
            if (!firstTransport.closed) firstClient.close(true);
            if (!secondTransport.closed) secondClient.close(true);
            firstAttachment.delete();
            secondAttachment.delete();
        }
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
    public void archiveAtOrAboveThirtyNineMillionBytesIsRejectedBeforeUpload() throws Exception {
        File zip = Files.createTempFile("bydhud-sentry-large", ".zip").toFile();
        try {
            try (RandomAccessFile output = new RandomAccessFile(zip, "rw")) {
                output.setLength(SentryLogUploader.MAX_ZIP_BYTES);
            }
            assertEquals("Archive must be between 1 byte and 38,999,999 bytes",
                    SentryLogUploader.validate("https://public@example/1", zip));
            try (RandomAccessFile output = new RandomAccessFile(zip, "rw")) {
                output.setLength(SentryLogUploader.MAX_ZIP_BYTES + 1L);
            }
            assertEquals("Archive must be between 1 byte and 38,999,999 bytes",
                    SentryLogUploader.validate("https://public@example/1", zip));
        } finally {
            zip.delete();
        }
    }

    private static Hint hint(File attachment,
            SentryLogUploader.SubmissionResultTracker result) {
        Hint hint = new Hint();
        hint.addAttachment(new Attachment(
                attachment.getAbsolutePath(), attachment.getName(), "application/zip"));
        HintUtils.setTypeCheckHint(hint, result);
        return hint;
    }

    private static void assertEnvelope(SentryEnvelope envelope, SentryOptions options,
            String uploadId, String selectedDays, String title, File attachment,
            String attachmentBody) throws Exception {
        assertTrue(envelope != null);
        boolean foundEvent = false;
        boolean foundAttachment = false;
        for (SentryEnvelopeItem item : envelope.getItems()) {
            if (item.getHeader().getType() == SentryItemType.Event) {
                SentryEvent event = item.getEvent(options.getSerializer());
                assertEquals(uploadId, event.getTag("upload_id"));
                assertEquals(selectedDays, event.getTag("selected_days"));
                assertEquals(title, event.getMessage().getMessage());
                foundEvent = true;
            } else if (item.getHeader().getType() == SentryItemType.Attachment) {
                assertEquals(attachment.getName(), item.getHeader().getFileName());
                assertEquals("application/zip", item.getHeader().getContentType());
                assertEquals(attachmentBody,
                        new String(item.getData(), StandardCharsets.UTF_8));
                foundAttachment = true;
            }
        }
        assertTrue(foundEvent);
        assertTrue(foundAttachment);
    }

    private static final class FakeTransport implements ITransport {
        private final RateLimiter rateLimiter;
        private SentryEnvelope envelope;
        private Hint hint;
        private boolean closed;

        FakeTransport(SentryOptions options) {
            rateLimiter = new RateLimiter(options);
        }

        @Override
        public void send(SentryEnvelope envelope, Hint hint) {
            assertFalse(closed);
            assertNull(this.envelope);
            this.envelope = envelope;
            this.hint = hint;
        }

        void complete(boolean success) {
            Object typedHint = HintUtils.getSentrySdkHint(hint);
            assertTrue(typedHint instanceof SubmissionResult);
            ((SubmissionResult) typedHint).setResult(success);
        }

        @Override
        public void flush(long timeoutMillis) {
        }

        @Override
        public RateLimiter getRateLimiter() {
            return rateLimiter;
        }

        @Override
        public void close() throws IOException {
            close(false);
        }

        @Override
        public void close(boolean isRestarting) throws IOException {
            if (closed) return;
            closed = true;
            rateLimiter.close();
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
