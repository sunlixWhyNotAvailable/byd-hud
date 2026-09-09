package com.bydhud.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Absolute, non-renewing expiry for configuration exports, independent of HUD runtime. */
final class ConfigurationExportArtifacts {
    static final long RETENTION_MS = 15 * 60_000L;
    private static final int JOB_ID = 0x434658;
    private static final long RETRY_MS = 60_000L;
    private static final Pattern OWNED = Pattern.compile(
            "^(BYD-HUD-vehicle-config-[0-9]{8}(?:-[0-9]{6}(?:-[0-9]{3})?)?\\.zip)"
                    + "(?:\\.[0-9]{3,})?(?:\\.part|\\.source\\.part|\\.staging|\\.expiry\\.json(?:\\.part)?)?$");
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "configuration-export-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED_FAILURES = new HashSet<>();
    private static ScheduledFuture<?> scheduled;

    private ConfigurationExportArtifacts() { }

    static void protect(File base) { ACTIVE.add(base.getAbsolutePath()); }
    static void unprotect(File base) { ACTIVE.remove(base.getAbsolutePath()); }

    static void completed(File base, long completedAtMs) throws IOException {
        persist(base, completedAtMs, Math.addExact(completedAtMs, RETENTION_MS));
    }

    private static void persist(File base, long created, long expires) throws IOException {
        try {
            byte[] bytes = new JSONObject().put("createdAtMs", created)
                    .put("expiresAtMs", expires).toString().getBytes(StandardCharsets.UTF_8);
            File metadata = new File(base.getPath() + ".expiry.json");
            // This small record is outside the ZIP; failure prevents publishing a new export.
            File temporary = new File(metadata.getPath() + ".part");
            if (Files.isSymbolicLink(metadata.toPath()) || Files.isSymbolicLink(temporary.toPath()))
                throw new IOException("expiry record is a symbolic link");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), metadata.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }

    static void checkBeforeExport(Context context) throws InterruptedException, ExecutionException {
        Context app = context.getApplicationContext();
        WORKER.submit(() -> run(app)).get();
    }

