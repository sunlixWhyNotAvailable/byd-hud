package com.bydhud.app;

// Records one direct-navigation start without coupling channel code to storage details.

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class DirectSessionLog {
    private static final String EVENTS_FILE = "events.jsonl";
    private static final String RAW_FILE = "raw.jsonl";
    private static final SimpleDateFormat SESSION_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private final String source;
    private final String sessionName;
    private final File dir;
    private final WazeCaptureDebugWriter writer;
    private final long startedElapsedMs;
    private final Set<String> scheduledArtifacts = new HashSet<>();
    private boolean ended;

    private DirectSessionLog(
            String source,
            String sessionName,
            File dir,
            long startedElapsedMs) {
        this.source = source;
        this.sessionName = sessionName;
        this.dir = dir;
        this.writer = WazeCaptureDebugWriter.get();
        this.startedElapsedMs = startedElapsedMs;
    }

    static DirectSessionLog start(Context context, String source, String reason) {
        if (context == null || !isDirectSource(source)) {
            return null;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        long elapsedMs = SystemClock.elapsedRealtime();
        long wallMs = System.currentTimeMillis();
        String sessionName = sessionName(wallMs);
        NavigationLogStorage.SessionPath path =
                NavigationLogStorage.openDirectSession(app, source, sessionName);
        DirectSessionLog session = new DirectSessionLog(
                source, sessionName, path.localDir, elapsedMs);
        if (!session.writer.appendDirectLine(
                session.dir,
                EVENTS_FILE,
                session.eventLine("start", reason, elapsedMs, wallMs, 0L))) {
            NavigationLogStorage.closeDirectSession(session.dir);
            return null;
        }
        return session;
    }

    synchronized boolean event(String event) {
        return event(event, "");
    }

    synchronized boolean event(String event, String detail) {
        if (ended || event == null || event.isEmpty()) {
            return false;
        }
        long elapsedMs = SystemClock.elapsedRealtime();
        long wallMs = System.currentTimeMillis();
        return writer.appendDirectLine(
                dir, EVENTS_FILE, eventLine(event, detail, elapsedMs, wallMs, -1L));
    }

    synchronized boolean raw(String raw) {
        if (ended || raw == null) {
            return false;
        }
        long elapsedMs = SystemClock.elapsedRealtime();
        long wallMs = System.currentTimeMillis();
        return writer.appendDirectRaw(dir, RAW_FILE, "{"
                + NavCaptureStore.timeFields(elapsedMs, wallMs)
                + ",\"source\":\"" + NavCaptureStore.esc(source) + "\""
                + ",\"session\":\"" + NavCaptureStore.esc(sessionName) + "\""
                + ",\"raw\":\"" + NavCaptureStore.esc(raw) + "\""
                + "}");
    }

    synchronized String saveArtifact(String kind, byte[] png) {
        if (ended || !NavigationLogStorage.WAZE_DIRECT_DIR.equals(source)) {
            return "";
        }
        String fileName = NavCaptureStore.directArtifactFileName(kind, png);
        if (fileName.isEmpty()) {
            return "";
        }
        if (scheduledArtifacts.contains(fileName)) return fileName;
        scheduledArtifacts.add(fileName);
        if (!writer.saveDirectArtifact(dir, fileName, png)) {
            scheduledArtifacts.remove(fileName);
            return "";
        }
        return fileName;
    }

    synchronized boolean end(String reason) {
        if (ended) {
            return false;
        }
        ended = true;
        long elapsedMs = SystemClock.elapsedRealtime();
        long wallMs = System.currentTimeMillis();
        boolean posted = writer.endDirectSession(
                dir,
                EVENTS_FILE,
                eventLine(
                        "end",
                        reason,
                        elapsedMs,
                        wallMs,
                        Math.max(0L, elapsedMs - startedElapsedMs)));
        if (!posted) {
            NavigationLogStorage.closeDirectSession(dir);
        }
        return posted;
    }

    File sessionDir() {
        return dir;
    }

    private String eventLine(
            String event,
            String detail,
            long elapsedMs,
            long wallMs,
            long durationMs) {
        return "{"
                + NavCaptureStore.timeFields(elapsedMs, wallMs)
                + ",\"source\":\"" + NavCaptureStore.esc(source) + "\""
                + ",\"session\":\"" + NavCaptureStore.esc(sessionName) + "\""
                + ",\"event\":\"" + NavCaptureStore.esc(event) + "\""
                + ",\"detail\":\"" + NavCaptureStore.esc(detail) + "\""
                + (durationMs >= 0L ? ",\"durationMs\":" + durationMs : "")
                + "}";
    }

    private static boolean isDirectSource(String source) {
        return NavigationLogStorage.WAZE_DIRECT_DIR.equals(source)
                || NavigationLogStorage.GMAPS_DIRECT_DIR.equals(source);
    }

    private static String sessionName(long wallMs) {
        synchronized (SESSION_FORMAT) {
            return SESSION_FORMAT.format(new Date(wallMs));
        }
    }
}
