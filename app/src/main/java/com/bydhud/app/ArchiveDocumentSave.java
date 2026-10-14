package com.bydhud.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/** Process-owned streaming copy into a document created by ACTION_CREATE_DOCUMENT. */
final class ArchiveDocumentSave {
    static final String KEY_PICKER = "archive_document_picker";
    private static final String KEY_COPY = "archive_document_copy";
    private static final String OUTCOME_PREFIX = "archive_document_outcome_";
    private static final String ATTEMPT_PREFIX = "archive_document_attempt_";
    private static final String CLEANUP_PREFIX = "archive_document_cleanup_";
    private static final String ORPHAN_PICKER_PREFIX = "archive_document_orphan_picker_";
    private static final String PREFS = "bydhud_archive_documents";
    private static final int URI_ACCESS_FLAGS = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final long OUTCOME_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final int COPY_ACTIVE = 0;
    private static final int COPY_CANCELLED = 1;
    private static final int COPY_TERMINAL = 2;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "archive-document-save");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<CopyWork> RESERVED = new AtomicReference<>();
    private static final AtomicReference<CopyWork> ACTIVE_COPY = new AtomicReference<>();
    private static final java.util.Set<String> RECOVERING_ATTEMPTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String PROCESS_SESSION = UUID.randomUUID().toString();
    private static volatile Context appContext;
    private static volatile boolean recoveryScheduled;
    private static volatile boolean recoveryPending;

    static final class PickerRequest {
        final String id;
        final String attemptToken;
        final boolean configuration;
        final String displayName;
        final String processSession;
        final String phase;
        final Uri uri;
        final int resultFlags;
        final int persistedFlags;

        PickerRequest(JSONObject value) throws JSONException {
            id = value.getString("id");
            attemptToken = value.getString("attemptToken");
            configuration = value.getBoolean("configuration");
            displayName = value.optString("displayName", "archive.zip");
            processSession = value.optString("session");
            phase = value.optString("phase");
            String rawUri = value.optString("uri");
            uri = rawUri.isEmpty() ? null : Uri.parse(rawUri);
            resultFlags = value.optInt("resultFlags", 0);
            persistedFlags = value.optInt("persistedFlags", 0);
        }
    }

    private static final class CopyWork {
        final Context app;
        final String id;
        final String attemptToken;
        final Uri uri;
        final boolean configuration;
        final ArchiveDocumentCopy.Plan plan;
        final String displayName;
        final long expiresAtMs;
        final ArtifactLocation location;
        final List<File> protectedBases;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger terminalState = new AtomicInteger(COPY_ACTIVE);
        final Object terminalLock = new Object();
        volatile Thread worker;
        volatile OutputStream output;
        volatile boolean copying;
        volatile boolean ownerLockHeld;
        volatile boolean legacyProtectionHeld;
        volatile boolean sourcesValidated;

        CopyWork(Context app, String id, String attemptToken, Uri uri, boolean configuration,
                ArchiveDocumentCopy.Plan plan, String displayName, long expiresAtMs,
                ArtifactLocation location, List<File> protectedBases) {
            this.app = app;
            this.id = id;
            this.attemptToken = attemptToken;
            this.uri = uri;
            this.configuration = configuration;
            this.plan = plan;
            this.displayName = displayName;
            this.expiresAtMs = expiresAtMs;
            this.location = location;
            this.protectedBases = protectedBases;
        }
    }

    private static final class ArtifactLocation {
        final File job;
        final File lockFile;

        ArtifactLocation(File job, File lockFile) {
            this.job = job;
            this.lockFile = lockFile;
        }
    }

    private static final class ExpiredBeforeCopyException extends IOException {
        ExpiredBeforeCopyException() { super("archive expired before copy started"); }
    }

    private ArchiveDocumentSave() { }

    static String processSession() { return PROCESS_SESSION; }

    static boolean queuePicker(String id, boolean configuration, String displayName) {
        return queuePicker(appContext, id, configuration, displayName);
    }

    static boolean queuePicker(Context context, String id, boolean configuration, String displayName) {
        Context app = appContext(context);
        if (app == null || !validId(id)) return false;
        synchronized (LOCK) {
            ensureRecoveryQueuedLocked(app);
            SharedPreferences prefs = preferences(app);
            String prior = prefs.getString(KEY_PICKER, "");
            if (!prior.isEmpty()) {
                try {
                    PickerRequest existing = new PickerRequest(new JSONObject(prior));
                    if (existing.id.equals(id)) {
                        if (existing.configuration != configuration) return false;
                        if (existing.processSession.equals(PROCESS_SESSION)) return true;
                        if ("QUEUED".equals(existing.phase)) {
                            JSONObject refreshed = new JSONObject(prior).put("session", PROCESS_SESSION);
                            return prefs.edit().putString(KEY_PICKER, refreshed.toString()).commit();
                        }
                        // A picker already launched by the prior process may still return a result.
                        return false;
                    }
                    return false;
                } catch (JSONException malformed) {
                    return false;
                }
            }
            if (RESERVED.get() != null || ACTIVE_COPY.get() != null) return false;
            try {
                JSONObject request = new JSONObject().put("id", id)
                        .put("attemptToken", UUID.randomUUID().toString())
                        .put("configuration", configuration)
                        .put("displayName", safeDisplayName(displayName))
                        .put("session", PROCESS_SESSION).put("phase", "QUEUED")
                        .put("createdAt", System.currentTimeMillis());
                // An operation id may be reused for a deliberate retry. Clear its previous
                // terminal outcome as part of publishing the new attempt so startup recovery
                // cannot mistake a later interrupted copy for that old result.
                String attemptToken = request.getString("attemptToken");
                return prefs.edit().remove(outcomeKey(id))
                        .putString(attemptKey(id), attemptToken)
                        .putString(KEY_PICKER, request.toString()).commit();
            } catch (JSONException impossible) { return false; }
        }
    }

    /** Move a prior-process launch aside after its exact result key has been registered. */
    static void abandonPriorProcessPicker(Context context, PickerRequest expected) {
        Context app = appContext(context);
        if (app == null || expected == null) return;
        PickerRequest abandoned = null;
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(app);
            String raw = prefs.getString(KEY_PICKER, "");
            try {
                PickerRequest request = new PickerRequest(new JSONObject(raw));
                if (!sameAttempt(request, expected) || !"LAUNCHED".equals(request.phase)
                        || PROCESS_SESSION.equals(request.processSession)) return;
                JSONObject orphan = new JSONObject(raw).put("phase", "ORPHANED")
                        .put("abandonedAt", System.currentTimeMillis());
                SharedPreferences.Editor editor = prefs.edit()
                        .putString(orphanPickerKey(request.attemptToken), orphan.toString())
                        .remove(KEY_PICKER);
                if (isLatestAttempt(prefs, request.id, request.attemptToken)) {
                    JSONObject outcome = new JSONObject().put("id", request.id)
                            .put("attemptToken", request.attemptToken).put("status", "INTERRUPTED")
                            .put("deleteFailed", false).put("at", System.currentTimeMillis());
                    editor.putString(outcomeKey(request.id), outcome.toString());
                }
                if (editor.commit()) abandoned = request;
            } catch (JSONException | RuntimeException malformed) { return; }
        }
        if (abandoned != null) {
            reportPickerCleanup(abandoned.id, abandoned.configuration, false, abandoned.attemptToken);
            AppEventLogger.event(app, "archive_document_picker_abandoned id=" + abandoned.id);
        }
    }

    static List<PickerRequest> orphanedPickers(Context context) {
        Context app = appContext(context);
        if (app == null) return Collections.emptyList();
        List<PickerRequest> result = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : preferences(app).getAll().entrySet()) {
            if (!entry.getKey().startsWith(ORPHAN_PICKER_PREFIX)) continue;
            try {
                PickerRequest request = new PickerRequest(new JSONObject(String.valueOf(entry.getValue())));
                if ("ORPHANED".equals(request.phase)) result.add(request);
            } catch (JSONException ignored) { }
        }
        return result;
    }

    static String activityResultKey(PickerRequest request) {
        return "bydhud.archive.document." + request.attemptToken;
    }

    static boolean isCurrentPicker(Context context, PickerRequest expected) {
        PickerRequest current = pendingPicker(context);
        return current != null && sameAttempt(current, expected);
    }

    static boolean isLatestAttempt(String id, String attemptToken) {
        if (!validId(id) || attemptToken == null || attemptToken.isEmpty()) return false;
        Context app = appContext;
        return app != null && attemptToken.equals(preferences(app).getString(attemptKey(id), ""));
    }

    /** Retain log artifacts only while this process owns a picker or reserved copy attempt. */
    static boolean isPendingLogSave(String id) {
        if (!validId(id)) return false;
        CopyWork reserved = RESERVED.get();
        if (reserved != null && !reserved.configuration && reserved.id.equals(id)) return true;
        CopyWork active = ACTIVE_COPY.get();
        if (active != null && !active.configuration && active.id.equals(id)) return true;
        Context app = appContext;
        PickerRequest picker = app == null ? null : pendingPicker(app);
        return picker != null && !picker.configuration && id.equals(picker.id)
                && PROCESS_SESSION.equals(picker.processSession)
                && ("QUEUED".equals(picker.phase) || "LAUNCHED".equals(picker.phase)
                || "RESULT".equals(picker.phase));
    }

    static PickerRequest pendingPicker(Context context) {
        Context app = appContext(context);
        if (app == null) return null;
        String raw = preferences(app).getString(KEY_PICKER, "");
        if (raw.isEmpty()) return null;
        try { return new PickerRequest(new JSONObject(raw)); }
        catch (JSONException malformed) { return null; }
    }

    static boolean markPickerLaunched(Context context, PickerRequest expected) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id)) return false;
        SharedPreferences prefs = preferences(app);
        synchronized (LOCK) {
            String raw = prefs.getString(KEY_PICKER, "");
            try {
                JSONObject request = new JSONObject(raw);
                if (!sameAttempt(new PickerRequest(request), expected)
                        || !PROCESS_SESSION.equals(request.optString("session"))
                        || !"QUEUED".equals(request.optString("phase"))) return false;
                request.put("phase", "LAUNCHED");
                return prefs.edit().putString(KEY_PICKER, request.toString()).commit();
            } catch (JSONException malformed) { return false; }
        }
    }

    static boolean recordPickerResult(Context context, PickerRequest expected, Uri uri, int resultFlags) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id) || !validCreatedDocumentUri(uri)) return false;
        SharedPreferences prefs = preferences(app);
        synchronized (LOCK) {
            String raw = prefs.getString(KEY_PICKER, "");
            try {
                JSONObject request = new JSONObject(raw);
                if (!sameAttempt(new PickerRequest(request), expected)
                        || !"LAUNCHED".equals(request.optString("phase")))
                    return false;
                request.put("phase", "RESULT").put("uri", uri.toString())
                        .put("resultFlags", resultFlags & URI_ACCESS_FLAGS)
                        .put("resultAt", System.currentTimeMillis());
                return prefs.edit().putString(KEY_PICKER, request.toString()).commit();
            } catch (JSONException malformed) { return false; }
        }
    }

    static boolean persistPickerPermission(Context context, PickerRequest expected, Uri uri, int resultFlags) {
        Context app = appContext(context);
        if (app == null || expected == null || uri == null || !validId(expected.id)) return false;
        int access = resultFlags & URI_ACCESS_FLAGS;
        if ((access & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) return false;
        int newlyAcquired = 0;
        int already = 0;
        try {
            for (android.content.UriPermission permission : app.getContentResolver().getPersistedUriPermissions()) {
                if (uri.equals(permission.getUri())) {
                    if (permission.isReadPermission()) already |= android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    if (permission.isWritePermission()) already |= android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                }
            }
            newlyAcquired = access & ~already;
            if (newlyAcquired != 0)
                app.getContentResolver().takePersistableUriPermission(uri, newlyAcquired);
            int persistedForRequest = newlyAcquired;
            SharedPreferences prefs = preferences(app);
            synchronized (LOCK) {
                String raw = prefs.getString(KEY_PICKER, "");
                JSONObject request = new JSONObject(raw);
                if (!sameAttempt(new PickerRequest(request), expected)
                        || !"RESULT".equals(request.optString("phase"))
                        || !uri.toString().equals(request.optString("uri"))) {
                    releasePermission(app, uri, persistedForRequest);
                    return false;
                }
                request.put("persistedFlags", persistedForRequest);
                if (prefs.edit().putString(KEY_PICKER, request.toString()).commit()) return true;
                releasePermission(app, uri, persistedForRequest);
                return false;
            }
        } catch (Exception failure) {
            // If takePersistableUriPermission succeeded but recording it failed, do not leak this grant.
            releasePermission(app, uri, newlyAcquired);
            return false;
        }
    }

    static boolean clearPicker(Context context, PickerRequest expected) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id)) return false;
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(app);
            try {
                JSONObject request = new JSONObject(prefs.getString(KEY_PICKER, ""));
                if (!sameAttempt(new PickerRequest(request), expected)) return false;
                return prefs.edit().remove(KEY_PICKER).commit();
            } catch (JSONException malformed) { return false; }
        }
    }

    static boolean clearOrphanedPicker(Context context, PickerRequest expected) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id)) return false;
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(app);
            String key = orphanPickerKey(expected.attemptToken);
            try {
                JSONObject request = new JSONObject(prefs.getString(key, ""));
                if (!sameAttempt(new PickerRequest(request), expected)) return false;
                return prefs.edit().remove(key).commit();
            } catch (JSONException malformed) { return false; }
        }
    }

    static boolean clearOrphanedPicker(Context context, String id, String attemptToken) {
        if (!validId(id) || attemptToken == null || attemptToken.isEmpty()) return false;
        Context app = appContext(context);
        if (app == null) return false;
        String key = orphanPickerKey(attemptToken);
        synchronized (LOCK) {
            JSONObject request = parse(preferences(app).getString(key, ""));
            if (!sameAttempt(request, id, attemptToken)) return false;
            return preferences(app).edit().remove(key).commit();
        }
    }

    static boolean request(Context context, String id, Uri destination, boolean configuration,
            List<File> sources, String displayName, long expiresAtMs) {
        Context app = appContext(context);
        if (app == null || !validId(id) || !validCreatedDocumentUri(destination)
                || (configuration && expiresAtMs <= System.currentTimeMillis())) return false;
        PickerRequest picker = pendingPicker(app);
        if (picker == null || !id.equals(picker.id) || !PROCESS_SESSION.equals(picker.processSession)
                || picker.configuration != configuration || !"RESULT".equals(picker.phase)
                || !destination.equals(picker.uri)) return false;
        ArchiveDocumentCopy.Plan plan;
        ArtifactLocation location;
        List<File> protectedBases;
        try {
            plan = ArchiveDocumentCopy.prepare(sources);
            location = locateArtifacts(app, id, configuration, plan);
            protectedBases = configuration ? legacyConfigurationBases(plan, location) : Collections.emptyList();
            if (configuration && location.job == null && protectedBases.isEmpty())
                throw new IOException("configuration archive protection unavailable");
        } catch (Exception invalidSource) { return false; }
        CopyWork work = new CopyWork(app, id, picker.attemptToken, destination, configuration, plan,
                safeDisplayName(displayName), expiresAtMs, location, protectedBases);
        synchronized (LOCK) {
            ensureRecoveryQueuedLocked(app);
            if (!RESERVED.compareAndSet(null, work)) return false;
            try { WORKER.execute(() -> runCopy(work)); }
            catch (RuntimeException rejected) {
                RESERVED.compareAndSet(work, null);
                return false;
            }
        }
        return true;
    }

    static boolean cancel(Context context, String id) {
        CopyWork work = RESERVED.get();
        if (work == null || !work.id.equals(id)) return false;
        OutputStream output;
        synchronized (work.terminalLock) {
            if (work.terminalState.get() == COPY_TERMINAL) return false;
            work.cancelled.set(true);
            work.terminalState.set(COPY_CANCELLED);
            output = work.output;
        }
        Thread running = work.worker;
        if (running != null) running.interrupt();
        if (output != null) {
            Thread close = new Thread(() -> closeQuietly(output), "archive-document-cancel-close");
            close.setDaemon(true);
            close.start();
        }
        return true;
    }

    /** True only while validated sources, an open document stream, and the shared owner lock are live. */
    static boolean isCopying(String id) {
        CopyWork active = ACTIVE_COPY.get();
        return active != null && active.id.equals(id) && active.copying && active.output != null
                && active.sourcesValidated
                && (!active.configuration || active.ownerLockHeld || active.legacyProtectionHeld);
    }

    /** Startup-recovery fence for workflow restoration only; never retention or expiry evidence. */
    static boolean isRecovering(String id) {
        if (!validId(id)) return false;
        Context app = appContext;
        if (app == null) return false;
        SharedPreferences prefs = preferences(app);
        String currentToken = prefs.getString(attemptKey(id), "");
        if (currentToken.isEmpty()) return false;
        if (RECOVERING_ATTEMPTS.contains(recoveryKey(id, currentToken))) return true;
        if (!recoveryPending) return false;
        String copy = prefs.getString(KEY_COPY, "");
        if (!copy.isEmpty()) {
            try {
                JSONObject value = new JSONObject(copy);
                if (sameAttempt(value, id, currentToken)
                        && !PROCESS_SESSION.equals(value.optString("session"))) return true;
            } catch (JSONException ignored) { }
        }
        PickerRequest picker = pendingPicker(app);
        return picker != null && sameAttempt(picker, id, currentToken)
                && "RESULT".equals(picker.phase)
                && !PROCESS_SESSION.equals(picker.processSession);
    }

    static boolean wasInterrupted(String id) { return hasOutcome(id, "INTERRUPTED"); }
    static boolean wasSaved(String id) { return hasOutcome(id, "SAVED"); }

    static String recoveryDetail(String id) {
        JSONObject outcome = readOutcome(id);
        return outcome != null && outcome.optBoolean("deleteFailed", false)
                ? "Document copy interrupted; partial document could not be removed"
                : "Document copy interrupted";
    }

    static void cleanupCreatedDocument(Context context, PickerRequest expected, Uri uri,
            int persistedFlags, boolean reportWorkflowFailure) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id)
                || !validCreatedDocumentUri(uri)) return;
        JSONObject cleanup;
        try {
            cleanup = new JSONObject().put("id", expected.id)
                    .put("attemptToken", expected.attemptToken).put("uri", uri.toString())
                    .put("persistedFlags", persistedFlags & URI_ACCESS_FLAGS)
                    .put("configuration", expected.configuration)
                    .put("reportWorkflowFailure", reportWorkflowFailure)
                    .put("session", PROCESS_SESSION).put("createdAt", System.currentTimeMillis());
        } catch (JSONException impossible) { return; }
        SharedPreferences prefs = preferences(app);
        String key = cleanupKey(expected.id, expected.attemptToken);
        if (!prefs.edit().putString(key, cleanup.toString()).commit()) {
            // The Activity result grant is still live here; make one best-effort cleanup attempt.
            WORKER.execute(() -> {
                boolean deleted = deleteAndRelease(app, uri, persistedFlags & URI_ACCESS_FLAGS);
                clearPicker(app, expected);
                clearOrphanedPicker(app, expected);
                if (!deleted) AppEventLogger.event(app,
                        "archive_document_cleanup_failed id=" + expected.id);
                if (reportWorkflowFailure)
                    reportPickerCleanup(expected.id, expected.configuration, !deleted,
                            expected.attemptToken);
            });
            return;
        }
        clearPicker(app, expected);
        clearOrphanedPicker(app, expected);
        synchronized (LOCK) {
            ensureRecoveryQueuedLocked(app);
            WORKER.execute(() -> processCleanupRecord(app, key, cleanup));
        }
    }

    static void cleanupOrphanedCreatedDocument(Context context, PickerRequest expected,
            Uri uri, int resultFlags) {
        Context app = appContext(context);
        if (app == null || expected == null || !validId(expected.id)
                || !validCreatedDocumentUri(uri)) return;
        int access = resultFlags & URI_ACCESS_FLAGS;
        int preexistingFlags;
        int grantIntentFlags;
        try {
            preexistingFlags = persistedUriFlags(app, uri);
            grantIntentFlags = (access & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
                    ? 0 : access & ~preexistingFlags;
        } catch (RuntimeException unavailable) {
            // If existing grants cannot be inspected, avoid taking or releasing any grant.
            preexistingFlags = URI_ACCESS_FLAGS;
            grantIntentFlags = 0;
        }
        JSONObject cleanup;
        try {
            cleanup = new JSONObject().put("id", expected.id)
                    .put("attemptToken", expected.attemptToken).put("uri", uri.toString())
                    .put("persistedFlags", 0).put("preexistingFlags", preexistingFlags)
                    .put("grantIntentFlags", grantIntentFlags)
                    .put("configuration", expected.configuration)
                    .put("reportWorkflowFailure", true)
                    .put("session", PROCESS_SESSION).put("createdAt", System.currentTimeMillis());
        } catch (JSONException impossible) { return; }
        String key = cleanupKey(expected.id, expected.attemptToken);
        SharedPreferences prefs = preferences(app);
        boolean durable;
        try { durable = prefs.edit().putString(key, cleanup.toString()).commit(); }
        catch (RuntimeException writeFailure) { durable = false; }
        if (!durable) {
            // The result grant is still live; make one bounded cleanup attempt without acquiring a new grant.
            WORKER.execute(() -> {
                boolean deleted = deleteCreatedDocument(app, uri);
                clearPicker(app, expected);
                clearOrphanedPicker(app, expected);
                if (!deleted) {
                    try { AppEventLogger.event(app, "archive_document_cleanup_failed id=" + expected.id); }
                    catch (RuntimeException ignored) { }
                    MainActivity.showArchiveDocumentCleanupWarning(app);
                }
            });
            return;
        }

        if (grantIntentFlags != 0) {
            try { app.getContentResolver().takePersistableUriPermission(uri, grantIntentFlags); }
            catch (Exception ignored) { }
        }
        int acquiredFlags = 0;
        try { acquiredFlags = persistedUriFlags(app, uri) & grantIntentFlags & ~preexistingFlags; }
        catch (RuntimeException ignored) { }
        try {
            cleanup.put("persistedFlags", acquiredFlags);
            prefs.edit().putString(key, cleanup.toString()).commit();
        } catch (JSONException | RuntimeException ignored) { }
        clearPicker(app, expected);
        clearOrphanedPicker(app, expected);
        synchronized (LOCK) {
            ensureRecoveryQueuedLocked(app);
            WORKER.execute(() -> processCleanupRecord(app, key, cleanup));
        }
    }

    static void recoverAbandoned(Context context) {
        Context app = appContext(context);
        if (app == null) return;
        synchronized (LOCK) { ensureRecoveryQueuedLocked(app); }
    }

    private static void ensureRecoveryQueuedLocked(Context app) {
        if (recoveryScheduled) return;
        recoveryScheduled = true;
        recoveryPending = true;
        WORKER.execute(() -> recover(app));
    }

    private static void recover(Context app) {
        try {
            SharedPreferences prefs = preferences(app);
            String raw = prefs.getString(KEY_COPY, "");
            JSONObject copy = parse(raw);
            String copyId = copy == null ? "" : copy.optString("id");
            String copyAttempt = copy == null ? "" : copy.optString("attemptToken");
            boolean currentCopy = isLatestAttempt(prefs, copyId, copyAttempt);
            boolean complete = copy != null && "COMPLETE_SUCCESS".equals(copy.optString("phase"));
            if (copy != null && validId(copyId) && !PROCESS_SESSION.equals(copy.optString("session"))) {
                String recoveryKey = recoveryKey(copyId, copyAttempt);
                if (currentCopy) RECOVERING_ATTEMPTS.add(recoveryKey);
                try {
                    if (complete) {
                        saveOutcome(prefs, copyId, copyAttempt, "SAVED", false);
                        if (currentCopy)
                            callbackFinished(copyId, copy.optBoolean("configuration"), true, false, "");
                        releasePermission(app, parseUri(copy), copy.optInt("persistedFlags", 0));
                    } else {
                        boolean deleted = deleteCopyRecordDocument(app, copy);
                        saveOutcome(prefs, copyId, copyAttempt, "INTERRUPTED", !deleted);
                        if (currentCopy)
                            callbackFinished(copyId, copy.optBoolean("configuration"), false, false,
                                    deleted ? "Document copy interrupted"
                                            : "Document copy interrupted; partial document could not be removed");
                        else if (!deleted) MainActivity.showArchiveDocumentCleanupWarning(app);
                    }
                    clearRecordIfMatches(prefs, KEY_COPY, copyId, copyAttempt);
                    PickerRequest picker = pendingPicker(app);
                    if (picker != null && sameAttempt(picker, copyId, copyAttempt)) clearPicker(app, picker);
                } finally {
                    if (currentCopy) RECOVERING_ATTEMPTS.remove(recoveryKey);
                }
            }

            PickerRequest picker = pendingPicker(app);
            if (picker != null && "RESULT".equals(picker.phase)
                    && !PROCESS_SESSION.equals(picker.processSession)
                    && (copy == null || !sameAttempt(picker, copyId, copyAttempt))) {
                boolean currentPicker = isLatestAttempt(prefs, picker.id, picker.attemptToken);
                String recoveryKey = recoveryKey(picker.id, picker.attemptToken);
                if (currentPicker) RECOVERING_ATTEMPTS.add(recoveryKey);
                try {
                    boolean deleted = picker.uri != null
                            && deleteAndRelease(app, picker.uri, picker.persistedFlags);
                    saveOutcome(prefs, picker.id, picker.attemptToken, "INTERRUPTED", !deleted);
                    reportPickerCleanup(picker.id, picker.configuration, !deleted, picker.attemptToken);
                    clearPicker(app, picker);
                } finally { if (currentPicker) RECOVERING_ATTEMPTS.remove(recoveryKey); }
            }

            recoverCleanupRecords(app, prefs);
        } catch (Exception error) {
            AppEventLogger.event(app, "archive_document_recovery_failed error=" + error.getClass().getSimpleName());
        } finally { recoveryPending = false; }
    }

    private static void recoverCleanupRecords(Context app, SharedPreferences prefs) {
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(CLEANUP_PREFIX)) continue;
            JSONObject cleanup = parse(String.valueOf(entry.getValue()));
            if (cleanup == null || PROCESS_SESSION.equals(cleanup.optString("session"))) continue;
            String id = cleanup.optString("id");
            String attemptToken = cleanup.optString("attemptToken");
            if (!validId(id)) continue;
            boolean currentAttempt = isLatestAttempt(prefs, id, attemptToken);
            String recoveryKey = recoveryKey(id, attemptToken);
            if (currentAttempt) RECOVERING_ATTEMPTS.add(recoveryKey);
            try {
                Uri uri = parseUri(cleanup);
                boolean deleted = deleteAndRelease(app, uri, cleanupPermissionFlags(app, cleanup, uri));
                saveOutcome(prefs, id, attemptToken, "INTERRUPTED", !deleted);
                prefs.edit().remove(key).commit();
                if (cleanup.optBoolean("reportWorkflowFailure", false))
                    reportPickerCleanup(id, cleanup.optBoolean("configuration", false), !deleted,
                            attemptToken);
                clearOrphanedPicker(app, id, attemptToken);
            } finally { if (currentAttempt) RECOVERING_ATTEMPTS.remove(recoveryKey); }
        }
    }

    private static void processCleanupRecord(Context app, String key, JSONObject cleanup) {
        String id = cleanup.optString("id");
        String attemptToken = cleanup.optString("attemptToken");
        Uri uri = parseUri(cleanup);
        boolean deleted = false;
        try { deleted = uri != null && deleteAndRelease(app, uri, cleanupPermissionFlags(app, cleanup, uri)); }
        catch (RuntimeException ignored) { }
        try { preferences(app).edit().remove(key).commit(); }
        catch (RuntimeException ignored) { }
        try { saveOutcome(preferences(app), id, attemptToken, "INTERRUPTED", !deleted); }
        catch (RuntimeException ignored) { }
        if (!deleted) {
            try { AppEventLogger.event(app, "archive_document_cleanup_failed id=" + id); }
            catch (RuntimeException ignored) { }
        }
        if (cleanup.optBoolean("reportWorkflowFailure", false))
            reportPickerCleanup(id, cleanup.optBoolean("configuration", false), !deleted, attemptToken);
        clearOrphanedPicker(app, id, attemptToken);
    }

    private static void reportPickerCleanup(String id, boolean configuration, boolean deleteFailed,
            String attemptToken) {
        String detail = deleteFailed
                ? "Destination request was interrupted; partial document could not be removed"
                : "Destination request was interrupted by app restart";
        if (!isLatestAttempt(id, attemptToken)) {
            if (deleteFailed) MainActivity.showArchiveDocumentCleanupWarning(appContext);
            return;
        }
        try {
            if (deleteFailed) {
                boolean warningShown = configuration
                        ? VehicleConfigurationExport.saveAsCleanupWarning(id, attemptToken, detail)
                        : StorageLogShareWorkflow.saveAsCleanupWarning(id, attemptToken, detail);
                if (!warningShown) MainActivity.showArchiveDocumentCleanupWarning(appContext);
            } else if (configuration) VehicleConfigurationExport.saveAsFailed(id, attemptToken, detail);
            else StorageLogShareWorkflow.saveAsFailed(id, attemptToken, detail);
        } catch (RuntimeException error) { callbackFailed(id); }
    }

    private static void runCopy(CopyWork work) {
        work.worker = Thread.currentThread();
        long startedAt = SystemClock.elapsedRealtime();
        long copied = 0L;
        String failure = "";
        boolean success = false;
        boolean cancelled = false;
        boolean cleanupFailed = false;
        OutputStream output = null;
        ShellWorkFiles.OperationLock artifactLock = null;
        JSONObject record = null;
        try {
            checkCancelled(work);
            record = copyRecord(work, "WRITING", 0L);
            if (!preferences(work.app).edit().putString(KEY_COPY, record.toString()).commit())
                throw new IOException("save record could not be persisted");
            clearMatchingResultPicker(work.app, work.id, work.attemptToken, work.uri);

            if (work.location.lockFile != null) {
                if (Files.isSymbolicLink(work.location.job.toPath())
                        || Files.isSymbolicLink(work.location.lockFile.toPath())
                        || (work.location.lockFile.exists() && !work.location.lockFile.isFile()))
                    throw new IOException("archive owner lock unavailable");
                artifactLock = new ShellWorkFiles.OperationLock(work.location.lockFile);
                work.ownerLockHeld = true;
            }
            checkCancelled(work);
            if (work.configuration && work.expiresAtMs <= System.currentTimeMillis())
                throw new ExpiredBeforeCopyException();
            ArchiveDocumentCopy.validate(work.plan);
            work.sourcesValidated = true;
            for (File base : work.protectedBases) ConfigurationExportArtifacts.protect(base);
            work.legacyProtectionHeld = !work.protectedBases.isEmpty();
            checkCancelled(work);
            if (work.configuration && work.expiresAtMs <= System.currentTimeMillis())
                throw new ExpiredBeforeCopyException();
            output = work.app.getContentResolver().openOutputStream(work.uri, "w");
            if (output == null) throw new FileNotFoundException("document provider returned no stream");
            synchronized (work.terminalLock) {
                checkCancelled(work);
                if (work.configuration && work.expiresAtMs <= System.currentTimeMillis())
                    throw new ExpiredBeforeCopyException();
                work.output = output;
                work.copying = true;
                ACTIVE_COPY.set(work);
            }
            callbackStarted(work.id, work.configuration, work.plan.totalBytes);
            AppEventLogger.event(work.app, "archive_document_save_started id=" + work.id
                    + " bytes=" + work.plan.totalBytes);

            LongConsumer progress = bytes -> callbackProgress(work.id, work.configuration,
                    bytes, work.plan.totalBytes);
            copied = ArchiveDocumentCopy.copy(work.plan, output, work.cancelled, progress);
            checkCancelled(work);
            output.close();
            output = null;
            synchronized (work.terminalLock) {
                if (work.cancelled.get() || Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("save cancelled");
                work.terminalState.set(COPY_TERMINAL);
                work.output = null;
            }
            record = copyRecord(work, "COMPLETE_SUCCESS", copied);
            if (!preferences(work.app).edit().putString(KEY_COPY, record.toString()).commit())
                throw new IOException("save result could not be persisted");
            success = true;
        } catch (Exception error) {
            cancelled = work.cancelled.get() || error instanceof InterruptedIOException
                    || Thread.currentThread().isInterrupted();
            failure = safeFailure(error);
        } finally {
            if (output != null) {
                try { output.close(); }
                catch (IOException closeFailure) {
                    success = false;
                    if (failure.isEmpty()) failure = "Unable to close saved document";
                }
            }
            synchronized (work.terminalLock) {
                work.terminalState.set(COPY_TERMINAL);
                work.output = null;
            }
            cancelled = work.cancelled.get() || cancelled;
            try {
                if (!success) {
                    try {
                        JSONObject currentRecord = copyRecord(work, "CLEANUP", copied);
                        preferences(work.app).edit().putString(KEY_COPY, currentRecord.toString()).commit();
                    } catch (RuntimeException ignored) { }
                    cleanupFailed = !deleteAndRelease(work.app, work.uri,
                            persistedFlags(work.app, work.id, work.attemptToken));
                }
                if (success) {
                    callbackFinished(work.id, work.configuration, true, false, "");
                    saveOutcome(preferences(work.app), work.id, work.attemptToken, "SAVED", false);
                } else {
                    String detail = failure.isEmpty() ? "Unable to save document" : failure;
                    if (cleanupFailed) detail += "; partial document could not be removed";
                    callbackFinished(work.id, work.configuration, false, cancelled, detail);
                    saveOutcome(preferences(work.app), work.id, work.attemptToken,
                            "INTERRUPTED", cleanupFailed);
                    if (cleanupFailed) AppEventLogger.event(work.app,
                            "archive_document_cleanup_failed id=" + work.id);
                }
            } catch (RuntimeException terminalFailure) {
                callbackFailed(work.id);
            } finally {
                try {
                    releasePermission(work.app, work.uri,
                            persistedFlags(work.app, work.id, work.attemptToken));
                } finally {
                    try {
                        PickerRequest current = pendingPicker(work.app);
                        if (current != null && sameAttempt(current, work.id, work.attemptToken))
                            clearPicker(work.app, current);
                        clearOrphanedPicker(work.app, work.id, work.attemptToken);
                        preferences(work.app).edit().remove(KEY_COPY)
                                .remove(cleanupKey(work.id, work.attemptToken)).commit();
                    } catch (RuntimeException ignored) { }
                    ACTIVE_COPY.compareAndSet(work, null);
                    work.copying = false;
                    work.sourcesValidated = false;
                    RESERVED.compareAndSet(work, null);
                    try { unprotect(work); }
                    catch (RuntimeException ignored) { }
                    if (artifactLock != null) {
                        try { artifactLock.close(); }
                        catch (IOException | RuntimeException error) {
                            AppEventLogger.event(work.app,
                                    "archive_document_lock_release_failed id=" + work.id);
                        }
                    }
                    work.ownerLockHeld = false;
                }
            }
            long duration = Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
            try {
                AppEventLogger.event(work.app, "archive_document_save_finished id=" + work.id
                        + " success=" + success + " cancelled=" + cancelled
                        + " bytes=" + Math.max(0L, copied) + " duration_ms=" + duration);
                if (work.configuration) ConfigurationExportArtifacts.checkAsync(work.app);
            } catch (RuntimeException ignored) { }
            Thread.interrupted();
        }
    }

    private static JSONObject copyRecord(CopyWork work, String phase, long bytes) {
        try {
            return new JSONObject().put("id", work.id).put("attemptToken", work.attemptToken)
                    .put("uri", work.uri.toString())
                    .put("configuration", work.configuration).put("displayName", work.displayName)
                    .put("expiresAt", work.expiresAtMs).put("session", PROCESS_SESSION)
                    .put("phase", phase).put("bytes", bytes)
                    .put("persistedFlags", persistedFlags(work.app, work.id, work.attemptToken));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void clearMatchingResultPicker(Context app, String id, String attemptToken, Uri uri) {
        SharedPreferences prefs = preferences(app);
        synchronized (LOCK) {
            try {
                String raw = prefs.getString(KEY_PICKER, "");
                JSONObject picker = new JSONObject(raw);
                if (sameAttempt(new PickerRequest(picker), id, attemptToken)
                        && "RESULT".equals(picker.optString("phase"))
                        && uri.toString().equals(picker.optString("uri")))
                    prefs.edit().remove(KEY_PICKER).commit();
            } catch (JSONException ignored) { }
        }
    }

    private static int persistedFlags(Context app, String id, String attemptToken) {
        PickerRequest picker = pendingPicker(app);
        if (picker != null && sameAttempt(picker, id, attemptToken)) return picker.persistedFlags;
        String raw = preferences(app).getString(KEY_COPY, "");
        JSONObject value = parse(raw);
        return sameAttempt(value, id, attemptToken) ? value.optInt("persistedFlags") : 0;
    }

    private static void checkCancelled(CopyWork work) throws InterruptedIOException {
        if (work.cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("save cancelled");
    }

    private static String safeFailure(Exception error) {
        if (error instanceof ExpiredBeforeCopyException) return "Archive expired before saving started";
        if (error instanceof FileNotFoundException || error instanceof SecurityException)
            return "Document provider unavailable or permission denied";
        if (error instanceof InterruptedIOException) return "Document save cancelled";
        if (error instanceof IOException) {
            String message = error.getMessage();
            if ("archive source size changed".equals(message)) return "Archive changed while saving";
            if ("archive source is not readable".equals(message)
                    || "archive source is not a regular file".equals(message)
                    || "archive source is empty".equals(message)
                    || "archive sources missing".equals(message)) return "Prepared archive is unavailable";
        }
        return "Unable to save document";
    }

    private static ArtifactLocation locateArtifacts(Context app, String id, boolean configuration,
            ArchiveDocumentCopy.Plan plan) throws IOException {
        File sharedRoot = ShellWorkFiles.root(app).getCanonicalFile();
        File cache = app.getCacheDir().getCanonicalFile();
        File externalCache = app.getExternalCacheDir() == null ? null : app.getExternalCacheDir().getCanonicalFile();
        File sharedJob = null;
        boolean legacy = false;
        for (File source : plan.sources) {
            File canonical = source.getCanonicalFile();
            if (isWithin(sharedRoot, canonical)) {
                File relative = sharedRoot.toPath().relativize(canonical.toPath()).toFile();
                String child = relative.getPath().split(java.util.regex.Pattern.quote(File.separator), 2)[0];
                if (!child.matches("(?:log|configuration)-[A-Za-z0-9_-]{1,80}"))
                    throw new IOException("archive source outside shared job");
                String expected = (configuration ? "configuration-" : "log-") + id;
                if (!expected.equals(child)) throw new IOException("archive source job mismatch");
                File job = new File(sharedRoot, child).getCanonicalFile();
                if (!job.getParentFile().equals(sharedRoot) || !isWithin(job, canonical))
                    throw new IOException("archive source outside shared job");
                File lock = new File(job, "owner.lock");
                if (Files.isSymbolicLink(new File(sharedRoot, child).toPath())
                        || (lock.exists() && !lock.isFile()) || Files.isSymbolicLink(lock.toPath()))
                    throw new IOException("shared archive owner lock unavailable");
                if (sharedJob != null && !sharedJob.equals(job)) throw new IOException("archive parts span jobs");
                if (legacy) throw new IOException("archive parts span storage areas");
                sharedJob = job;
            } else if (isWithin(new File(cache, "log-shares").getCanonicalFile(), canonical)
                    || (externalCache != null && isWithin(
                            new File(externalCache, "log-shares").getCanonicalFile(), canonical))) {
                if (sharedJob != null) throw new IOException("archive parts span storage areas");
                legacy = true;
            } else throw new IOException("archive source outside export workspace");
        }
        return sharedJob == null ? new ArtifactLocation(null, null)
                : new ArtifactLocation(sharedJob, new File(sharedJob, "owner.lock"));
    }

    private static List<File> legacyConfigurationBases(ArchiveDocumentCopy.Plan plan,
            ArtifactLocation location) throws IOException {
        if (location.job != null) return Collections.emptyList();
        File basePath = null;
        for (File source : plan.sources) {
            String name = source.getName();
            String base = name.replaceFirst("\\.zip\\.[0-9]{3,}$", ".zip");
            if (!base.startsWith("BYD-HUD-vehicle-config-") || !base.endsWith(".zip"))
                throw new IOException("configuration archive name is invalid");
            File path = new File(source.getParentFile().getCanonicalFile(), base);
            if (basePath != null && !basePath.equals(path))
                throw new IOException("configuration archive parts span files");
            basePath = path;
        }
        return basePath == null ? Collections.emptyList() : Collections.singletonList(basePath);
    }

    private static boolean isWithin(File root, File candidate) {
        String rootPath = root.getAbsolutePath();
        String candidatePath = candidate.getAbsolutePath();
        return candidatePath.startsWith(rootPath + File.separator);
    }

    private static void unprotect(CopyWork work) {
        if (!work.legacyProtectionHeld) return;
        for (File base : work.protectedBases) ConfigurationExportArtifacts.unprotect(base);
        work.legacyProtectionHeld = false;
    }

    private static void callbackStarted(String id, boolean configuration, long total) {
        try {
            if (configuration) VehicleConfigurationExport.documentSaveStarted(id, total);
            else StorageLogShareWorkflow.documentSaveStarted(id, total);
        } catch (RuntimeException error) { callbackFailed(id); }
    }

    private static void callbackProgress(String id, boolean configuration, long copied, long total) {
        try {
            if (configuration) VehicleConfigurationExport.documentSaveProgress(id, copied, total);
            else StorageLogShareWorkflow.documentSaveProgress(id, copied, total);
        } catch (RuntimeException error) { callbackFailed(id); }
    }

    private static void callbackFinished(String id, boolean configuration, boolean success,
            boolean cancelled, String detail) {
        try {
            if (configuration) VehicleConfigurationExport.documentSaveFinished(id, success, cancelled, detail);
            else StorageLogShareWorkflow.documentSaveFinished(id, success, cancelled, detail);
        } catch (RuntimeException error) { callbackFailed(id); }
    }

    private static void callbackFailed(String id) {
        Context app = appContext;
        if (app != null) AppEventLogger.event(app, "archive_document_callback_failed id=" + id);
    }

    private static void saveOutcome(SharedPreferences prefs, String id, String attemptToken,
            String status, boolean deleteFailed) {
        if (!validId(id) || attemptToken == null || attemptToken.isEmpty()
                || !isLatestAttempt(prefs, id, attemptToken)) return;
        try {
            JSONObject outcome = new JSONObject().put("id", id).put("attemptToken", attemptToken)
                    .put("status", status)
                    .put("deleteFailed", deleteFailed).put("at", System.currentTimeMillis());
            prefs.edit().putString(outcomeKey(id), outcome.toString()).commit();
        } catch (JSONException | RuntimeException ignored) { }
    }

    private static boolean hasOutcome(String id, String expected) {
        JSONObject value = readOutcome(id);
        return value != null && expected.equals(value.optString("status"));
    }

    private static JSONObject readOutcome(String id) {
        if (!validId(id)) return null;
        Context app = appContext;
        if (app == null) return null;
        JSONObject value = parse(preferences(app).getString(outcomeKey(id), ""));
        if (value == null || !id.equals(value.optString("id"))
                || !isLatestAttempt(preferences(app), id, value.optString("attemptToken"))) return null;
        long at = value.optLong("at", 0L);
        return at > 0 && System.currentTimeMillis() - at <= OUTCOME_TTL_MS ? value : null;
    }

    private static void clearRecordIfMatches(SharedPreferences prefs, String key, String id,
            String attemptToken) {
        JSONObject value = parse(prefs.getString(key, ""));
        if (sameAttempt(value, id, attemptToken)) prefs.edit().remove(key).commit();
    }

    private static boolean deleteCopyRecordDocument(Context app, JSONObject copy) {
        Uri uri = parseUri(copy);
        return uri != null && deleteAndRelease(app, uri, copy.optInt("persistedFlags", 0));
    }

    private static boolean deleteAndRelease(Context app, Uri uri, int persistedFlags) {
        boolean deleted = deleteCreatedDocument(app, uri);
        releasePermission(app, uri, persistedFlags);
        return deleted;
    }

    private static boolean deleteCreatedDocument(Context app, Uri uri) {
        if (uri == null || !validCreatedDocumentUri(uri)) return false;
        ContentResolver resolver = app.getContentResolver();
        try {
            if (DocumentsContract.isDocumentUri(app, uri) && DocumentsContract.deleteDocument(resolver, uri))
                return true;
        } catch (Exception ignored) { }
        try { return resolver.delete(uri, null, null) > 0; }
        catch (Exception ignored) { return false; }
    }

    private static void releasePermission(Context app, Uri uri, int flags) {
        int release = flags & URI_ACCESS_FLAGS;
        if (uri == null || release == 0) return;
        try { app.getContentResolver().releasePersistableUriPermission(uri, release); }
        catch (RuntimeException ignored) { }
    }

    private static int persistedUriFlags(Context app, Uri uri) {
        int flags = 0;
        for (android.content.UriPermission permission : app.getContentResolver()
                .getPersistedUriPermissions()) {
            if (!uri.equals(permission.getUri())) continue;
            if (permission.isReadPermission()) flags |= android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return flags & URI_ACCESS_FLAGS;
    }

    private static int cleanupPermissionFlags(Context app, JSONObject cleanup, Uri uri) {
        int owned = cleanup.optInt("persistedFlags", 0) & URI_ACCESS_FLAGS;
        int intent = cleanup.optInt("grantIntentFlags", 0) & URI_ACCESS_FLAGS;
        int preexisting = cleanup.optInt("preexistingFlags", 0) & URI_ACCESS_FLAGS;
        if (uri != null && intent != 0) {
            try { owned |= persistedUriFlags(app, uri) & intent & ~preexisting; }
            catch (RuntimeException ignored) { }
        }
        return owned & URI_ACCESS_FLAGS;
    }

    private static Uri parseUri(JSONObject value) {
        if (value == null) return null;
        String raw = value.optString("uri");
        return raw.isEmpty() ? null : Uri.parse(raw);
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); }
        catch (JSONException malformed) { return null; }
    }

    private static String cleanupKey(String id, String attemptToken) {
        return CLEANUP_PREFIX + id + "_" + attemptToken;
    }
    private static String attemptKey(String id) { return ATTEMPT_PREFIX + id; }
    private static String orphanPickerKey(String attemptToken) {
        return ORPHAN_PICKER_PREFIX + attemptToken;
    }
    private static String outcomeKey(String id) { return OUTCOME_PREFIX + id; }
    private static String recoveryKey(String id, String attemptToken) {
        return id + "_" + attemptToken;
    }

    private static boolean isLatestAttempt(SharedPreferences prefs, String id, String attemptToken) {
        return validId(id) && attemptToken != null && !attemptToken.isEmpty()
                && attemptToken.equals(prefs.getString(attemptKey(id), ""));
    }

    private static boolean sameAttempt(PickerRequest left, PickerRequest right) {
        return left != null && right != null && sameAttempt(left, right.id, right.attemptToken);
    }

    private static boolean sameAttempt(PickerRequest value, String id, String attemptToken) {
        return value != null && sameAttempt(value.id, value.attemptToken, id, attemptToken);
    }

    private static boolean sameAttempt(JSONObject value, String id, String attemptToken) {
        return value != null && sameAttempt(value.optString("id"),
                value.optString("attemptToken"), id, attemptToken);
    }

    private static boolean sameAttempt(String leftId, String leftToken, String rightId,
            String rightToken) {
        return leftId != null && leftId.equals(rightId) && leftToken != null
                && leftToken.equals(rightToken) && !leftToken.isEmpty();
    }

    private static Context appContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        appContext = app;
        return app;
    }

    private static SharedPreferences preferences(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,80}");
    }

    private static boolean validCreatedDocumentUri(Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme());
    }

    private static String safeDisplayName(String name) {
        String value = name == null ? "archive.zip" : name.replaceAll("[\\r\\n\\u0000-\\u001f\\\\/]", "_").trim();
        if (value.isEmpty()) value = "archive.zip";
        if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) value += ".zip";
        return value.length() > 180 ? value.substring(value.length() - 180) : value;
    }

    private static void closeQuietly(OutputStream output) {
        try { output.close(); } catch (IOException ignored) { }
    }
}
