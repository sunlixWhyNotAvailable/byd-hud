package com.bydhud.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class LogcatRecorder {
    static final String STATUS_WAITING = "Очікування запису";
    static final String STATUS_RECORDING = "Йде запис логу";
    static final String STATUS_SAVING = "Збереження логу";
    static final String STATUS_SAVED = "Лог збережено";

    private static final String TAG = "BydHudLogcat";
    private static final SimpleDateFormat FILE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat LINE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static Process process;
    private static File activeFile;
    private static File lastSavedFile;
    private static String lastStatus = STATUS_WAITING;
    private static String lastDetail = "";

    private LogcatRecorder() {
    }

    static synchronized boolean isRecording() {
        if (process == null) {
            return false;
        }
        if (process.isAlive()) {
            return true;
        }
        process = null;
        activeFile = null;
        if (!STATUS_SAVED.equals(lastStatus)) {
            lastStatus = STATUS_WAITING;
        }
        return false;
    }

    static synchronized String statusText() {
        isRecording();
        StringBuilder text = new StringBuilder(lastStatus);
        File file = isRecording() ? activeFile : lastSavedFile;
        if (file != null) {
            text.append('\n').append(file.getAbsolutePath());
        }
        if (!lastDetail.isEmpty()) {
            text.append('\n').append(lastDetail);
        }
        return text.toString();
    }

    static synchronized Result start(Context context) {
        Context appContext = context.getApplicationContext();
        if (isRecording()) {
            lastStatus = STATUS_RECORDING;
            return Result.recording(activeFile, "already recording");
        }

        File file = new File(AppEventLogger.logDir(appContext),
                "logcat_" + timestampForFile() + ".txt");
        lastSavedFile = null;
        activeFile = file;
        lastStatus = STATUS_RECORDING;
        lastDetail = "";

        append(file, "=== BYD HUD logcat start " + timestampForLine() + " ===\n");
        append(file, "version=" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE + "\n");

        ClearResult clear = clearLogcat(file);
        append(file, "logcat -c exit=" + clear.exitCode + " " + clear.detail + "\n");
        AppEventLogger.event(appContext, "logcat_start file=" + file.getAbsolutePath()
                + " clearExit=" + clear.exitCode + " detail=" + clear.detail);

        try {
            ProcessBuilder builder = new ProcessBuilder("logcat", "-v", "threadtime");
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
            process = builder.start();
            lastDetail = "file=" + file.getName();
            return Result.recording(file, "clearExit=" + clear.exitCode);
        } catch (IOException e) {
            Log.e(TAG, "logcat start failed", e);
            append(file, "logcat start failed: " + e.getMessage() + "\n");
            AppEventLogger.event(appContext, "logcat_start_failed file=" + file.getAbsolutePath()
                    + " error=" + e.getMessage());
            process = null;
            activeFile = null;
            lastSavedFile = file;
            lastStatus = STATUS_WAITING;
            lastDetail = "start failed: " + e.getMessage();
            return Result.failed(file, e.getMessage());
        }
    }

    static synchronized Result stop(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isRecording()) {
            File file = lastSavedFile;
            lastStatus = file == null ? STATUS_WAITING : STATUS_SAVED;
            return Result.saved(file, file == null ? "not recording" : "already stopped");
        }

        File file = activeFile;
        lastStatus = STATUS_SAVING;
        lastDetail = "";
        append(file, "\n=== BYD HUD logcat stop requested " + timestampForLine() + " ===\n");

        int exitCode = Integer.MIN_VALUE;
        try {
            process.destroy();
            if (!process.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1500L, TimeUnit.MILLISECONDS);
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            append(file, "logcat stop interrupted\n");
        } catch (IllegalThreadStateException e) {
            append(file, "logcat still alive after stop\n");
        }

        append(file, "logcat process exit=" + exitCode + "\n");
        append(file, "=== BYD HUD logcat saved " + timestampForLine() + " ===\n");
        process = null;
        activeFile = null;
        lastSavedFile = file;
        lastStatus = STATUS_SAVED;
        lastDetail = "bytes=" + (file == null ? 0L : file.length());
        AppEventLogger.event(appContext, "logcat_saved file=" + file.getAbsolutePath()
                + " bytes=" + file.length() + " exit=" + exitCode);
        return Result.saved(file, lastDetail);
    }

    private static ClearResult clearLogcat(File file) {
        try {
            ProcessBuilder builder = new ProcessBuilder("logcat", "-c");
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
            Process clearProcess = builder.start();
            boolean finished = clearProcess.waitFor(2500L, TimeUnit.MILLISECONDS);
            if (!finished) {
                clearProcess.destroyForcibly();
                return new ClearResult(Integer.MIN_VALUE, "timeout");
            }
            return new ClearResult(clearProcess.exitValue(), "ok");
        } catch (IOException e) {
            Log.e(TAG, "logcat clear failed", e);
            return new ClearResult(Integer.MIN_VALUE, "io=" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ClearResult(Integer.MIN_VALUE, "interrupted");
        }
    }

    private static void append(File file, String text) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(text);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + file.getAbsolutePath(), e);
        }
    }

    private static String timestampForFile() {
        synchronized (FILE_FORMAT) {
            return FILE_FORMAT.format(new Date());
        }
    }

    private static String timestampForLine() {
        synchronized (LINE_FORMAT) {
            return LINE_FORMAT.format(new Date());
        }
    }

    static final class Result {
        final boolean ok;
        final boolean recording;
        final File file;
        final String detail;

        private Result(boolean ok, boolean recording, File file, String detail) {
            this.ok = ok;
            this.recording = recording;
            this.file = file;
            this.detail = detail == null ? "" : detail;
        }

        static Result recording(File file, String detail) {
            return new Result(true, true, file, detail);
        }

        static Result saved(File file, String detail) {
            return new Result(true, false, file, detail);
        }

        static Result failed(File file, String detail) {
            return new Result(false, false, file, detail);
        }
    }

    private static final class ClearResult {
        final int exitCode;
        final String detail;

        ClearResult(int exitCode, String detail) {
            this.exitCode = exitCode;
            this.detail = detail == null ? "" : detail;
        }
    }
}
