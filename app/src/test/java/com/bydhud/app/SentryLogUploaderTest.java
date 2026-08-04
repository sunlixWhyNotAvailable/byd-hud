package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
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
