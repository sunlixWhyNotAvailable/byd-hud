package com.bydhud.app;

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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

final class LocalAdbBridge {
    private static final String TAG = "BydHudAdbBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int AUTH_PROMPT_TIMEOUT_MS = 60000;
    private static final Object RUNTIME_CONNECTION_LOCK = new Object();
    private static final long RUNTIME_IDLE_CLOSE_MS = 30000L;
    private static final long POST_SETTINGS_POLL_TIMEOUT_MS = 3000L;
    private static final long POST_GRANT_POLL_TIMEOUT_MS = 30000L;
    private static final long POST_GRANT_POLL_INTERVAL_MS = 250L;
    private static final long ACCESSIBILITY_REBIND_STEP_DELAY_MS = 300L;
    private static final String KEY_DIR = "adb_keys";
    private static final String PRIVATE_KEY_FILE = "adb_key.priv";
    private static final String PUBLIC_KEY_FILE = "adb_key.pub";
    private static final String LEGACY_PRIVATE_KEY_FILE = "bydhud_adb_private.pk8";
    private static final String PREFS_NAME = "byd_hud_adb_bridge_prefs";
    private static final String KEY_AUTO_PROMPT_FINGERPRINT = "auto_prompt_fingerprint";
    private static final String EXIT_MARKER = "__BYDHUD_EXIT__:";
    private static final Pattern MOVE_STACK_COMMAND =
            Pattern.compile("cmd activity display move-stack [0-9]{1,6} [0-9]{1,3}");
    private static final String CAPTURE_SHELL_ROOT =
            "((/sdcard|/storage/emulated/0)/Documents/BYD-HUD/nav-capture"
                    + "|(/sdcard|/storage/emulated/0)/Android/data/com\\.bydhud\\.app/files/nav-capture)";
    private static final String CAPTURE_SESSION_PATH =
            "(/[0-9]{8})?/(waze-virtual-display|waze-crop)/[A-Za-z0-9_.-]{1,80}/screen_[0-9]{4}\\.png";
    private static final Pattern WAZE_SCREENSHOT_COMMAND = Pattern.compile(
            "screencap -d [0-9]{1,3} -p "
                    + CAPTURE_SHELL_ROOT
                    + CAPTURE_SESSION_PATH);
    private static final Pattern APP_LOCAL_RM_COMMAND = Pattern.compile(
            "rm "
                    + CAPTURE_SHELL_ROOT
                    + CAPTURE_SESSION_PATH);
    private static final byte[] ADB_AUTH_PADDING = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    };
    private static Connection runtimeConnection;
    private static long runtimeLastUsedMs;

    private LocalAdbBridge() {
    }

    enum AuthorizationPromptMode {
        AUTO_ONCE,
        FORCE,
        NEVER
    }

    static boolean shouldSendPublicKeyForMode(
            AuthorizationPromptMode mode,
            boolean autoPromptAlreadySentForKey) {
        if (mode == AuthorizationPromptMode.NEVER) {
            return false;
        }
        if (mode == AuthorizationPromptMode.AUTO_ONCE) {
            return !autoPromptAlreadySentForKey;
        }
        return true;
    }

    static List<String> adbEndpointLabelsForTest() {
        return Arrays.asList(endpointLabel(PORT));
    }

    static String adbEndpointSummaryForTest() {
        return endpointSummary();
    }

    static String adbKeyFingerprint(Context context) {
        try {
            return fingerprint(loadOrCreateKeyPair(context.getApplicationContext()));
        } catch (Exception e) {
            Log.w(TAG, "ADB key fingerprint unavailable", e);
            return "unavailable";
        }
    }

    static Result grantNavCapturePermissions(Context context) {
        return grantNavCapturePermissions(context, AuthorizationPromptMode.AUTO_ONCE);
    }

    static Result grantNavCapturePermissions(
            Context context,
            AuthorizationPromptMode authorizationPromptMode) {
        Context appContext = context.getApplicationContext();
        String normalizedPackage = appContext.getPackageName();
        NavPermissionStatus before = NavPermissionStatus.check(appContext);
        NavRuntimePermissionStatus runtimeBefore = NavRuntimePermissionStatus.check(appContext);
        if (runtimeBefore.readyForCapture()) {
            return Result.alreadyGranted(runtimeBefore.summary());
        }
        boolean grantNotificationListener = !before.notificationListenerEnabled
                || !runtimeBefore.notificationListenerConnected;
        boolean grantAccessibilityService = !before.accessibilityServiceEnabled;
        boolean grantAccessibilityMaster = !before.accessibilityMasterEnabled;
        boolean grantDashboardOverlay = !before.dashboardOverlayEnabled;

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
                    normalizedPackage,
                    notification.output,
                    accessibility.output,
                    grantNotificationListener,
                    grantAccessibilityService,
                    grantAccessibilityMaster,
                    grantDashboardOverlay);
            for (String command : plan.shellCommands) {
                ShellResult result = connection.shellWithExit(command);
                if (!result.success()) {
                    return Result.failed("ADB grant command failed: " + command
                            + " -> " + result.shortDetail());
                }
            }
            grantStoragePermissionsBestEffort(connection, appContext, normalizedPackage);
            if (grantNotificationListener) {
                ShellResult notificationAllow = connection.shellWithExit(
                        notificationAllowListenerCommand(normalizedPackage));
                if (notificationAllow.success()) {
                    AppEventLogger.event(appContext,
                            "adb_bridge notification_allow_listener success");
                } else {
                    AppEventLogger.event(appContext,
                            "adb_bridge notification_allow_listener failed "
                                    + notificationAllow.shortDetail());
                }
            }

            NavNotificationListenerService.requestRuntimeRebind(appContext, "adb-grant");
            Result accessibilityRebindResult = rebindAccessibilityRuntimeIfNeeded(
                    connection,
                    appContext,
                    plan.accessibilityServicesValue);
            if (accessibilityRebindResult != null) {
                return accessibilityRebindResult;
            }

            NavPermissionStatus after = waitForSettingsGranted(appContext);
            if (!after.allGranted()) {
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

    private static void grantStoragePermissionsBestEffort(
            Connection connection,
            Context appContext,
            String normalizedPackage) throws IOException {
        for (String command : storageGrantCommands(normalizedPackage)) {
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

    private static List<String> storageGrantCommands(String normalizedPackage) {
        return Arrays.asList(
                "pm grant " + normalizedPackage + " android.permission.READ_EXTERNAL_STORAGE",
                "pm grant " + normalizedPackage + " android.permission.WRITE_EXTERNAL_STORAGE",
                "appops set " + normalizedPackage + " READ_EXTERNAL_STORAGE allow",
                "appops set " + normalizedPackage + " WRITE_EXTERNAL_STORAGE allow",
                "appops set " + normalizedPackage + " LEGACY_STORAGE allow");
    }

    static ShellResult runRuntimeShellCommand(Context context, String command) throws IOException {
        String safeCommand = command == null ? "" : command.trim();
        if (!isAllowedRuntimeShellCommand(safeCommand)) {
            throw new SecurityException("ADB runtime command is not allowed: " + safeCommand);
        }
        Context appContext = context.getApplicationContext();
        synchronized (RUNTIME_CONNECTION_LOCK) {
            try {
                Connection connection = runtimeConnectionLocked(appContext);
                if (connection == null) {
                    return unauthorizedRuntimeShellResult();
                }
                ShellResult result = connection.shellWithExit(safeCommand);
                runtimeLastUsedMs = android.os.SystemClock.elapsedRealtime();
                return result;
            } catch (IOException e) {
                closeRuntimeConnectionLocked(appContext, "io_exception");
                try {
                    Connection connection = runtimeConnectionLocked(appContext);
                    if (connection == null) {
                        return unauthorizedRuntimeShellResult();
                    }
                    ShellResult result = connection.shellWithExit(safeCommand);
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

    private static Result rebindAccessibilityRuntimeIfNeeded(
            Connection connection,
            Context appContext,
            String targetAccessibilityServicesValue) throws IOException, InterruptedException {
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
        for (String command : NavPermissionGrantPlan.accessibilityRuntimeRebindCommands(
                appContext.getPackageName(),
                targetAccessibilityServicesValue)) {
            ShellResult result = connection.shellWithExit(command);
            if (!result.success()) {
                return Result.partial("Accessibility runtime rebind command failed: "
                        + command + " -> " + result.shortDetail());
            }
            Thread.sleep(ACCESSIBILITY_REBIND_STEP_DELAY_MS);
        }
        return null;
    }

    static boolean isAllowedRuntimeShellCommandForTest(String command) {
        return isAllowedRuntimeShellCommand(command == null ? "" : command.trim());
    }

    static String notificationAllowListenerCommandForTest(String packageName) {
        return notificationAllowListenerCommand(packageName);
    }

    private static boolean isAllowedRuntimeShellCommand(String command) {
        return "dumpsys display".equals(command)
                || "dumpsys activity activities".equals(command)
                || MOVE_STACK_COMMAND.matcher(command).matches()
                || WAZE_SCREENSHOT_COMMAND.matcher(command).matches()
                || APP_LOCAL_RM_COMMAND.matcher(command).matches();
    }

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

    private static OpenResult runtimeOpenLocked(Context appContext) throws Exception {
        return Connection.open(appContext, AuthorizationPromptMode.NEVER);
    }

    private static void closeRuntimeConnectionLocked(Context context, String reason) {
        if (runtimeConnection == null) {
            return;
        }
        runtimeConnection.close();
        runtimeConnection = null;
        runtimeLastUsedMs = 0L;
        runtimeLog(context, "adb_runtime close reason=" + reason);
    }

    private static ShellResult unauthorizedRuntimeShellResult() {
        return new ShellResult("ADB key is not authorized.", 126, "");
    }

    private static void runtimeLog(Context context, String message) {
        if (context != null) {
            AppEventLogger.event(context, message);
        }
    }

    static final class Result {
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

        private Result(Code code, String message) {
            this.code = code;
            this.message = message == null ? "" : message;
        }

        boolean shouldRecheckPermissions() {
            return code == Code.ALREADY_GRANTED || code == Code.GRANTED || code == Code.PARTIAL;
        }

        static Result alreadyGranted(String message) {
            return new Result(Code.ALREADY_GRANTED, message);
        }

        static Result granted(String message) {
            return new Result(Code.GRANTED, message);
        }

        static Result partial(String message) {
            return new Result(Code.PARTIAL, message);
        }

        static Result authorizationRequired(String message) {
            return new Result(Code.AUTHORIZATION_REQUIRED, message);
        }

        static Result adbUnavailable(String message) {
            return new Result(Code.ADB_UNAVAILABLE, message);
        }

        static Result failed(String message) {
            return new Result(Code.FAILED, message);
        }

        static Result postGrantVerificationForTest(NavPermissionStatus after, String detail) {
            return postGrantVerification(after, detail);
        }

        static Result runtimeReconnectingAfterGrantForTest(
                NavRuntimePermissionStatus status, String endpointSuffix) {
            return runtimeReconnectingAfterGrant(status, endpointSuffix);
        }

        private static Result postGrantVerification(NavPermissionStatus after, String detail) {
            String safeDetail = detail == null ? "" : detail.trim();
            if (after == null || !after.allGranted()) {
                return Result.partial("ADB commands completed, but Android reports "
                        + (after == null ? "unknown permission state" : after.summary())
                        + (safeDetail.isEmpty() ? "" : ": " + safeDetail));
            }
            return Result.granted("ADB grant OK: " + after.summary()
                    + (safeDetail.isEmpty() ? "" : ": " + safeDetail));
        }

        private static Result runtimeReconnectingAfterGrant(
                NavRuntimePermissionStatus status, String endpointSuffix) {
            String safeEndpoint = endpointSuffix == null ? "" : endpointSuffix;
            String summary = status == null ? "unknown runtime state" : status.summary();
            return Result.partial("ADB grant incomplete" + safeEndpoint
                    + ": permissions granted; capture services reconnecting: " + summary);
        }
    }

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

    private static NavPermissionStatus waitForSettingsGranted(Context appContext)
            throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + POST_SETTINGS_POLL_TIMEOUT_MS;
        NavPermissionStatus last = NavPermissionStatus.check(appContext);
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            last = NavPermissionStatus.check(appContext);
            if (last.allGranted()) {
                return last;
            }
            Thread.sleep(POST_GRANT_POLL_INTERVAL_MS);
        }
        return last;
    }

    private static String notificationAllowListenerCommand(String packageName) {
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                packageName,
                "",
                "",
                false,
                false,
                false,
                false);
        return "cmd notification allow_listener " + plan.notificationService;
    }

    private static final class OpenResult {
        final Connection connection;
        final boolean authorizationRequired;
        final boolean authorizationPromptSent;
        final String endpointLabel;

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

    private static final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final KeyPair keyPair;
        private int nextLocalId = 1;

        private Connection(Socket socket, KeyPair keyPair) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.keyPair = keyPair;
        }

        static OpenResult open(
                Context context,
                AuthorizationPromptMode authorizationPromptMode) throws Exception {
            KeyPair keyPair = loadOrCreateKeyPair(context);
            String keyFingerprint = fingerprint(keyPair);
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
                        endpoint);
            } catch (IOException e) {
                closeQuietly(socket);
                AppEventLogger.event(context, "adb_bridge connect_failed endpoint="
                        + endpoint + " error=" + e.getClass().getSimpleName());
                throw new IOException("Unable to connect to ADB bridge; tried " + endpoint, e);
            }
        }

        private static OpenResult openConnectedSocket(
                Context context,
                AuthorizationPromptMode authorizationPromptMode,
                KeyPair keyPair,
                String keyFingerprint,
                Socket socket,
                String endpoint) throws Exception {
            Connection connection = new Connection(socket, keyPair);
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
                    if (!shouldSendPublicKeyForMode(
                            authorizationPromptMode,
                            autoPromptAlreadySentForKey(context, keyFingerprint))) {
                        connection.close();
                        return new OpenResult(null, true, false, endpoint);
                    }
                    String publicKey = AdbKeyFormatter.formatPublicKey(
                            (RSAPublicKey) keyPair.getPublic());
                    Log.i(TAG, "ADB public key sent key=" + keyFingerprint);
                    AppEventLogger.event(context, "adb_bridge public_key_sent key=" + keyFingerprint);
                    markAuthorizationPromptSent(context, keyFingerprint);
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

        ShellResult shellWithExit(String command) throws IOException {
            String wrapped = command + "; echo " + EXIT_MARKER + "$?";
            String output = shell(wrapped);
            return ShellResult.parse(output);
        }

        private String shell(String command) throws IOException {
            int localId = nextLocalId++;
            int remoteId = 0;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
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
                    output.write(packet.payload, 0, packet.payload.length);
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, new byte[0]);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) {
                        remoteId = packet.arg0;
                    }
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, new byte[0]);
                    return output.toString("UTF-8");
                }
            }
        }

        private void handleStalePacket(AdbPacket packet) throws IOException {
            if (packet.command == AdbPacket.A_WRTE) {
                AdbPacket.write(out, AdbPacket.A_OKAY, packet.arg1, packet.arg0, new byte[0]);
            } else if (packet.command == AdbPacket.A_CLSE) {
                AdbPacket.write(out, AdbPacket.A_CLSE, packet.arg1, packet.arg0, new byte[0]);
            }
        }

        void close() {
            closeQuietly(socket);
        }
    }

    static final class ShellResult {
        final String output;
        final int exitCode;
        final String raw;

        private ShellResult(String output, int exitCode, String raw) {
            this.output = output == null ? "" : output;
            this.exitCode = exitCode;
            this.raw = raw == null ? "" : raw;
        }

        boolean success() {
            return exitCode == 0;
        }

        String shortDetail() {
            String trimmed = output.trim();
            if (trimmed.length() > 160) {
                trimmed = trimmed.substring(0, 160) + "...";
            }
            return "exit=" + exitCode + " output=" + trimmed;
        }

        static ShellResult parse(String raw) {
            String safeRaw = raw == null ? "" : raw;
            int markerIndex = safeRaw.lastIndexOf(EXIT_MARKER);
            if (markerIndex < 0) {
                return new ShellResult(safeRaw.trim(), -1, safeRaw);
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
            return new ShellResult(output, exitCode, safeRaw);
        }
    }

    static byte[] signAuthToken(PrivateKey privateKey, byte[] token) throws Exception {
        byte[] safeToken = token == null ? new byte[0] : token;
        byte[] padded = Arrays.copyOf(ADB_AUTH_PADDING, ADB_AUTH_PADDING.length + safeToken.length);
        System.arraycopy(safeToken, 0, padded, ADB_AUTH_PADDING.length, safeToken.length);
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(privateKey);
        signature.update(padded);
        return signature.sign();
    }

    private static KeyPair loadOrCreateKeyPair(Context context) throws Exception {
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

    private static KeyPair loadPersistedKeyPair(File privateFile, File publicFile) throws Exception {
        if (!privateFile.exists() || !publicFile.exists()) {
            return null;
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readFile(privateFile)));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(readFile(publicFile)));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            Log.w(TAG, "ADB persisted keypair load failed; trying private-key repair", e);
            return null;
        }
    }

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

    private static byte[] readFile(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

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

    private static boolean autoPromptAlreadySentForKey(Context context, String keyFingerprint) {
        return autoPromptKey(keyFingerprint).equals(
                prefs(context).getString(KEY_AUTO_PROMPT_FINGERPRINT, ""));
    }

    private static void markAuthorizationPromptSent(Context context, String keyFingerprint) {
        prefs(context).edit()
                .putString(KEY_AUTO_PROMPT_FINGERPRINT, autoPromptKey(keyFingerprint))
                .apply();
    }

    private static String autoPromptKey(String keyFingerprint) {
        String safeFingerprint = keyFingerprint == null ? "unknown" : keyFingerprint.trim();
        if (safeFingerprint.isEmpty()) {
            safeFingerprint = "unknown";
        }
        return BuildConfig.VERSION_CODE + ":" + safeFingerprint;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String endpointLabel(int port) {
        return HOST + ":" + port;
    }

    private static String endpointSummary() {
        return endpointLabel(PORT);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a best-effort local diagnostic bridge.
        }
    }

    private static byte[] nulPayload(String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[value.length + 1];
        System.arraycopy(value, 0, payload, 0, value.length);
        return payload;
    }
}