    static void checkAsync(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> run(app));
    }

    static void checkAsync(Context context, Runnable finished) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> { try { run(app); } finally { finished.run(); } });
    }

    private static void run(Context context) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        Consumer<String> log = event -> {
            if (!event.startsWith("cleanup_failed") || REPORTED_FAILURES.add(event)) {
                AppEventLogger.event(context, "configuration_export " + event);
            }
        };
        for (File directory : directories(context)) {
            next = Math.min(next, sweep(directory, now, ACTIVE, log));
        }
        VehicleConfigurationExport.artifactsChecked(now);
        if (scheduled != null) scheduled.cancel(false);
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (next == Long.MAX_VALUE) {
            if (jobs != null) jobs.cancel(JOB_ID);
            return;
        }
        long delay = Math.max(1L, next - now);
        scheduled = WORKER.schedule(() -> run(context), delay, TimeUnit.MILLISECONDS);
        if (jobs != null) {
            int result = jobs.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, ConfigurationExportCleanupJob.class))
                    .setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay).build());
            if (result != JobScheduler.RESULT_SUCCESS && REPORTED_FAILURES.add("schedule")) {
                log.accept("cleanup_failed scheduler");
            }
        }
    }

    private static List<File> directories(Context context) {
        List<File> values = new ArrayList<>();
        values.add(new File(context.getCacheDir(), "log-shares"));
        File external = context.getExternalCacheDir();
        if (external != null) values.add(new File(external, "log-shares"));
        return values;
    }

    /** Pure filesystem boundary: names never supply paths outside the owned directory. */
    static long sweep(File directory, long now, Set<String> active, Consumer<String> log) {
        File[] children = directory.listFiles();
        if (children == null) return Long.MAX_VALUE;
        Map<String, List<File>> groups = new TreeMap<>();
        long next = Long.MAX_VALUE;
        for (File file : children) {
            if (file.getName().matches("BYD-HUD-vehicle-config-elf-[0-9]+\\.zip\\.source\\.part")
                    && active.isEmpty() && file.isFile() && !Files.isSymbolicLink(file.toPath())) {
                long size = file.length();
                if (file.delete()) log.accept("cleanup partial=" + file.getName() + " bytes=" + size);
                else {
                    log.accept("cleanup_failed file=" + file.getName());
                    next = Math.min(next, now + RETRY_MS);
                }
                continue;
            }
            Matcher match = OWNED.matcher(file.getName());
            if (!match.matches() || Files.isSymbolicLink(file.toPath())) continue;
            if (file.isDirectory() && file.getName().endsWith(".staging")) {
                File base = new File(directory, match.group(1));
                if (active.contains(base.getAbsolutePath())) continue;
                try (java.util.stream.Stream<java.nio.file.Path> tree = Files.walk(file.toPath())) {
                    java.nio.file.Path root = file.toPath().toAbsolutePath().normalize();
                    for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) tree.sorted(Comparator.reverseOrder())::iterator) {
                        if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IOException("cleanup path escaped");
                        Files.deleteIfExists(path);
                    }
                    log.accept("cleanup staging=" + file.getName());
                } catch (IOException error) {
                    log.accept("cleanup_failed staging=" + file.getName());
                    next = Math.min(next, now + RETRY_MS);
                }
                continue;
            }
            if (!file.isFile()) continue;
            groups.computeIfAbsent(match.group(1), ignored -> new ArrayList<>()).add(file);
        }
        for (Map.Entry<String, List<File>> group : groups.entrySet()) {
            File base = new File(directory, group.getKey());
            if (active.contains(base.getAbsolutePath())) continue;
            List<File> files = group.getValue();
            File metadata = new File(base.getPath() + ".expiry.json");
            if (Files.isSymbolicLink(metadata.toPath())) {
                log.accept("cleanup_failed symbolic_metadata=" + base.getName());
                next = Math.min(next, now + RETRY_MS);
                continue;
            }
            boolean completed = files.stream().anyMatch(f -> !f.getName().endsWith(".part")
                    && !f.getName().endsWith(".json"));
            long expires = 0;
            if (completed) {
                try {
                    if (metadata.isFile() && metadata.length() <= 1024) {
                        JSONObject record = new JSONObject(new String(Files.readAllBytes(metadata.toPath()),
                                StandardCharsets.UTF_8));
                        long created = record.getLong("createdAtMs");
                        expires = record.getLong("expiresAtMs");
                        if (created <= 0 || expires - created != RETENTION_MS) expires = 0;
                    }
                } catch (Exception ignored) { expires = 0; }
                if (expires == 0) {
                    // A completed legacy ZIP uses last-write time, not the start time in its name.
                    long modified = files.stream().filter(f -> !f.getName().endsWith(".json"))
                            .mapToLong(File::lastModified).max().orElse(0);
                    long created = modified > 0 && modified <= now ? modified : now;
                    expires = created + RETENTION_MS;
                    try {
                        persist(base, created, expires);
                        log.accept("legacy_adopted archive=" + base.getName() + " createdAtMs=" + created
                                + " expiresAtMs=" + expires);
                    } catch (IOException error) {
                        log.accept("cleanup_failed metadata=" + base.getName());
                        next = Math.min(next, now + RETRY_MS);
                        // Never delete early when the fallback could not be persisted.
                        if (expires > now) continue;
                    }
                }
            }
            if (completed && now < expires) { next = Math.min(next, expires); continue; }
            long removedBytes = 0;
            int removed = 0;
            boolean failed = false;
            for (File file : files) {
                if (file.equals(metadata)) continue;
                long size = file.length();
                if (file.delete() || !file.exists()) { removed++; removedBytes += size; }
                else { failed = true; log.accept("cleanup_failed file=" + file.getName()); }
            }
            if (!failed && metadata.exists() && !metadata.delete()) {
                failed = true;
                log.accept("cleanup_failed metadata=" + metadata.getName());
            }
            if (failed) next = Math.min(next, now + RETRY_MS);
            if (removed > 0) log.accept("cleanup archive=" + base.getName() + " files=" + removed
                    + " bytes=" + removedBytes + " expired=" + completed);
        }
        return next;
    }
}
