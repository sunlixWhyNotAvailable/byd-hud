package com.bydhud.app;

//owns localhost adb transport so privileged BYD commands stay behind one authenticated bridge.

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.function.LongConsumer;

import org.json.JSONObject;

//contains the LocalAdbBridge transport boundary so external communication is isolated from app logic.
final class LocalAdbBridge {
    private static final String TAG = "BydHudAdbBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int AUTH_PROMPT_TIMEOUT_MS = 60000;
    private static final Object AUTHORIZATION_SOCKET_LOCK = new Object();
    private static final Object PERMISSION_GRANT_LOCK = new Object();
    private static final Object RUNTIME_CONNECTION_LOCK = new Object();
    private static final Object KEY_PAIR_LOCK = new Object();
    //Retain an idle transport for real command bursts; status checks never touch it.
    private static final long RUNTIME_IDLE_CLOSE_MS = 30L * 60L * 1000L;
    private static final long POST_SETTINGS_POLL_TIMEOUT_MS = 3000L;
    private static final long POST_GRANT_POLL_TIMEOUT_MS = 30000L;
    private static final long POST_GRANT_POLL_INTERVAL_MS = 250L;
    private static final long ACCESSIBILITY_REBIND_STEP_DELAY_MS = 300L;
    private static final int MAX_DIAGNOSTIC_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int DIAGNOSTIC_OUTPUT_TAIL_BYTES = 64;
    private static final int INSTRUMENT_STARTUP_DIAGNOSTIC_BYTES = 4 * 1024;
    private static final int FULL_EXPORT_IDLE_TIMEOUT_MS = 30_000;
    private static final long FULL_INVENTORY_COMMAND_TIMEOUT_MS = 60_000L;
    private static final int SHANGHAI_SNAPSHOT_MAX_BYTES = 1024 * 1024;
    private static final Pattern SHANGHAI_SESSION_TOKEN = Pattern.compile("[a-f0-9]{12}");
    private static final String KEY_DIR = "adb_keys";
    private static volatile boolean permissionGrantInProgress;
    private static final String PRIVATE_KEY_FILE = "adb_key.priv";
    private static final String PUBLIC_KEY_FILE = "adb_key.pub";
    private static final String LEGACY_PRIVATE_KEY_FILE = "bydhud_adb_private.pk8";
    private static final String PREFS_NAME = "byd_hud_adb_bridge_prefs";
    private static final String KEY_AUTHORIZED_FINGERPRINT = "authorized_fingerprint";
    private static final String EXIT_MARKER = "__BYDHUD_EXIT__:";
    private static final Pattern MOVE_STACK_COMMAND =
            Pattern.compile("cmd activity display move-stack [0-9]{1,6} [0-9]{1,3}");
    private static final Pattern AUTO_CONTAINER_COMMAND = Pattern.compile(
            "service call (?:auto_container|AutoContainer) 2 i32 1000 i32 (?:16|17|18) s16 '\"\"'");
    private static final Pattern DIAGNOSTIC_LOGCAT_COMMAND = Pattern.compile(
            "logcat -b all -v threadtime -T '[0-9]{2}-[0-9]{2} "
                    + "[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}' -d");
    private static final Pattern LOGCAT_STREAM_CURSOR = Pattern.compile(
            "[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}");
    private static final Pattern DIAGNOSTIC_PROC_STAT_COMMAND = Pattern.compile(
            "cat /proc/[0-9]{1,10}/stat");
    private static final Pattern VEHICLE_CONFIG_PROPERTY_COMMAND = Pattern.compile(
            "getprop (?:ro\\.(?:build\\.(?:fingerprint|display\\.id)|product\\."
                    + "(?:brand|device|manufacturer|model|name)|hardware|board\\.platform|"
                    + "build\\.version\\.(?:release|sdk))|vendor\\.ro\\.build\\.system\\."
                    + "fission_single_os|ro\\.build\\.car\\.series|debug\\.cluster\\.type)");
    private static final Pattern VEHICLE_CONFIG_READ_COMMAND = Pattern.compile(
            "cat /(?:system|vendor|product|odm)/etc/[A-Za-z0-9_./+@:-]{1,512}");
    private static final Pattern VEHICLE_CONFIG_METADATA_COMMAND = Pattern.compile(
            "(?:stat -c %s|sha256sum) /(?:system|vendor|product|odm)/"
                    + "(?:etc|lib|lib64)/[A-Za-z0-9_./+@:-]{1,512}");
    private static final Pattern VEHICLE_CONFIG_APK_METADATA_COMMAND = Pattern.compile(
            "(?:stat -c %s|sha256sum) /(?:data/app|system|vendor|product|odm)/"
                    + "[A-Za-z0-9_./+@=:~-]{1,508}\\.apk");
    private static final byte[] ADB_AUTH_PADDING = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    };
    private static Connection runtimeConnection;
    private static long runtimeLastUsedMs;
    private static Socket pendingAuthorizationSocket;
    private static long authorizationCancellationGeneration;
    private static boolean authorizationCancellationPending;
    private static volatile String verifiedFingerprintThisProcess = "";
    private static volatile KeyPair cachedKeyPair;
    private static volatile String cachedKeyFingerprint = "";
    private static volatile long authorizationObservedAtMs;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private LocalAdbBridge() {
    }

    //defines the AuthorizationPromptMode module boundary so related behavior stays readable inside one unit.
    enum AuthorizationPromptMode {
        AUTO_ONCE,
        FORCE,
        NEVER
    }

    //identifies one FORCE cancellation so stale callbacks cannot clear a newer request.
    static final class AuthorizationCancellation {
        final long generation;
        final boolean socketClosed;

        private AuthorizationCancellation(long generation, boolean socketClosed) {
            this.generation = generation;
            this.socketClosed = socketClosed;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldSendPublicKeyForMode(AuthorizationPromptMode mode) {
        return mode != AuthorizationPromptMode.NEVER;
    }

    //guards adb repair so auto and manual flows can authorize a fresh app key after reinstall.
    static boolean canShortCircuitReadyForCapture(
            Context context, AuthorizationPromptMode mode) {
        if (mode == AuthorizationPromptMode.NEVER) {
            return true;
        }
        return mode == AuthorizationPromptMode.AUTO_ONCE
                && isCurrentKeyVerifiedThisProcess(context);
    }

    //uses the last successful ADB handshake as evidence that AUTO_ONCE needs no new prompt.
    static boolean isCurrentKeyKnownAuthorized(Context context) {
        String fingerprint = adbKeyFingerprint(context);
        return !"unavailable".equals(fingerprint)
                && fingerprint.equals(prefs(context).getString(KEY_AUTHORIZED_FINGERPRINT, ""));
    }

    static boolean isCurrentKeyVerifiedThisProcess(Context context) {
        String fingerprint = adbKeyFingerprint(context);
        return !"unavailable".equals(fingerprint)
                && fingerprint.equals(verifiedFingerprintThisProcess)
                && isCurrentKeyKnownAuthorized(context);
    }

    static boolean isPermissionGrantInProgress() {
        return permissionGrantInProgress;
    }

    //This snapshot never loads/generates a key or refreshes the runtime authorization cache.
    static JSONObject configurationExportAdbState(Context context) {
        JSONObject state = new JSONObject();
        try {
            File directory = new File(context.getFilesDir(), KEY_DIR);
            state.put("privateKeyFilePresent", new File(directory, PRIVATE_KEY_FILE).isFile());
            state.put("publicKeyFilePresent", new File(directory, PUBLIC_KEY_FILE).isFile());
            state.put("keyLoadedInProcess", cachedKeyPair != null);
            String cached = cachedKeyFingerprint;
            state.put("cachedKeyAuthorization", cached.isEmpty() ? JSONObject.NULL
                    : cached.equals(prefs(context).getString(KEY_AUTHORIZED_FINGERPRINT, "")));
            state.put("verifiedThisProcess", !verifiedFingerprintThisProcess.isEmpty());
            state.put("observedAtMs", authorizationObservedAtMs > 0
                    ? authorizationObservedAtMs : JSONObject.NULL);
            state.put("source", "passive app cache and file presence; not an ADB handshake");
        } catch (Exception error) {
            try { state.put("error", error.getClass().getName()); } catch (Exception ignored) { }
        }
        return state;
    }

    static ConfigurationExportSession openConfigurationExport(Context context) {
        return openConfigurationExport(context, VehicleConfigurationReadback.SESSION_TIMEOUT_MS);
    }

    /** Opens one isolated, prompt-free transport for a continuous all-buffer Logcat stream. */
    static LogcatStreamSession openLogcatStream(Context context, String cursor) throws IOException {
        String safeCursor = cursor == null ? "" : cursor.trim();
        if (!LOGCAT_STREAM_CURSOR.matcher(safeCursor).matches()) {
            throw new SecurityException("Invalid Logcat stream cursor");
        }
        Context app = context.getApplicationContext();
        Socket socket = new Socket();
        try {
            File directory = new File(app.getFilesDir(), KEY_DIR);
            KeyPair pair = loadConfigurationExportKeyPair(
                    new File(directory, PRIVATE_KEY_FILE),
                    new File(directory, PUBLIC_KEY_FILE));
            if (pair == null) throw new IOException("existing complete ADB key unavailable");
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            OpenResult opened = Connection.openConnectedSocket(app,
                    AuthorizationPromptMode.NEVER, pair, "", socket,
                    endpointLabel(PORT), 0L, false);
            if (opened.authorizationRequired || opened.connection == null) {
                throw new IOException("existing ADB key not authorized");
            }
            socket.setSoTimeout(0);
            return new LogcatStreamSession(socket, opened.connection, safeCursor);
        } catch (Exception error) {
            closeExportSocket(socket);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to open Logcat stream", error);
        }
    }

    /** A single-use stream whose close affects neither runtime ADB nor authorization state. */
    static final class LogcatStreamSession implements AutoCloseable {
        private final Socket socket;
        private final Connection connection;
        private final String cursor;
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private LogcatStreamSession(Socket socket, Connection connection, String cursor) {
            this.socket = socket;
            this.connection = connection;
            this.cursor = cursor;
        }

        void readTo(OutputStream output) throws IOException {
            if (output == null) throw new IllegalArgumentException("output is required");
            if (closed.get()) throw new IOException("Logcat stream closed");
            if (!reading.compareAndSet(false, true)) {
                throw new IOException("Logcat stream already consumed");
            }
            connection.streamShell(
                    "logcat -b all -v threadtime -T '" + cursor + "'", output);
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) closeExportSocket(socket);
        }
    }

    static LogcatStreamSession logcatStreamForTest(Socket socket) throws IOException {
        return new LogcatStreamSession(socket, new Connection(socket, null, false),
                "09-08 15:45:20.226");
    }

    /** Opens an isolated fixed Shanghai helper/capture stream; it never uses the runtime socket. */
    static ShanghaiStreamSession openShanghaiAdasStream(Context context, String token)
            throws IOException {
        requireShanghaiToken(token);
        String apkPath = context.getApplicationInfo().sourceDir;
        if (apkPath == null || apkPath.contains("..")
                || !apkPath.matches("/data/app/[A-Za-z0-9_./+=:~-]{1,500}/base\\.apk")) {
            throw new SecurityException("Unsupported installed APK path for Shanghai ADAS helper");
        }
        String pidFile = shanghaiPidFile(token, "adas");
        String command = "if [ -x /system/bin/app_process ]; then "
                + "/system/bin/app_process -Djava.class.path=" + apkPath
                + " /system/bin --nice-name=bydhud-sh-" + token + "-adas"
                + " com.bydhud.app.ShanghaiAdasEntryPoint & child=$!; "
                + "start=$(awk '{print $22}' /proc/$child/stat 2>/dev/null); "
                + "echo $child $start > " + pidFile + "; wait $child; code=$?; rm -f "
                + pidFile + "; exit $code; else exit 127; fi";
        return openShanghaiStream(context, command);
    }

    static ShanghaiStreamSession openShanghaiPcapStream(
            Context context, String token, String tcpdumpPath, String networkInterface)
            throws IOException {
        requireShanghaiToken(token);
        if (!("/system/bin/tcpdump".equals(tcpdumpPath)
                || "/vendor/bin/tcpdump".equals(tcpdumpPath))) {
            throw new SecurityException("Unsupported tcpdump path");
        }
        if (!("eth0".equals(networkInterface) || "any".equals(networkInterface))) {
            throw new SecurityException("Unsupported capture interface");
        }
        String pidFile = shanghaiPidFile(token, "pcap");
        String command = tcpdumpPath + " -i " + networkInterface
                + " -U -w - udp 2>/dev/null & child=$!; "
                + "start=$(awk '{print $22}' /proc/$child/stat 2>/dev/null); "
                + "echo $child $start > " + pidFile
                + "; (sleep " + ShanghaiDiagnostics.HELPER_HARD_LIMIT_SECONDS
                + "; kill -INT $child 2>/dev/null) & guard=$!; "
                + "wait $child; code=$?; kill $guard 2>/dev/null; rm -f " + pidFile
                + "; exit $code";
        return openShanghaiStream(context, command);
    }

    static ShellResult probeShanghaiPcap(Context context) throws IOException {
        return runTrustedRuntimeShellCommand(context,
                "if [ -x /system/bin/tcpdump ]; then echo /system/bin/tcpdump; "
                        + "elif [ -x /vendor/bin/tcpdump ]; then echo /vendor/bin/tcpdump; "
                        + "else exit 127; fi; "
                        + "if ip link show eth0 >/dev/null 2>&1; then echo interface=eth0; "
                        + "else echo interface=any; fi",
                16 * 1024);
    }

    static ShellResult captureShanghaiSnapshot(Context context) throws IOException {
        String command = "echo '[location]'; dumpsys location; "
                + "echo '[someip-service]'; dumpsys activity services com.ts.car.someip.service; "
                + "echo '[network-addresses]'; ip addr; echo '[network-routes]'; ip route show table all; "
                + "echo '[processes]'; ps -A";
        return runTrustedRuntimeShellCommand(context, command, SHANGHAI_SNAPSHOT_MAX_BYTES);
    }

    static ShellResult stopShanghaiStream(Context context, String token, String channel)
            throws IOException {
        requireShanghaiToken(token);
        if (!("adas".equals(channel) || "pcap".equals(channel))) {
            throw new SecurityException("Unsupported Shanghai channel");
        }
        String signal = "pcap".equals(channel) ? "INT" : "TERM";
        String pidFile = shanghaiPidFile(token, channel);
        String command = "if [ -r " + pidFile + " ]; then set -- $(cat " + pidFile
                + "); pid=$1; expected=$2; case $pid:$expected in *[!0-9:]*) exit 65;; esac; "
                + "current=$(awk '{print $22}' /proc/$pid/stat 2>/dev/null); "
                + "if [ -z \"$expected\" ] || [ \"$current\" != \"$expected\" ]; then exit 66; fi; "
                + "kill -" + signal + " $pid 2>/dev/null; code=$?; rm -f "
                + pidFile + "; exit $code; else exit 0; fi";
        return runTrustedRuntimeShellCommand(context, command, 16 * 1024, false);
    }

    static ShellResult readOwnMockLocationAppOp(Context context) throws IOException {
        String packageName = requireOwnPackage(context);
        int userId = android.os.Process.myUid() / 100000;
        return runTrustedRuntimeShellCommand(context,
                "appops get --user " + userId + " " + packageName + " android:mock_location",
                64 * 1024);
    }

    static ShellResult setOwnMockLocationAppOp(Context context, String mode) throws IOException {
        String safeMode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("allow".equals(safeMode) || "deny".equals(safeMode)
                || "ignore".equals(safeMode) || "default".equals(safeMode))) {
            throw new SecurityException("Unsupported mock-location app-op mode");
        }
        String packageName = requireOwnPackage(context);
        int userId = android.os.Process.myUid() / 100000;
        return runTrustedRuntimeShellCommand(context,
                "appops set --user " + userId + " " + packageName
                        + " android:mock_location " + safeMode,
                64 * 1024, false);
    }

    static ShellResult readGpsProviderState(Context context) throws IOException {
        return runTrustedRuntimeShellCommand(context, "dumpsys location gps", 512 * 1024);
    }

    private static String requireOwnPackage(Context context) {
        String packageName = context.getPackageName();
        if (packageName == null || !packageName.matches("[A-Za-z0-9_.]{1,200}")) {
            throw new SecurityException("Invalid current application package");
        }
        return packageName;
    }

    private static void requireShanghaiToken(String token) {
        if (token == null || !SHANGHAI_SESSION_TOKEN.matcher(token).matches()) {
            throw new SecurityException("Invalid Shanghai session token");
        }
    }

    private static String shanghaiPidFile(String token, String channel) {
        return "/data/local/tmp/bydhud-shanghai-" + token + "-" + channel + ".pid";
    }

    private static ShanghaiStreamSession openShanghaiStream(Context context, String command)
            throws IOException {
        Context app = context.getApplicationContext();
        Socket socket = new Socket();
        try {
            File directory = new File(app.getFilesDir(), KEY_DIR);
            KeyPair pair = loadConfigurationExportKeyPair(
                    new File(directory, PRIVATE_KEY_FILE),
                    new File(directory, PUBLIC_KEY_FILE));
            if (pair == null) throw new IOException("existing complete ADB key unavailable");
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            OpenResult opened = Connection.openConnectedSocket(app,
                    AuthorizationPromptMode.NEVER, pair, "", socket,
                    endpointLabel(PORT), 0L, false);
            if (opened.authorizationRequired || opened.connection == null) {
                throw new IOException("existing ADB key not authorized");
            }
            socket.setSoTimeout(0);
            return new ShanghaiStreamSession(socket, opened.connection, command);
        } catch (Exception error) {
            closeExportSocket(socket);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to open Shanghai diagnostic stream", error);
        }
    }

    static final class ShanghaiStreamSession implements AutoCloseable {
        private final Socket socket;
        private final Connection connection;
        private final String command;
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private ShanghaiStreamSession(Socket socket, Connection connection, String command) {
            this.socket = socket;
            this.connection = connection;
            this.command = command;
        }

        void readTo(OutputStream output) throws IOException {
            if (output == null) throw new IllegalArgumentException("output is required");
            if (closed.get()) throw new IOException("Shanghai stream closed");
            if (!reading.compareAndSet(false, true)) throw new IOException("Shanghai stream already consumed");
            connection.streamShell(command, output);
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) closeExportSocket(socket);
        }
    }

    //The collector owns the overall deadline, including its pre-ADB local work.
    static ConfigurationExportSession openConfigurationExport(Context context, long remainingBudgetMs) {
        long budgetMs = Math.max(0L, Math.min(remainingBudgetMs, VehicleConfigurationReadback.SESSION_TIMEOUT_MS));
        ConfigurationExportSession session = new ConfigurationExportSession(new Socket(), budgetMs, false);
        if (budgetMs == 0L) {
            session.unavailable = exportFailure("skipped", 125, "collection deadline exhausted", "");
            session.stop("session deadline");
            return session;
        }
        session.open(context.getApplicationContext());
        return session;
    }

    //Full raw-file export has no aggregate deadline; each command and idle read stays bounded.
    static ConfigurationExportSession openFullConfigurationExport(Context context) {
        ConfigurationExportSession session = new ConfigurationExportSession(new Socket(), 0L, true);
        session.open(context.getApplicationContext());
        return session;
    }

    //One foreground export owns one transport. No runtime locks, retries, consent or cache writes.
    static final class ConfigurationExportSession implements AutoCloseable {
        private final Socket socket;
        private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bydhud-export-deadline");
            thread.setDaemon(true);
            return thread;
        });
        private final long deadlineNanos;
        private final boolean fullExport;
        private final AtomicBoolean busy = new AtomicBoolean();
        private final AtomicBoolean oemRead = new AtomicBoolean();
        private final AtomicReference<String> stopped = new AtomicReference<>();
        private volatile Connection connection;
        private volatile ShellResult unavailable;
        private String apkPath;

        private ConfigurationExportSession(Socket socket, long budgetMs) {
            this(socket, budgetMs, false);
        }

        private ConfigurationExportSession(Socket socket, long budgetMs, boolean fullExport) {
            this.socket = socket;
            this.fullExport = fullExport;
            deadlineNanos = fullExport
                    ? Long.MAX_VALUE
                    : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
            if (!fullExport && budgetMs > 0L) {
                deadlines.schedule(() -> stop("session deadline"),
                        budgetMs, TimeUnit.MILLISECONDS);
            }
        }

        private void open(Context context) {
            ScheduledFuture<?> guard = null;
            try {
                guard = guard(CONNECT_TIMEOUT_MS, "connect/auth timeout");
                apkPath = context.getApplicationInfo().sourceDir;
                File directory = new File(context.getFilesDir(), KEY_DIR);
                File privateFile = new File(directory, PRIVATE_KEY_FILE);
                File publicFile = new File(directory, PUBLIC_KEY_FILE);
                //Refuse partial, oversized or mismatched saved pairs; never repair/migrate them.
                KeyPair pair = loadConfigurationExportKeyPair(privateFile, publicFile);
                if (pair == null) {
                    unavailable = exportFailure("denied", 126, "existing complete ADB key unavailable", "");
                    stop("key unavailable");
                    return;
                }
                if (stopped.get() != null || Thread.currentThread().isInterrupted()) {
                    throw new IOException("export cancelled before connect");
                }
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                OpenResult opened = Connection.openConnectedSocket(context,
                        AuthorizationPromptMode.NEVER, pair, "", socket, endpointLabel(PORT), 0L, false);
                if (opened.authorizationRequired) {
                    unavailable = exportFailure("denied", 126, "existing ADB key not authorized", "");
                    stop("authorization denied");
                } else {
                    connection = opened.connection;
                    socket.setSoTimeout(fullExport ? FULL_EXPORT_IDLE_TIMEOUT_MS : 0);
                }
            } catch (Exception error) {
                String reason = stopped.get();
                boolean timeout = reason != null && (reason.contains("timeout") || reason.contains("deadline"));
                unavailable = exportFailure(timeout ? "timeout" : "error", timeout ? 124 : -1,
                        reason == null ? exportError(error) : reason, "");
                stop("open failed");
            } finally {
                if (guard != null) guard.cancel(false);
            }
        }

        ShellResult run(String command) {
            String safe = command == null ? "" : command.trim();
            if (!VehicleConfigurationReadback.isAllowedCommand(safe)
                    && !isVehicleConfigurationCommand(safe)) {
                throw new SecurityException("Configuration export command is not read-only allowlisted");
            }
            return execute(safe, VehicleConfigurationReadback.COMMAND_TIMEOUT_MS);
        }

        ShellResult runFileCommand(String command) {
            String safe = command == null ? "" : command.trim();
            if (!VehicleConfigurationFiles.isAllowedCommand(safe)) {
                throw new SecurityException("Full configuration command is not allowlisted");
            }
            long timeout = fullExport && safe.startsWith("find ")
                    ? FULL_INVENTORY_COMMAND_TIMEOUT_MS
                    : VehicleConfigurationReadback.COMMAND_TIMEOUT_MS;
            return execute(safe, timeout);
        }

        long readFile(String path, OutputStream output, long expectedBytes,
                LongConsumer progress) throws IOException {
            if (!VehicleConfigurationFiles.isAllowedPath(path)) {
                throw new SecurityException("Full configuration path is not allowlisted");
            }
            String safePath = path;
            if (!fullExport) throw new IOException("full export session required");
            if (output == null) throw new IllegalArgumentException("output is required");
            if (expectedBytes < 0L) throw new IllegalArgumentException("expectedBytes < 0");
            if (unavailable != null) {
                throw new IOException(unavailable.error.isEmpty()
                        ? "configuration export transport unavailable" : unavailable.error);
            }
            if (!busy.compareAndSet(false, true)) {
                throw new IOException("export command already in progress");
            }
            try {
                if (Thread.currentThread().isInterrupted()) {
                    stop("cancelled");
                    throw new IOException("configuration file read cancelled");
                }
                if (stopped.get() != null || connection == null) {
                    throw new IOException(String.valueOf(stopped.get()));
                }
                return connection.readFile(safePath, output, expectedBytes, progress);
            } catch (AdbSyncReader.FileUnavailableException error) {
                // A completed sync FAIL or source-size mismatch affects this stream only.
                throw error;
            } catch (IOException | RuntimeException error) {
                if (stopped.get() == null) stop("transport failed");
                throw error;
            } finally {
                busy.set(false);
            }
        }

        ShellResult readOem() {
            long observedAtMs = System.currentTimeMillis();
            long startedNanos = System.nanoTime();
            return readOemUntimed().withTiming(observedAtMs,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }

        private ShellResult readOemUntimed() {
            if (!oemRead.compareAndSet(false, true)) {
                return exportFailure("skipped", 125, "OEM readback already requested", "");
            }
            if (unavailable != null) return unavailable;
            try {
                return execute(VehicleConfigurationReadback.launchCommand(apkPath),
                        VehicleConfigurationReadback.OEM_TIMEOUT_MS);
            } catch (SecurityException error) {
                return exportFailure("unsupported", 127, error.getMessage(), "");
            }
        }

        private ShellResult execute(String command, long timeoutMs) {
            long observedAtMs = System.currentTimeMillis();
            long startedNanos = System.nanoTime();
            return executeUntimed(command, timeoutMs).withTiming(observedAtMs,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }

        private ShellResult executeUntimed(String command, long timeoutMs) {
            if (unavailable != null) return unavailable;
            if (!busy.compareAndSet(false, true)) {
                return exportFailure("skipped", 125, "export command already in progress", "");
            }
            ScheduledFuture<?> guard = null;
            OutputAccumulator output = new OutputAccumulator(MAX_DIAGNOSTIC_OUTPUT_BYTES, true);
            try {
                if (System.nanoTime() >= deadlineNanos) stop("session deadline");
                if (Thread.currentThread().isInterrupted()) stop("cancelled");
                if (stopped.get() != null || connection == null) {
                    return exportFailure("skipped", 125, String.valueOf(stopped.get()), "");
                }
                guard = guard(timeoutMs, "command timeout");
                ShellCapture captured = connection.shell(command + "; echo " + EXIT_MARKER + "$?", output);
                ShellResult result = ShellResult.parse(captured.raw, captured.truncated, captured.droppedBytes);
                if (stopped.get() != null) return interruptedResult(output, null);
                return result;
            } catch (IOException | RuntimeException error) {
                ShellResult result = interruptedResult(output, error);
                stop("transport failed");
                return result;
            } finally {
                if (guard != null) guard.cancel(false);
                busy.set(false);
            }
        }

        private ShellResult interruptedResult(OutputAccumulator output, Exception error) {
            String reason = stopped.get();
            boolean timeout = reason != null && (reason.contains("timeout") || reason.contains("deadline"));
            try {
                ShellCapture captured = output.capture();
                return new ShellResult(captured.raw, timeout ? 124 : -1, captured.raw,
                        captured.truncated, captured.droppedBytes, timeout ? "timeout" : "error",
                        reason == null ? exportError(error) : reason);
            } catch (IOException impossible) {
                return exportFailure(timeout ? "timeout" : "error", timeout ? 124 : -1,
                        reason == null ? exportError(error) : reason, "");
            }
        }

        private ScheduledFuture<?> guard(long timeoutMs, String reason) {
            long remaining = fullExport
                    ? Long.MAX_VALUE
                    : Math.max(0L, deadlineNanos - System.nanoTime());
            long operationBudget = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            String expiry = remaining <= operationBudget ? "session deadline" : reason;
            return deadlines.schedule(() -> stop(expiry), Math.min(remaining,
                    operationBudget), TimeUnit.NANOSECONDS);
        }

        private void stop(String reason) {
            if (stopped.compareAndSet(null, reason)) {
                closeExportSocket(socket);
                deadlines.shutdownNow();
            }
        }

        @Override public void close() {
            stop("closed/cancelled");
            deadlines.shutdownNow();
        }
    }

    static KeyPair loadConfigurationExportKeyPair(File privateFile, File publicFile) throws Exception {
        if (!privateFile.isFile() || !publicFile.isFile()
                || privateFile.length() <= 0 || privateFile.length() > 16384
                || publicFile.length() <= 0 || publicFile.length() > 16384) return null;
        KeyPair pair = loadPersistedKeyPair(privateFile, publicFile, true);
        if (pair == null || !(pair.getPrivate() instanceof RSAPrivateCrtKey)
                || !(pair.getPublic() instanceof RSAPublicKey)) return null;
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        return privateKey.getModulus().equals(publicKey.getModulus())
                && privateKey.getPublicExponent().equals(publicKey.getPublicExponent()) ? pair : null;
    }

    private static ShellResult exportFailure(String status, int code, String error, String output) {
        return new ShellResult(output, code, output, false, 0L, status, error);
    }

    private static String exportError(Exception error) {
        return error == null ? "export interrupted" : error.getClass().getName() + ": " + error.getMessage();
    }

    private static void closeExportSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    //closes only the socket waiting for RSA approval so a manual retry can prompt immediately.
    static AuthorizationCancellation cancelPendingAuthorization() {
        Socket socket;
        long generation;
        synchronized (AUTHORIZATION_SOCKET_LOCK) {
            authorizationCancellationGeneration++;
            generation = authorizationCancellationGeneration;
            authorizationCancellationPending = true;
            socket = pendingAuthorizationSocket;
            pendingAuthorizationSocket = null;
        }
        if (socket != null) {
            closeQuietly(socket);
        }
        return new AuthorizationCancellation(generation, socket != null);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static List<String> adbEndpointLabelsForTest() {
        return Arrays.asList(endpointLabel(PORT));
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String adbEndpointSummaryForTest() {
        return endpointSummary();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String adbKeyFingerprint(Context context) {
        try {
            KeyPair keyPair = loadOrCreateKeyPair(context.getApplicationContext());
            String cached = cachedKeyFingerprint;
            return cached.isEmpty() ? fingerprint(keyPair) : cached;
        } catch (Exception e) {
            Log.w(TAG, "ADB key fingerprint unavailable", e);
            return "unavailable";
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static Result grantNavCapturePermissions(Context context) {
        return grantNavCapturePermissions(context, AuthorizationPromptMode.AUTO_ONCE);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static Result grantNavCapturePermissions(
            Context context,
            AuthorizationPromptMode authorizationPromptMode) {
        AuthorizationCancellation forceCancellation = null;
        if (authorizationPromptMode == AuthorizationPromptMode.FORCE) {
            forceCancellation = cancelPendingAuthorization();
        }
        synchronized (PERMISSION_GRANT_LOCK) {
            permissionGrantInProgress = true;
            try {
                if (forceCancellation != null
                        && !clearPendingAuthorizationCancellation(forceCancellation.generation)) {
                    return Result.authorizationRequired("ADB authorization retry superseded.");
                }
                return grantNavCapturePermissionsLocked(context, authorizationPromptMode);
            } finally {
                permissionGrantInProgress = false;
            }
        }
    }

    //serializes permission repair so service and UI authorization attempts cannot overlap.
    private static Result grantNavCapturePermissionsLocked(
            Context context,
            AuthorizationPromptMode authorizationPromptMode) {
        Context appContext = context.getApplicationContext();
        String normalizedPackage = appContext.getPackageName();
        NavPermissionStatus before = NavPermissionStatus.check(appContext);
        NavRuntimePermissionStatus runtimeBefore = NavRuntimePermissionStatus.check(appContext);
        if (runtimeBefore.readyForCapture()
                && canShortCircuitReadyForCapture(appContext, authorizationPromptMode)) {
            return Result.alreadyGranted(runtimeBefore.summary());
        }
        boolean grantNotificationListener = !before.notificationListenerEnabled;
        boolean grantAccessibilityService = !before.accessibilityServiceEnabled;
        boolean grantAccessibilityMaster = !before.accessibilityMasterEnabled;
        boolean grantStorageRead = !before.storageReadEnabled;
        boolean grantStorageWrite = !before.storageWriteEnabled;
        AppEventLogger.event(appContext, "adb_bridge targets"
                + " notification=" + grantNotificationListener
                + " accessibility=" + grantAccessibilityService
                + " accessibilityMaster=" + grantAccessibilityMaster
                + " storageRead=" + grantStorageRead
                + " storageWrite=" + grantStorageWrite
                + " forceStorageAppOps="
                + (authorizationPromptMode == AuthorizationPromptMode.FORCE));

        Connection connection = null;
        try {
            OpenResult openResult = Connection.open(appContext, authorizationPromptMode);
            if (openResult.authorizationRequired) {
                if (openResult.authorizationPromptSent) {
                    return Result.authorizationRequired(
                            "ADB RSA prompt sent; authorization was not completed before timeout.");
                }
                return Result.authorizationRequired(
                        "ADB key is not authorized.");
            }
            connection = openResult.connection;
            String endpointSuffix = openResult.endpointLabel.isEmpty()
                    ? ""
                    : " via " + openResult.endpointLabel;

            //A healthy runtime with a new/forced key only needs the successful handshake above.
            if (runtimeBefore.readyForCapture()
                    && authorizationPromptMode != AuthorizationPromptMode.FORCE) {
                return Result.alreadyGranted(runtimeBefore.summary() + endpointSuffix);
            }

            ShellResult notification = connection.shellWithExit(
                    "settings get secure " + NavPermissionGrantPlan.NOTIFICATION_LISTENERS);
            if (!notification.success()) {
                return Result.failed("settings get notification listeners failed: "
                        + notification.shortDetail());
            }
            ShellResult accessibility = connection.shellWithExit(
                    "settings get secure " + NavPermissionGrantPlan.ACCESSIBILITY_SERVICES);
            if (!accessibility.success()) {
                return Result.failed("settings get accessibility services failed: "
                        + accessibility.shortDetail());
            }

            NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                    appContext,
                    normalizedPackage,
                    notification.output,
                    accessibility.output,
                    grantNotificationListener,
                    grantAccessibilityService,
                    grantAccessibilityMaster);
            if (!plan.isValid()) {
                return Result.failed("ADB grant plan rejected: " + plan.error);
            }
            for (String command : plan.shellCommands) {
                AppEventLogger.event(appContext, "adb_bridge targeted_command " + command);
                ShellResult result = connection.shellWithExit(command);
                if (!result.success()) {
                    return Result.failed("ADB grant command failed: " + command
                            + " -> " + result.shortDetail());
                }
            }
            grantStoragePermissionsBestEffort(
                    connection,
                    appContext,
                    normalizedPackage,
                    grantStorageRead,
                    grantStorageWrite,
                    authorizationPromptMode == AuthorizationPromptMode.FORCE);
            if (grantNotificationListener) {
                String notificationCommand = notificationAllowListenerCommand(
                        appContext, normalizedPackage);
                if (notificationCommand.isEmpty()) {
                    return Result.failed("Notification listener command plan rejected");
                }
                ShellResult notificationAllow = connection.shellWithExit(
                        notificationCommand);
                if (notificationAllow.success()) {
                    AppEventLogger.event(appContext,
                            "adb_bridge notification_allow_listener success");
                } else {
                    AppEventLogger.event(appContext,
                            "adb_bridge notification_allow_listener failed "
                                    + notificationAllow.shortDetail());
                }
            }

            if (grantNotificationListener || !runtimeBefore.notificationListenerConnected) {
                NavNotificationListenerService.requestRuntimeRebind(appContext, "adb-grant");
            }
            Result accessibilityRebindResult = rebindAccessibilityRuntimeIfNeeded(
                    connection, appContext, normalizedPackage, accessibility.output);
            if (accessibilityRebindResult != null) {
                return accessibilityRebindResult;
            }

            NavPermissionStatus after = waitForSettingsGranted(appContext);
            if (!after.captureGranted()) {
                return Result.postGrantVerification(after, "endpoint=" + endpointSuffix);
            }
            return waitForRuntimeReady(appContext, endpointSuffix);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.partial("ADB grant interrupted while waiting for runtime services");
        } catch (IOException e) {
            Log.w(TAG, "ADB bridge unavailable", e);
            return Result.adbUnavailable("ADB bridge unavailable. Tried " + endpointSummary()
                    + ". Enable ADB debugging.");
        } catch (Exception e) {
            Log.e(TAG, "ADB grant failed", e);
            return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void grantStoragePermissionsBestEffort(
            Connection connection,
            Context appContext,
            String normalizedPackage,
            boolean grantRead,
            boolean grantWrite,
            boolean forceAppOps) throws IOException {
        for (String command : storageGrantCommands(
                normalizedPackage, grantRead, grantWrite, forceAppOps)) {
            ShellResult result = connection.shellWithExit(command);
            if (result.success()) {
                AppEventLogger.event(appContext,
                        "adb_bridge storage_grant success command=" + command);
            } else {
                AppEventLogger.event(appContext,
                        "adb_bridge storage_grant failed command=" + command
                                + " " + result.shortDetail());
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static List<String> storageGrantCommands(
            String normalizedPackage,
            boolean grantRead,
            boolean grantWrite,
            boolean forceAppOps) {
        List<String> commands = new ArrayList<>();
        if (grantRead) {
            commands.add("pm grant " + normalizedPackage
                    + " android.permission.READ_EXTERNAL_STORAGE");
        }
        if (grantRead || forceAppOps) {
            commands.add("appops set " + normalizedPackage + " READ_EXTERNAL_STORAGE allow");
        }
        if (grantWrite) {
            commands.add("pm grant " + normalizedPackage
                    + " android.permission.WRITE_EXTERNAL_STORAGE");
        }
        if (grantWrite || forceAppOps) {
            commands.add("appops set " + normalizedPackage + " WRITE_EXTERNAL_STORAGE allow");
        }
        if (grantRead || grantWrite || forceAppOps) {
            commands.add("appops set " + normalizedPackage + " LEGACY_STORAGE allow");
        }
        return commands;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static ShellResult runRuntimeShellCommand(Context context, String command) throws IOException {
        return runRuntimeShellCommand(context, command, true);
    }

    /** Mutations use readback before a caller decides whether sending again is safe. */
    static ShellResult runRuntimeShellCommandOnce(Context context, String command) throws IOException {
        return runRuntimeShellCommand(context, command, false);
    }

    private static ShellResult runRuntimeShellCommand(
            Context context, String command, boolean retryTransportFailure) throws IOException {
        String safeCommand = command == null ? "" : command.trim();
        if (!isAllowedRuntimeShellCommand(safeCommand)) {
            throw new SecurityException("ADB runtime command is not allowed: " + safeCommand);
        }
        boolean outputWrite = MOVE_STACK_COMMAND.matcher(safeCommand).matches()
                || AUTO_CONTAINER_COMMAND.matcher(safeCommand).matches();
        if (outputWrite && !ShanghaiOutputGate.enterWrite()) {
            return exportFailure("skipped", 125, "Shanghai output is suspended", "");
        }
        try {
            return runTrustedRuntimeShellCommand(context, safeCommand, 0, retryTransportFailure);
        } finally {
            if (outputWrite) ShanghaiOutputGate.leaveWrite();
        }
    }

    //keeps AutoContainer values behind the same authenticated allowlist as task moves.
    static ShellResult runAutoContainer(Context context, int value) throws IOException {
        if (value != 16 && value != 17 && value != 18) {
            throw new SecurityException("Unsupported AutoContainer value: " + value);
        }
        if (!ShanghaiOutputGate.enterWrite()) {
            return exportFailure("skipped", 125, "Shanghai output is suspended", "");
        }
        try {
            ShellResult lowercase = normalizeAutoContainerResult(runRuntimeShellCommand(
                    context, autoContainerCommand("auto_container", value)));
            if (lowercase.success() || !isUnknownServiceResult(lowercase)) {
                return lowercase;
            }
            return normalizeAutoContainerResult(runRuntimeShellCommand(
                    context, autoContainerCommand("AutoContainer", value)));
        } finally {
            ShanghaiOutputGate.leaveWrite();
        }
    }

    //uses the proven service-call shape without accepting arbitrary shell text.
    static String autoContainerCommandForTest(String service, int value) {
        return autoContainerCommand(service, value);
    }

    private static String autoContainerCommand(String service, int value) {
        if (!("auto_container".equals(service) || "AutoContainer".equals(service))
                || (value != 16 && value != 17 && value != 18)) {
            throw new IllegalArgumentException("Unsupported AutoContainer request");
        }
        return "service call " + service + " 2 i32 1000 i32 " + value + " s16 '\"\"'";
    }

    private static boolean isUnknownServiceResult(ShellResult result) {
        if (result == null || result.success()) {
            return false;
        }
        String detail = result.shortDetail().toLowerCase(java.util.Locale.ROOT);
        return detail.contains("unknown service")
                || detail.contains("service not found")
                || detail.contains("can't find service")
                || detail.contains("cannot find service")
                || detail.contains("not found");
    }

    private static ShellResult normalizeAutoContainerResult(ShellResult result) {
        if (result == null || !result.success()) return result;
        if (isSuccessfulAutoContainerResponse(result.exitCode, result.output)) {
            return result;
        }
        return new ShellResult(result.output, 1, result.raw,
                result.truncated, result.droppedBytes);
    }

    static boolean isSuccessfulAutoContainerResponse(int exitCode, String output) {
        if (exitCode != 0) return false;
        String safe = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
        return !safe.contains("exception") && !safe.contains("does not exist")
                && !safe.contains("not found") && !safe.contains("unknown service");
    }

    static ShellResult runDiagnosticShellCommand(Context context, String command)
            throws IOException {
        String safeCommand = command == null ? "" : command.trim();
        if (!isAllowedDiagnosticShellCommand(safeCommand)) {
            throw new SecurityException("ADB diagnostic command is not allowed: " + safeCommand);
        }
        return runTrustedRuntimeShellCommand(
                context, safeCommand, MAX_DIAGNOSTIC_OUTPUT_BYTES);
    }

    static boolean isAllowedDiagnosticShellCommandForTest(String command) {
        return isAllowedDiagnosticShellCommand(command == null ? "" : command.trim());
    }

    static ShellResult boundedDiagnosticOutputForTest(String raw, int maxBytes)
            throws IOException {
        OutputAccumulator output = new OutputAccumulator(maxBytes);
        output.append((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
        ShellCapture capture = output.capture();
        return ShellResult.parse(capture.raw, capture.truncated, capture.droppedBytes);
    }

    static ShellResult launchInstrumentProxy(
            Context context, String apkPath, long generation, String nonce,
            int appUid, String launchToken, int appVersionCode)
            throws IOException {
        String command = instrumentProxyLaunchCommand(
                apkPath, generation, nonce, appUid, launchToken, appVersionCode);
        return runTrustedRuntimeShellCommand(context, command);
    }

    static int instrumentProxyPid(ShellResult launchResult) {
        if (launchResult == null || !launchResult.success()) return -1;
        String output = launchResult.output == null ? "" : launchResult.output.trim();
        for (String value : output.split("\\s+")) {
            if (!value.matches("[0-9]{1,10}")) continue;
            try {
                int pid = Integer.parseInt(value);
                if (pid > 0) return pid;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    static String instrumentProxyStartupDiagnostic(
            Context context, InstrumentProxyStore.Identity expected) throws IOException {
        if (expected == null || !expected.isValid()) return "identity=missing";
        String path = instrumentProxyStartupDiagnosticPath(expected.uid, expected.generation);
        String alive = expected.pid > 0
                ? "[ -d /proc/" + expected.pid + " ] && echo pidAlive=1 || echo pidAlive=0"
                : "echo pidAlive=unknown";
        ShellResult result = runTrustedRuntimeShellCommand(context,
                alive + "; if [ -f " + path + " ]; then tail -c "
                        + INSTRUMENT_STARTUP_DIAGNOSTIC_BYTES + " " + path
                        + "; else echo startupLog=missing; fi; rm -f " + path,
                INSTRUMENT_STARTUP_DIAGNOSTIC_BYTES + 512);
        return sanitizeInstrumentProxyStartupDiagnostic(result.output);
    }

    static void clearInstrumentProxyStartupDiagnostic(
            Context context, InstrumentProxyStore.Identity expected) throws IOException {
        if (expected == null || !expected.isValid()) return;
        runTrustedRuntimeShellCommand(context, "rm -f "
                + instrumentProxyStartupDiagnosticPath(expected.uid, expected.generation));
    }

    static ShellResult stopInstrumentProxy(
            Context context, InstrumentProxyStore.Identity expected) throws IOException {
        if (expected == null || !expected.isValid()) return new ShellResult("", 0, "");
        String output;
        ShellResult lookup;
        if (expected.pid > 0) {
            output = Integer.toString(expected.pid);
            lookup = new ShellResult(output, 0, "");
        } else {
            lookup = runTrustedRuntimeShellCommand(
                    context, "pidof " + expected.processName);
            if (lookup.exitCode == 126) return lookup;
            output = lookup.output.trim();
            if (output.isEmpty()) return new ShellResult("", 0, lookup.raw);
        }
        StringBuilder command = new StringBuilder("kill -9");
        for (String value : output.split("\\s+")) {
            if (!value.matches("[0-9]{1,10}")) {
                throw new IOException("Unexpected Instrument proxy pid: " + value);
            }
            ShellResult status = runTrustedRuntimeShellCommand(
                    context, "cat /proc/" + value + "/status");
            if (!status.success()) {
                ShellResult recheck = runTrustedRuntimeShellCommand(
                        context, "pidof " + expected.processName);
                if (recheck.exitCode == 126) return recheck;
                if (!containsPid(recheck.output, value)) continue;
            }
            ShellResult cmdline = runTrustedRuntimeShellCommand(
                    context, "cat /proc/" + value + "/cmdline");
            if (!cmdline.success()) {
                ShellResult recheck = runTrustedRuntimeShellCommand(
                        context, "pidof " + expected.processName);
                if (recheck.exitCode == 126) return recheck;
                if (!containsPid(recheck.output, value)) continue;
            }
            ShellResult stat = runTrustedRuntimeShellCommand(
                    context, "cat /proc/" + value + "/stat");
            if (!stat.success()) {
                ShellResult recheck = runTrustedRuntimeShellCommand(
                        context, "pidof " + expected.processName);
                if (recheck.exitCode == 126) return recheck;
                if (!containsPid(recheck.output, value)) continue;
            }
            int pid = Integer.parseInt(value);
            InstrumentProcessIdentity identity = InstrumentProcessIdentity.parse(
                    status.output, cmdline.output, stat.output);
            if (!status.success() || !cmdline.success() || !stat.success()
                    || !identity.isVerifiable()) {
                throw new IOException("Unable to verify Instrument proxy identity pid="
                        + value + " name=" + identity.name
                        + " uid=" + identity.uidSummary
                        + " startTicks=" + identity.startTimeTicks
                        + " cmdline=" + identity.sanitizedCmdline());
            }
            if (!identity.matches(expected, pid)) {
                AppEventLogger.event(context,
                        "instrument_proxy cleanup_identity_not_owned pid=" + pid
                                + " name=" + identity.name
                                + " uid=" + identity.uidSummary.replaceAll("\\s+", ",")
                                + " startTicks=" + identity.startTimeTicks
                                + " generation=" + expected.generation);
                continue;
            }
            AppEventLogger.event(context, "instrument_proxy cleanup_identity pid=" + pid
                    + " name=" + identity.name
                    + " uid=" + identity.uidSummary.replaceAll("\\s+", ",")
                    + " startTicks=" + identity.startTimeTicks
                    + " generation=" + expected.generation);
            command.append(' ').append(value);
        }
        if ("kill -9".contentEquals(command)) return new ShellResult("", 0, lookup.raw);
        ShellResult killed = runTrustedRuntimeShellCommand(context, command.toString());
        if (killed.success() || killed.exitCode == 126) return killed;
        ShellResult recheck = runTrustedRuntimeShellCommand(
                context, "pidof " + expected.processName);
        return recheck.output.trim().isEmpty()
                ? new ShellResult("", 0, killed.raw + recheck.raw)
                : killed;
    }

    static ShellResult stopLegacyInstrumentProxy(Context context, int appUid)
            throws IOException {
        String processName = InstrumentProxyContract.legacyProcessName(appUid);
        ShellResult lookup = runTrustedRuntimeShellCommand(context, "pidof " + processName);
        if (lookup.exitCode == 126) return lookup;
        if (lookup.output.trim().isEmpty()) {
            return new ShellResult("", 0, lookup.raw);
        }
        StringBuilder command = new StringBuilder("kill -9");
        for (String value : lookup.output.trim().split("\\s+")) {
            if (!value.matches("[0-9]{1,10}")) {
                throw new IOException("Unexpected legacy Instrument proxy pid: " + value);
            }
            ShellResult status = runTrustedRuntimeShellCommand(
                    context, "cat /proc/" + value + "/status");
            ShellResult cmdline = runTrustedRuntimeShellCommand(
                    context, "cat /proc/" + value + "/cmdline");
            InstrumentProcessIdentity identity = InstrumentProcessIdentity.parse(
                    status.output, cmdline.output, "");
            if (!status.success() || !cmdline.success()
                    || !identity.matchesLegacy(processName)) {
                throw new IOException("Refusing unexpected legacy Instrument proxy identity pid="
                        + value + " name=" + identity.name
                        + " uid=" + identity.uidSummary
                        + " cmdline=" + identity.sanitizedCmdline());
            }
            command.append(' ').append(value);
        }
        return "kill -9".contentEquals(command)
                ? new ShellResult("", 0, lookup.raw)
                : runTrustedRuntimeShellCommand(context, command.toString());
    }

    private static boolean containsPid(String output, String expectedPid) {
        String safeOutput = output == null ? "" : output.trim();
        for (String value : safeOutput.split("\\s+")) {
            if (expectedPid.equals(value)) return true;
        }
        return false;
    }

    static boolean hasExpectedInstrumentProxyIdentityForTest(
            String status, String processName) {
        return hasExpectedInstrumentProxyIdentity(status, processName);
    }

    private static boolean hasExpectedInstrumentProxyIdentity(
            String status, String processName) {
        String safeStatus = status == null ? "" : status;
        String safeName = processName == null ? "" : processName.trim();
        if (!safeName.matches("bydh[0-9]{5,10}")) return false;
        boolean nameMatches = Pattern.compile(
                "(?m)^Name:\\s*" + Pattern.quote(safeName) + "\\s*$")
                .matcher(safeStatus).find();
        boolean uidMatches = Pattern.compile(
                "(?m)^Uid:\\s*2000(?:\\s+2000){3}\\s*$")
                .matcher(safeStatus).find();
        return nameMatches && uidMatches;
    }

    static boolean hasExpectedInstrumentProxyIdentityForTest(
            String status, String cmdline, String stat,
            InstrumentProxyStore.Identity expected, int actualPid) {
        return InstrumentProcessIdentity.parse(status, cmdline, stat)
                .matches(expected, actualPid);
    }

    static boolean hasExpectedLegacyInstrumentProxyIdentityForTest(
            String status, String cmdline, String processName) {
        return InstrumentProcessIdentity.parse(status, cmdline, "")
                .matchesLegacy(processName);
    }

    private static final class InstrumentProcessIdentity {
        final String name;
        final String uidSummary;
        final boolean shellUid;
        final String cmdline;
        final long startTimeTicks;

        private InstrumentProcessIdentity(String name, String uidSummary,
                boolean shellUid, String cmdline, long startTimeTicks) {
            this.name = name;
            this.uidSummary = uidSummary;
            this.shellUid = shellUid;
            this.cmdline = cmdline;
            this.startTimeTicks = startTimeTicks;
        }

        static InstrumentProcessIdentity parse(
                String status, String rawCmdline, String stat) {
            String safeStatus = status == null ? "" : status;
            String name = capture(safeStatus, "(?m)^Name:\\s*([^\\r\\n]+)$");
            String uid = capture(safeStatus, "(?m)^Uid:\\s*([^\\r\\n]+)$");
            boolean shellUid = uid.matches("2000(?:\\s+2000){3}");
            String cmdline = rawCmdline == null ? ""
                    : rawCmdline.replace('\0', ' ').trim().replaceAll("\\s+", " ");
            return new InstrumentProcessIdentity(name, uid, shellUid, cmdline,
                    InstrumentProxyContract.processStartTimeTicks(stat));
        }

        boolean matches(InstrumentProxyStore.Identity expected, int actualPid) {
            if (expected == null || !expected.isValid() || !shellUid) return false;
            if (expected.pid > 0 && expected.pid != actualPid) return false;
            if (!cmdline.equals(expected.processName)
                    && !cmdline.startsWith(expected.processName + " ")) return false;
            return expected.startTimeTicks <= 0L
                    || expected.startTimeTicks == startTimeTicks;
        }

        boolean isVerifiable() {
            return !uidSummary.isEmpty() && !cmdline.isEmpty() && startTimeTicks > 0L;
        }

        boolean matchesLegacy(String processName) {
            String expected = processName == null ? "" : processName.trim();
            return expected.matches("bydh[0-9]{5,10}")
                    && shellUid
                    && cmdline.equals(expected);
        }

        String sanitizedCmdline() {
            String sanitized = cmdline.replaceAll(
                    "--nonce=[0-9a-f]{32}", "--nonce=<redacted>");
            return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180);
        }

        private static String capture(String value, String pattern) {
            java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(value);
            return matcher.find() ? matcher.group(1).trim() : "";
        }
    }

    static String instrumentProxyLaunchCommandForTest(
            String apkPath, long generation, String nonce, int appUid,
            String launchToken, int appVersionCode) {
        return instrumentProxyLaunchCommand(
                apkPath, generation, nonce, appUid, launchToken, appVersionCode);
    }

    static String sanitizeInstrumentProxyStartupDiagnosticForTest(String raw) {
        return sanitizeInstrumentProxyStartupDiagnostic(raw);
    }

    private static String instrumentProxyLaunchCommand(
            String apkPath, long generation, String nonce, int appUid,
            String launchToken, int appVersionCode) {
        String safePath = apkPath == null ? "" : apkPath.trim();
        String safeNonce = nonce == null ? "" : nonce.trim();
        String safeToken = launchToken == null ? "" : launchToken.trim();
        if (safePath.contains("..")
                || !safePath.matches("/data/app/[A-Za-z0-9_./+=:~-]{1,500}/base\\.apk")
                || generation <= 0L
                || !safeNonce.matches("[0-9a-f]{32}")
                || appUid < 10_000
                || !InstrumentProxyContract.validLaunchToken(safeToken)
                || appVersionCode <= 0) {
            throw new SecurityException("Invalid Instrument proxy launch parameters");
        }
        String classPath = "/system/framework/services.jar:"
                + "/system/framework/dilink-services.jar:" + safePath;
        String libraryPath = "/system/lib64:/product/lib64:"
                + safePath + "!/lib/arm64-v8a";
        String processName = InstrumentProxyContract.processName(appUid, safeToken);
        String diagnosticPath = instrumentProxyStartupDiagnosticPath(appUid, generation);
        return "umask 077; rm -f " + diagnosticPath + "; nohup /system/bin/app_process"
                + " -Djava.class.path=" + classPath
                + " -Djava.library.path=" + libraryPath
                + " /system/bin"
                + " --nice-name=" + processName
                + " com.bydhud.app.InstrumentProxyEntryPoint"
                + " --generation=" + generation
                + " --nonce=" + safeNonce
                + " --app-uid=" + appUid
                + " --launch-token=" + safeToken
                + " --version-code=" + appVersionCode
                + " >" + diagnosticPath + " 2>&1 </dev/null & echo $!";
    }

    private static String instrumentProxyStartupDiagnosticPath(int appUid, long generation) {
        if (appUid < 10_000 || generation <= 0L) {
            throw new SecurityException("Invalid Instrument diagnostic identity");
        }
        return "/data/local/tmp/bydhud-instrument-" + appUid + "-" + generation + ".log";
    }

    private static String sanitizeInstrumentProxyStartupDiagnostic(String raw) {
        String sanitized = (raw == null ? "" : raw)
                .replaceAll("--nonce=[^\\s]+", "--nonce=<redacted>")
                .replaceAll("--launch-token=[^\\s]+", "--launch-token=<redacted>")
                .replace('\0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isEmpty()) return "output=empty";
        if (sanitized.length() <= 1_600) return sanitized;
        return sanitized.substring(0, 800) + " ... "
                + sanitized.substring(sanitized.length() - 800);
    }

    private static ShellResult runTrustedRuntimeShellCommand(
            Context context, String safeCommand) throws IOException {
        return runTrustedRuntimeShellCommand(context, safeCommand, 0);
    }

    private static ShellResult runTrustedRuntimeShellCommand(
            Context context, String safeCommand, int maxOutputBytes) throws IOException {
        return runTrustedRuntimeShellCommand(context, safeCommand, maxOutputBytes, true);
    }

    private static ShellResult runTrustedRuntimeShellCommand(
            Context context, String safeCommand, int maxOutputBytes,
            boolean retryTransportFailure) throws IOException {
        Context appContext = context.getApplicationContext();
        synchronized (RUNTIME_CONNECTION_LOCK) {
            try {
                Connection connection = runtimeConnectionLocked(appContext);
                if (connection == null) {
                    return unauthorizedRuntimeShellResult();
                }
                ShellResult result = connection.shellWithExit(safeCommand, maxOutputBytes);
                runtimeLastUsedMs = android.os.SystemClock.elapsedRealtime();
                return result;
            } catch (IOException e) {
                closeRuntimeConnectionLocked(appContext, "io_exception");
                if (!retryTransportFailure) throw e;
                try {
                    Connection connection = runtimeConnectionLocked(appContext);
                    if (connection == null) {
                        return unauthorizedRuntimeShellResult();
                    }
                    ShellResult result = connection.shellWithExit(safeCommand, maxOutputBytes);
                    runtimeLastUsedMs = android.os.SystemClock.elapsedRealtime();
                    return result;
                } catch (IOException retry) {
                    closeRuntimeConnectionLocked(appContext, "retry_io_exception");
                    throw retry;
                } catch (Exception retry) {
                    closeRuntimeConnectionLocked(appContext, "retry_exception");
                    throw new IOException("ADB runtime command failed after retry: "
                            + retry.getClass().getSimpleName() + ": " + retry.getMessage(),
                            retry);
                }
            } catch (Exception e) {
                throw new IOException("ADB runtime command failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Result rebindAccessibilityRuntimeIfNeeded(
            Connection connection,
            Context appContext,
            String packageName,
            String currentAccessibilityServices) throws IOException, InterruptedException {
        ShellResult accessibilityDump = connection.shellWithExit("dumpsys accessibility");
        if (!accessibilityDump.success()) {
            AppEventLogger.event(appContext, "adb_bridge accessibility_dump_failed "
                    + accessibilityDump.shortDetail());
            return null;
        }
        NavPermissionRuntimeProbe.Result accessibilityRuntime =
                NavPermissionRuntimeProbe.parseAccessibilityDumpsys(
                        appContext.getPackageName(),
                        accessibilityDump.output);
        if (accessibilityRuntime.accessibilityRuntimeOk()) {
            return null;
        }
        AppEventLogger.event(appContext, "adb_bridge accessibility_runtime_rebind start"
                + " enabled=" + accessibilityRuntime.accessibilityEnabledInDumpsys
                + " bound=" + accessibilityRuntime.accessibilityBoundInDumpsys
                + " crashed=" + accessibilityRuntime.accessibilityCrashedInDumpsys);
        List<String> commands = NavPermissionGrantPlan.accessibilityRuntimeRebindCommands(
                appContext, packageName, currentAccessibilityServices);
        if (commands.isEmpty()) {
            return Result.failed("Accessibility runtime rebind plan rejected");
        }
        for (String command : commands) {
            ShellResult result = connection.shellWithExit(command);
            if (!result.success()) {
                return Result.partial("Accessibility runtime rebind command failed: "
                        + command + " -> " + result.shortDetail());
            }
            Thread.sleep(ACCESSIBILITY_REBIND_STEP_DELAY_MS);
        }
        return null;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isAllowedRuntimeShellCommandForTest(String command) {
        return isAllowedRuntimeShellCommand(command == null ? "" : command.trim());
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String notificationAllowListenerCommandForTest(String packageName) {
        return notificationAllowListenerCommand(packageName);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isAllowedRuntimeShellCommand(String command) {
        return "id".equals(command)
                || "pidof com.waze".equals(command)
                || "dumpsys display".equals(command)
                || "dumpsys activity activities".equals(command)
                || isVehicleConfigurationCommand(command)
                || MOVE_STACK_COMMAND.matcher(command).matches()
                || AUTO_CONTAINER_COMMAND.matcher(command).matches();
    }

    private static boolean isAllowedDiagnosticShellCommand(String command) {
        if (DIAGNOSTIC_LOGCAT_COMMAND.matcher(command).matches()
                || DIAGNOSTIC_PROC_STAT_COMMAND.matcher(command).matches()) {
            return true;
        }
        switch (command) {
            case "logcat -g -b all":
            case "dumpsys gfxinfo com.bydhud.app reset":
            case "dumpsys gfxinfo com.bydhud.app framestats":
            case "dumpsys accessibility":
            case "dumpsys activity activities":
            case "dumpsys window windows":
            case "dumpsys cpuinfo":
            case "dumpsys thermalservice":
            case "dumpsys meminfo com.bydhud.app":
            case "dumpsys package com.bydhud.app":
            case "cat /proc/loadavg":
                return true;
            default:
                return false;
        }
    }

    private static boolean isVehicleConfigurationCommand(String command) {
        if (command.contains("..")) {
            return false;
        }
        if (VEHICLE_CONFIG_PROPERTY_COMMAND.matcher(command).matches()
                || VEHICLE_CONFIG_READ_COMMAND.matcher(command).matches()
                || VEHICLE_CONFIG_METADATA_COMMAND.matcher(command).matches()
                || VEHICLE_CONFIG_APK_METADATA_COMMAND.matcher(command).matches()) {
            return true;
        }
        switch (command) {
            case "id":
            case "uname -a":
            case "service list":
            case "ps -A":
            case "ip -details link":
            case "ip addr":
            case "ip route show table all":
            case "ip rule":
            case "ip neigh":
            case "ss -a -n -p":
            case "dumpsys package com.ts.car.someip.service":
              case "dumpsys activity services com.ts.car.someip.service":
              case "dumpsys activity services com.waze":
              case "dumpsys activity services com.google.android.apps.maps":
              case "dumpsys activity services app.revanced.android.apps.maps":
            case "find /system/etc /vendor/etc /product/etc /odm/etc -type f":
            case "find /system/lib /system/lib64 /vendor/lib /vendor/lib64 "
                    + "/product/lib /product/lib64 /odm/lib /odm/lib64 -type f":
            case "pm path com.bydhud.app":
            case "pm path com.waze":
            case "pm path app.revanced.android.apps.maps":
            case "pm path com.google.android.apps.maps":
            case "pm path com.ts.car.someip.service":
            case "pm path com.byd.launchermap":
                return true;
            default:
                return false;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Connection runtimeConnectionLocked(Context appContext) throws Exception {
        long nowMs = android.os.SystemClock.elapsedRealtime();
        if (runtimeConnection != null
                && runtimeLastUsedMs > 0
                && nowMs - runtimeLastUsedMs > RUNTIME_IDLE_CLOSE_MS) {
            closeRuntimeConnectionLocked(appContext, "idle_timeout");
        }
        if (runtimeConnection != null) {
            runtimeLog(appContext, "adb_runtime reuse");
            return runtimeConnection;
        }

        OpenResult openResult = runtimeOpenLocked(appContext);
        if (openResult.authorizationRequired || openResult.connection == null) {
            runtimeLog(appContext, "adb_runtime unauthorized");
            return null;
        }
        runtimeConnection = openResult.connection;
        runtimeLastUsedMs = nowMs;
        runtimeLog(appContext, "adb_runtime open_session endpoint=" + openResult.endpointLabel);
        return runtimeConnection;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static OpenResult runtimeOpenLocked(Context appContext) throws Exception {
        return Connection.open(appContext, AuthorizationPromptMode.NEVER);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void closeRuntimeConnectionLocked(Context context, String reason) {
        if (runtimeConnection == null) {
            return;
        }
        runtimeConnection.close();
        runtimeConnection = null;
        runtimeLastUsedMs = 0L;
        runtimeLog(context, "adb_runtime close reason=" + reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static ShellResult unauthorizedRuntimeShellResult() {
        return new ShellResult("ADB key is not authorized.", 126, "");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void runtimeLog(Context context, String message) {
        if (context != null) {
            AppEventLogger.event(context, message);
        }
    }

    //defines the Result module boundary so related behavior stays readable inside one unit.
    static final class Result {
        //defines the Code module boundary so related behavior stays readable inside one unit.
        enum Code {
            ALREADY_GRANTED,
            GRANTED,
            PARTIAL,
            AUTHORIZATION_REQUIRED,
            ADB_UNAVAILABLE,
            FAILED
        }

        final Code code;
        final String message;

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        private Result(Code code, String message) {
            this.code = code;
            this.message = message == null ? "" : message;
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean shouldRecheckPermissions() {
            return code == Code.ALREADY_GRANTED || code == Code.GRANTED || code == Code.PARTIAL;
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result alreadyGranted(String message) {
            return new Result(Code.ALREADY_GRANTED, message);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result granted(String message) {
            return new Result(Code.GRANTED, message);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result partial(String message) {
            return new Result(Code.PARTIAL, message);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result authorizationRequired(String message) {
            return new Result(Code.AUTHORIZATION_REQUIRED, message);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result adbUnavailable(String message) {
            return new Result(Code.ADB_UNAVAILABLE, message);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        static Result failed(String message) {
            return new Result(Code.FAILED, message);
        }

        //exposes this helper so parser behavior can be verified without depending on Android runtime state.
        static Result postGrantVerificationForTest(NavPermissionStatus after, String detail) {
            return postGrantVerification(after, detail);
        }

        //exposes this helper so parser behavior can be verified without depending on Android runtime state.
        static Result runtimeReconnectingAfterGrantForTest(
                NavRuntimePermissionStatus status, String endpointSuffix) {
            return runtimeReconnectingAfterGrant(status, endpointSuffix);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        private static Result postGrantVerification(NavPermissionStatus after, String detail) {
            String safeDetail = detail == null ? "" : detail.trim();
            if (after == null || !after.captureGranted()) {
                return Result.partial("ADB commands completed, but Android reports "
                        + (after == null ? "unknown permission state" : after.summary())
                        + (safeDetail.isEmpty() ? "" : ": " + safeDetail));
            }
            return Result.granted("ADB grant OK: " + after.summary()
                    + (safeDetail.isEmpty() ? "" : ": " + safeDetail));
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        private static Result runtimeReconnectingAfterGrant(
                NavRuntimePermissionStatus status, String endpointSuffix) {
            String safeEndpoint = endpointSuffix == null ? "" : endpointSuffix;
            String summary = status == null ? "unknown runtime state" : status.summary();
            return Result.partial("ADB grant incomplete" + safeEndpoint
                    + ": permissions granted; capture services reconnecting: " + summary);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static Result waitForRuntimeReady(Context appContext, String endpointSuffix)
            throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + POST_GRANT_POLL_TIMEOUT_MS;
        NavRuntimePermissionStatus last = NavRuntimePermissionStatus.check(appContext);
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            last = NavRuntimePermissionStatus.check(appContext);
            if (last.readyForCapture()) {
                return Result.granted("ADB grant OK" + endpointSuffix + ": " + last.summary());
            }
            Thread.sleep(POST_GRANT_POLL_INTERVAL_MS);
        }
        return Result.runtimeReconnectingAfterGrant(last, endpointSuffix);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static NavPermissionStatus waitForSettingsGranted(Context appContext)
            throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + POST_SETTINGS_POLL_TIMEOUT_MS;
        NavPermissionStatus last = NavPermissionStatus.check(appContext);
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            last = NavPermissionStatus.check(appContext);
            if (last.captureGranted()) {
                return last;
            }
            Thread.sleep(POST_GRANT_POLL_INTERVAL_MS);
        }
        return last;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String notificationAllowListenerCommand(String packageName) {
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                packageName,
                "",
                "",
                false,
                false,
                false);
        return plan.isValid()
                ? "cmd notification allow_listener '" + plan.notificationService + "'"
                : "";
    }

    //uses the installed canonical notification component for the live grant path.
    private static String notificationAllowListenerCommand(
            Context context, String packageName) {
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                context,
                packageName,
                "",
                "",
                false,
                false,
                false);
        return plan.isValid()
                ? "cmd notification allow_listener '" + plan.notificationService + "'"
                : "";
    }

    //defines the OpenResult module boundary so related behavior stays readable inside one unit.
    private static final class OpenResult {
        final Connection connection;
        final boolean authorizationRequired;
        final boolean authorizationPromptSent;
        final String endpointLabel;

        //opens the external boundary here so connection setup remains observable and retryable.
        private OpenResult(
                Connection connection,
                boolean authorizationRequired,
                boolean authorizationPromptSent,
                String endpointLabel) {
            this.connection = connection;
            this.authorizationRequired = authorizationRequired;
            this.authorizationPromptSent = authorizationPromptSent;
            this.endpointLabel = endpointLabel == null ? "" : endpointLabel;
        }
    }

    //defines the Connection module boundary so related behavior stays readable inside one unit.
    private static final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final KeyPair keyPair;
        private final boolean publishAuthorization;
        private int nextLocalId = 1;

        //opens the external boundary here so connection setup remains observable and retryable.
        private Connection(Socket socket, KeyPair keyPair) throws IOException {
            this(socket, keyPair, true);
        }

        private Connection(Socket socket, KeyPair keyPair, boolean publishAuthorization) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.keyPair = keyPair;
            this.publishAuthorization = publishAuthorization;
        }

        //opens the external boundary here so connection setup remains observable and retryable.
        static OpenResult open(
                Context context,
                AuthorizationPromptMode authorizationPromptMode) throws Exception {
            KeyPair keyPair = loadOrCreateKeyPair(context);
            String keyFingerprint = fingerprint(keyPair);
            long cancellationGeneration = authorizationCancellationGeneration();
            Log.i(TAG, "ADB bridge opening key=" + keyFingerprint);
            AppEventLogger.event(context, "adb_bridge open key=" + keyFingerprint);
            String endpoint = endpointLabel(PORT);
            Socket socket = new Socket();
            try {
                AppEventLogger.event(context, "adb_bridge connect_attempt endpoint=" + endpoint);
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                AppEventLogger.event(context, "adb_bridge connected endpoint=" + endpoint);
                return openConnectedSocket(
                        context,
                        authorizationPromptMode,
                        keyPair,
                        keyFingerprint,
                        socket,
                        endpoint,
                        cancellationGeneration);
            } catch (IOException e) {
                closeQuietly(socket);
                AppEventLogger.event(context, "adb_bridge connect_failed endpoint="
                        + endpoint + " error=" + e.getClass().getSimpleName());
                throw new IOException("Unable to connect to ADB bridge; tried " + endpoint, e);
            }
        }

        //opens the external boundary here so connection setup remains observable and retryable.
        private static OpenResult openConnectedSocket(
                Context context,
                AuthorizationPromptMode authorizationPromptMode,
                KeyPair keyPair,
                String keyFingerprint,
                Socket socket,
                String endpoint,
                long cancellationGeneration) throws Exception {
            return openConnectedSocket(context, authorizationPromptMode, keyPair, keyFingerprint,
                    socket, endpoint, cancellationGeneration, true);
        }

        private static OpenResult openConnectedSocket(
                Context context, AuthorizationPromptMode authorizationPromptMode,
                KeyPair keyPair, String keyFingerprint, Socket socket, String endpoint,
                long cancellationGeneration, boolean publishAuthorization) throws Exception {
            Connection connection = new Connection(socket, keyPair, publishAuthorization);
            byte[] banner = nulPayload("host::");
            AdbPacket.write(
                    connection.out,
                    AdbPacket.A_CNXN,
                    AdbPacket.VERSION,
                    AdbPacket.MAX_DATA,
                    banner);
            boolean signatureSent = false;
            boolean publicKeySent = false;
            while (true) {
                AdbPacket packet;
                try {
                    packet = AdbPacket.read(connection.in);
                } catch (SocketTimeoutException e) {
                    if (publicKeySent) {
                        connection.close();
                        return new OpenResult(null, true, true, endpoint);
                    }
                    throw e;
                }
                if (packet.command == AdbPacket.A_CNXN) {
                    if (publishAuthorization) {
                        markAuthorizedFingerprint(context, keyFingerprint);
                        clearPendingAuthorization(socket);
                    }
                    return new OpenResult(connection, false, publicKeySent, endpoint);
                }
                if (packet.command != AdbPacket.A_AUTH
                        || packet.arg0 != AdbPacket.AUTH_TOKEN) {
                    connection.close();
                    throw new IOException("Unexpected ADB auth packet");
                }
                if (!signatureSent) {
                    AdbPacket.write(
                            connection.out,
                            AdbPacket.A_AUTH,
                            AdbPacket.AUTH_SIGNATURE,
                            0,
                            signAuthToken(keyPair.getPrivate(), packet.payload));
                    signatureSent = true;
                    continue;
                }
                if (!publicKeySent) {
                    boolean persistedAuthorizationRejected = publishAuthorization && keyFingerprint.equals(
                            prefs(context).getString(KEY_AUTHORIZED_FINGERPRINT, ""));
                    if (persistedAuthorizationRejected) {
                        clearAuthorizedFingerprint(context, keyFingerprint);
                    }
                    if (!publishAuthorization || !shouldSendPublicKeyForMode(authorizationPromptMode)) {
                        connection.close();
                        return new OpenResult(null, true, false, endpoint);
                    }
                    String publicKey = AdbKeyFormatter.formatPublicKey(
                            (RSAPublicKey) keyPair.getPublic());
                    Log.i(TAG, "ADB public key sent key=" + keyFingerprint);
                    AppEventLogger.event(context, "adb_bridge public_key_sent key=" + keyFingerprint);
                    if (!trackPendingAuthorization(socket, cancellationGeneration)) {
                        connection.close();
                        return new OpenResult(null, true, false, endpoint);
                    }
                    AdbPacket.write(
                            connection.out,
                            AdbPacket.A_AUTH,
                            AdbPacket.AUTH_RSAPUBLICKEY,
                            0,
                            nulPayload(publicKey));
                    publicKeySent = true;
                    socket.setSoTimeout(AUTH_PROMPT_TIMEOUT_MS);
                    continue;
                }
                connection.close();
                return new OpenResult(null, true, publicKeySent, endpoint);
            }
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        ShellResult shellWithExit(String command) throws IOException {
            return shellWithExit(command, 0);
        }

        //keeps diagnostic output bounded while retaining the shell exit marker at the tail.
        ShellResult shellWithExit(String command, int maxOutputBytes) throws IOException {
            String wrapped = command + "; echo " + EXIT_MARKER + "$?";
            ShellCapture capture = shell(wrapped, maxOutputBytes);
            return ShellResult.parse(capture.raw, capture.truncated, capture.droppedBytes);
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        private ShellCapture shell(String command, int maxOutputBytes) throws IOException {
            return shell(command, new OutputAccumulator(maxOutputBytes));
        }

        private ShellCapture shell(String command, OutputAccumulator output) throws IOException {
            int localId = nextLocalId++;
            int remoteId = 0;
            AdbPacket.write(out, AdbPacket.A_OPEN, localId, 0, nulPayload("shell:" + command));
            while (true) {
                AdbPacket packet = AdbPacket.read(in);
                if (packet.arg1 != localId) {
                    handleStalePacket(packet);
                    continue;
                }
                if (packet.command == AdbPacket.A_OKAY) {
                    remoteId = packet.arg0;
                } else if (packet.command == AdbPacket.A_WRTE) {
                    if (remoteId == 0) {
                        remoteId = packet.arg0;
                    }
                    output.append(packet.payload);
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, new byte[0]);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) {
                        remoteId = packet.arg0;
                    }
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                    return output.capture();
                }
            }
        }

        /** Forwards one shell stream packet-by-packet without an aggregate output buffer. */
        private void streamShell(String command, OutputStream output) throws IOException {
            int localId = nextLocalId++;
            int remoteId = 0;
            AdbPacket.write(out, AdbPacket.A_OPEN, localId, 0, nulPayload("shell:" + command));
            while (true) {
                AdbPacket packet = AdbPacket.read(in);
                if (packet.arg1 != localId) {
                    handleStalePacket(packet);
                    continue;
                }
                if (packet.command == AdbPacket.A_OKAY) {
                    remoteId = packet.arg0;
                } else if (packet.command == AdbPacket.A_WRTE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    output.write(packet.payload);
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, new byte[0]);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                    output.flush();
                    return;
                } else {
                    throw new IOException("Unexpected ADB Logcat packet");
                }
            }
        }

        //Uses the ADB sync RECV protocol so arbitrary file bytes never pass through shell text.
        long readFile(String path, OutputStream output, long expectedBytes,
                LongConsumer progress) throws IOException {
            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            if (pathBytes.length == 0 || pathBytes.length > 4096) {
                throw new IOException("ADB sync path length out of range");
            }
            int localId = nextLocalId++;
            int remoteId = 0;
            AdbPacket.write(out, AdbPacket.A_OPEN, localId, 0, nulPayload("sync:"));
            AdbSyncReader reader = new AdbSyncReader(output, expectedBytes, progress);
            boolean requestSent = false;
            while (true) {
                AdbPacket packet = AdbPacket.read(in);
                if (packet.arg1 != localId) {
                    handleStalePacket(packet);
                    continue;
                }
                if (packet.command == AdbPacket.A_OKAY) {
                    if (remoteId != 0 && remoteId != packet.arg0) {
                        throw new IOException("ADB sync remote id changed");
                    }
                    remoteId = packet.arg0;
                    if (!requestSent) {
                        AdbPacket.write(out, AdbPacket.A_WRTE, localId, remoteId,
                                syncRecvRequest(pathBytes));
                        requestSent = true;
                    }
                } else if (packet.command == AdbPacket.A_WRTE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    try {
                        reader.accept(packet.payload);
                    } catch (AdbSyncReader.FileUnavailableException sourceFailure) {
                        AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, new byte[0]);
                        AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                        throw sourceFailure;
                    }
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, new byte[0]);
                    if (reader.isDone()) {
                        //Sync services may hold CLSE until the client closes this stream.
                        AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                        return reader.copiedBytes();
                    }
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    reader.finish();
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                    return reader.copiedBytes();
                } else {
                    throw new IOException("Unexpected ADB sync packet");
                }
            }
        }

        private static byte[] syncRecvRequest(byte[] pathBytes) throws IOException {
            ByteArrayOutputStream request = new ByteArrayOutputStream(pathBytes.length + 8);
            request.write('R');
            request.write('E');
            request.write('C');
            request.write('V');
            writeIntLe(request, pathBytes.length);
            request.write(pathBytes);
            return request.toByteArray();
        }

        private static void writeIntLe(OutputStream output, int value) throws IOException {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
            output.write((value >>> 16) & 0xff);
            output.write((value >>> 24) & 0xff);
        }

        //handles this branch here so source-specific edge cases stay out of the main flow.
        private void handleStalePacket(AdbPacket packet) throws IOException {
            if (packet.command == AdbPacket.A_WRTE) {
                AdbPacket.write(out, AdbPacket.A_OKAY, packet.arg1, packet.arg0, new byte[0]);
            } else if (packet.command == AdbPacket.A_CLSE) {
                AdbPacket.write(out, AdbPacket.A_CLSE, packet.arg1, packet.arg0, new byte[0]);
            }
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        void close() {
            if (publishAuthorization) closeQuietly(socket);
            else closeExportSocket(socket);
        }
    }

    private static final class ShellCapture {
        final String raw;
        final boolean truncated;
        final long droppedBytes;

        ShellCapture(String raw, boolean truncated, long droppedBytes) {
            this.raw = raw == null ? "" : raw;
            this.truncated = truncated;
            this.droppedBytes = Math.max(0L, droppedBytes);
        }
    }

    private static final class OutputAccumulator {
        private static final byte[] GAP = "\n[output truncated]\n".getBytes(StandardCharsets.UTF_8);
        private final int maxBytes;
        private final int prefixLimit;
        private final int tailLimit;
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        private final ByteArrayOutputStream tail = new ByteArrayOutputStream();
        private long totalBytes;
        private final boolean separateTruncation;

        OutputAccumulator(int maxBytes) {
            this(maxBytes, false);
        }

        OutputAccumulator(int maxBytes, boolean separateTruncation) {
            this.maxBytes = Math.max(0, maxBytes);
            this.separateTruncation = separateTruncation;
            tailLimit = Math.min(DIAGNOSTIC_OUTPUT_TAIL_BYTES, this.maxBytes);
            prefixLimit = this.maxBytes == 0
                    ? 0
                    : Math.max(0, this.maxBytes - tailLimit - (separateTruncation ? GAP.length : 0));
        }

        void append(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return;
            totalBytes += bytes.length;
            if (maxBytes == 0) {
                prefix.write(bytes, 0, bytes.length);
                return;
            }
            int remaining = prefixLimit - prefix.size();
            if (remaining > 0) {
                int keep = Math.min(remaining, bytes.length);
                prefix.write(bytes, 0, keep);
                if (keep == bytes.length) return;
                appendTail(bytes, keep, bytes.length - keep);
                return;
            }
            appendTail(bytes, 0, bytes.length);
        }

        private void appendTail(byte[] bytes, int offset, int length) {
            int newLength = Math.min(length, tailLimit);
            byte[] old = tail.toByteArray();
            int oldLength = Math.min(old.length, tailLimit - newLength);
            tail.reset();
            if (oldLength > 0) {
                tail.write(old, old.length - oldLength, oldLength);
            }
            if (newLength > 0) {
                tail.write(bytes, offset + length - newLength, newLength);
            }
        }

        ShellCapture capture() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(
                    prefix.size() + tail.size());
            raw.write(prefix.toByteArray());
            long dropped = Math.max(0L, totalBytes - prefix.size() - tail.size());
            if (separateTruncation && dropped > 0) raw.write(GAP);
            raw.write(tail.toByteArray());
            if (maxBytes == 0 || dropped == 0) {
                return new ShellCapture(raw.toString("UTF-8"), false, 0L);
            }
            return new ShellCapture(raw.toString("UTF-8"), true, dropped);
        }
    }

    //defines the ShellResult module boundary so related behavior stays readable inside one unit.
    static final class ShellResult {
        final String output;
        final int exitCode;
        final String raw;
        final boolean truncated;
        final long droppedBytes;
        final String status;
        final String error;
        final long observedAtMs;
        final long durationMs;

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        private ShellResult(String output, int exitCode, String raw) {
            this(output, exitCode, raw, false, 0L);
        }

        private ShellResult(
                String output, int exitCode, String raw,
                boolean truncated, long droppedBytes) {
            this(output, exitCode, raw, truncated, droppedBytes,
                    exitCode == 0 ? "success" : exitCode == 124 || exitCode == 137 ? "timeout"
                            : exitCode == 126 ? "denied" : exitCode == 127 ? "unsupported" : "error", "");
        }

        private ShellResult(String output, int exitCode, String raw,
                boolean truncated, long droppedBytes, String status, String error) {
            this(output, exitCode, raw, truncated, droppedBytes, status, error, -1L, -1L);
        }

        private ShellResult(String output, int exitCode, String raw,
                boolean truncated, long droppedBytes, String status, String error,
                long observedAtMs, long durationMs) {
            this.output = output == null ? "" : output;
            this.exitCode = exitCode;
            this.raw = raw == null ? "" : raw;
            this.truncated = truncated;
            this.droppedBytes = Math.max(0L, droppedBytes);
            this.status = status;
            this.error = error == null ? "" : error;
            this.observedAtMs = observedAtMs;
            this.durationMs = durationMs;
        }

        private ShellResult withTiming(long observedAtMs, long durationMs) {
            return new ShellResult(output, exitCode, raw, truncated, droppedBytes, status, error,
                    observedAtMs, Math.max(0L, durationMs));
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        boolean success() {
            return exitCode == 0;
        }

        //keeps this step explicit so callers can rely on one documented behavior boundary.
        String shortDetail() {
            String trimmed = output.trim();
            if (trimmed.length() > 160) {
                trimmed = trimmed.substring(0, 160) + "...";
            }
            return "exit=" + exitCode + " output=" + trimmed
                    + (truncated ? " truncatedBytes=" + droppedBytes : "")
                    + (error.isEmpty() ? "" : " status=" + status + " error=" + error);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static ShellResult parse(String raw) {
            return parse(raw, false, 0L);
        }

        static ShellResult parse(String raw, boolean truncated, long droppedBytes) {
            String safeRaw = raw == null ? "" : raw;
            int markerIndex = safeRaw.lastIndexOf(EXIT_MARKER);
            if (markerIndex < 0) {
                return new ShellResult(safeRaw.trim(), -1, safeRaw,
                        truncated, droppedBytes);
            }
            int codeStart = markerIndex + EXIT_MARKER.length();
            int codeEnd = codeStart;
            while (codeEnd < safeRaw.length()
                    && Character.isDigit(safeRaw.charAt(codeEnd))) {
                codeEnd++;
            }
            int exitCode = -1;
            try {
                exitCode = Integer.parseInt(safeRaw.substring(codeStart, codeEnd));
            } catch (NumberFormatException ignored) {
                exitCode = -1;
            }
            String output = safeRaw.substring(0, markerIndex).trim();
            return new ShellResult(output, exitCode, safeRaw, truncated, droppedBytes);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static byte[] signAuthToken(PrivateKey privateKey, byte[] token) throws Exception {
        byte[] safeToken = token == null ? new byte[0] : token;
        byte[] padded = Arrays.copyOf(ADB_AUTH_PADDING, ADB_AUTH_PADDING.length + safeToken.length);
        System.arraycopy(safeToken, 0, padded, ADB_AUTH_PADDING.length, safeToken.length);
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(privateKey);
        signature.update(padded);
        return signature.sign();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static KeyPair loadOrCreateKeyPair(Context context) throws Exception {
        KeyPair cached = cachedKeyPair;
        if (cached != null) {
            return cached;
        }
        synchronized (KEY_PAIR_LOCK) {
            cached = cachedKeyPair;
            if (cached != null) {
                return cached;
            }
            KeyPair loaded = loadOrCreateKeyPairUncached(context);
            cachedKeyPair = loaded;
            cachedKeyFingerprint = fingerprint(loaded);
            return loaded;
        }
    }

    private static KeyPair loadOrCreateKeyPairUncached(Context context) throws Exception {
        File keyDir = new File(context.getFilesDir(), KEY_DIR);
        File privateFile = new File(keyDir, PRIVATE_KEY_FILE);
        File publicFile = new File(keyDir, PUBLIC_KEY_FILE);
        KeyPair persisted = loadPersistedKeyPair(privateFile, publicFile);
        if (persisted != null) {
            Log.i(TAG, "ADB key loaded key=" + fingerprint(persisted));
            return persisted;
        }

        KeyPair repaired = loadPrivateOnlyKeyPair(privateFile);
        if (repaired != null) {
            writeKeyPairFiles(keyDir, privateFile, publicFile, repaired);
            Log.i(TAG, "ADB key repaired key=" + fingerprint(repaired));
            AppEventLogger.event(context, "adb_bridge key_repaired key=" + fingerprint(repaired));
            return repaired;
        }

        KeyPair migrated = loadPrivateOnlyKeyPair(new File(context.getFilesDir(), LEGACY_PRIVATE_KEY_FILE));
        if (migrated != null) {
            writeKeyPairFiles(keyDir, privateFile, publicFile, migrated);
            Log.i(TAG, "ADB key migrated key=" + fingerprint(migrated));
            AppEventLogger.event(context, "adb_bridge key_migrated key=" + fingerprint(migrated));
            return migrated;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        writeKeyPairFiles(keyDir, privateFile, publicFile, keyPair);
        Log.i(TAG, "ADB key generated key=" + fingerprint(keyPair));
        AppEventLogger.event(context, "adb_bridge key_generated key=" + fingerprint(keyPair));
        return keyPair;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static KeyPair loadPersistedKeyPair(File privateFile, File publicFile) throws Exception {
        return loadPersistedKeyPair(privateFile, publicFile, false);
    }

    private static KeyPair loadPersistedKeyPair(File privateFile, File publicFile, boolean exportOnly) throws Exception {
        if (!privateFile.exists() || !publicFile.exists()) {
            return null;
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readFile(privateFile, exportOnly ? 16384 : 0)));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(readFile(publicFile, exportOnly ? 16384 : 0)));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            if (!exportOnly) Log.w(TAG, "ADB persisted keypair load failed; trying private-key repair", e);
            return null;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static KeyPair loadPrivateOnlyKeyPair(File privateFile) throws Exception {
        if (!privateFile.exists()) {
            return null;
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readFile(privateFile)));
            RSAPrivateCrtKey privateCrtKey = (RSAPrivateCrtKey) privateKey;
            PublicKey publicKey = keyFactory.generatePublic(new RSAPublicKeySpec(
                    privateCrtKey.getModulus(),
                    privateCrtKey.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            Log.w(TAG, "ADB private key load failed: " + privateFile.getName(), e);
            return null;
        }
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static void writeKeyPairFiles(
            File keyDir,
            File privateFile,
            File publicFile,
            KeyPair keyPair) throws IOException {
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IOException("Unable to create ADB key directory");
        }
        writeFile(privateFile, keyPair.getPrivate().getEncoded());
        writeFile(publicFile, keyPair.getPublic().getEncoded());
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static byte[] readFile(File file) throws IOException {
        return readFile(file, 0);
    }

    private static byte[] readFile(File file, int maxBytes) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (maxBytes > 0 && bytes.size() + read > maxBytes) throw new IOException("Key file exceeds export limit");
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        } finally {
            in.close();
        }
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static void writeFile(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String fingerprint(KeyPair keyPair) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(keyPair.getPublic().getEncoded());
        StringBuilder builder = new StringBuilder();
        int bytes = Math.min(8, value.length);
        for (int i = 0; i < bytes; i++) {
            if (i > 0) {
                builder.append(':');
            }
            int b = value[i] & 0xff;
            if (b < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(b));
        }
        return builder.toString();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String endpointLabel(int port) {
        return HOST + ":" + port;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String endpointSummary() {
        return endpointLabel(PORT);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void closeQuietly(Socket socket) {
        clearPendingAuthorization(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            //closes the diagnostic bridge defensively because this path runs during failure cleanup.
        }
    }

    //records only completed ADB handshakes, not merely displayed RSA prompts.
    private static void markAuthorizedFingerprint(Context context, String keyFingerprint) {
        authorizationObservedAtMs = System.currentTimeMillis();
        verifiedFingerprintThisProcess = keyFingerprint == null ? "" : keyFingerprint.trim();
        prefs(context).edit()
                .putString(KEY_AUTHORIZED_FINGERPRINT,
                        verifiedFingerprintThisProcess)
                .apply();
        AppEventLogger.event(context, "adb_bridge authorized key=" + keyFingerprint);
        MainActivity.requestRuntimeStatusRefresh(context, true, "adb-authorization-verified");
    }

    private static void clearAuthorizedFingerprint(Context context, String keyFingerprint) {
        authorizationObservedAtMs = System.currentTimeMillis();
        verifiedFingerprintThisProcess = "";
        prefs(context).edit().remove(KEY_AUTHORIZED_FINGERPRINT).apply();
        AppEventLogger.event(context, "adb_bridge authorization_revoked key=" + keyFingerprint);
        MainActivity.requestRuntimeStatusRefresh(context, true, "adb-authorization-rejected");
    }

    //tracks only the socket blocked on RSA consent so shell sessions remain untouched.
    private static boolean trackPendingAuthorization(
            Socket socket,
            long cancellationGeneration) {
        synchronized (AUTHORIZATION_SOCKET_LOCK) {
            if (authorizationCancellationPending
                    || cancellationGeneration != authorizationCancellationGeneration) {
                return false;
            }
            pendingAuthorizationSocket = socket;
            return true;
        }
    }

    //captures the cancellation generation before connect so FORCE can invalidate that attempt.
    private static long authorizationCancellationGeneration() {
        synchronized (AUTHORIZATION_SOCKET_LOCK) {
            return authorizationCancellationGeneration;
        }
    }

    //clears the handoff marker only when the queued FORCE attempt owns the grant lock.
    static boolean clearPendingAuthorizationCancellation(long generation) {
        synchronized (AUTHORIZATION_SOCKET_LOCK) {
            if (generation != authorizationCancellationGeneration) {
                return false;
            }
            authorizationCancellationPending = false;
            return true;
        }
    }

    //prevents a completed or failed authorization attempt from cancelling a later retry.
    private static void clearPendingAuthorization(Socket socket) {
        synchronized (AUTHORIZATION_SOCKET_LOCK) {
            if (pendingAuthorizationSocket == socket) {
                pendingAuthorizationSocket = null;
            }
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static byte[] nulPayload(String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[value.length + 1];
        System.arraycopy(value, 0, payload, 0, value.length);
        return payload;
    }
}
