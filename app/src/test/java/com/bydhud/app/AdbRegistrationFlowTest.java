package com.bydhud.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityOptionsCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import javax.crypto.Cipher;
import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        AdbRegistrationFlowTest.SocketBoundary.class,
        AdbRegistrationFlowTest.NotificationRuntime.class,
        AdbRegistrationFlowTest.AccessibilityRuntime.class,
        AdbRegistrationFlowTest.InstrumentRuntime.class,
        AdbRegistrationFlowTest.UiRefresh.class})
public class AdbRegistrationFlowTest {
    static final List<String> events = new ArrayList<>();
    static boolean accepted;
    static boolean reject;
    static Runnable beforeConsent;

    @Test public void rsaConsentPrecedesTargetedGrantsAndOtherPermissionWindows() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        events.clear();
        accepted = false;
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        ShadowEnvironment.setIsExternalStorageLegacy(true);
        String foreign = "other.app/other.app.Listener";
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", foreign);
        Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", foreign);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        ReflectionHelpers.setField(activity, "mainUiReady", true);
        ReflectionHelpers.setField(activity, "activityResumed", true);
        ReflectionHelpers.setField(activity, "activityWindowFocused", true);
        ReflectionHelpers.setField(activity, "autoAdbAuthorizationState", AdbAuthorizationUiPolicy.AutoState.RUNNING);
        activity.composeReportMainUiState("");
        ReflectionHelpers.setField(activity, "overlayPermissionLauncher", new ActivityResultLauncher<Intent>() {
            @Override public void launch(Intent intent, ActivityOptionsCompat options) {
                assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.getAction());
                assertTrue("settings must be read back before overlay", NavPermissionStatus.check(context).captureGranted());
                events.add("overlay");
            }
            @Override public void unregister() { }
            @Override public ActivityResultContract<Intent, ?> getContract() {
                return new ActivityResultContracts.StartActivityForResult();
            }
        });
        beforeConsent = () -> {
            ReflectionHelpers.callInstanceMethod(activity, "maybeRequestOverlayPermission");
            assertFalse(events.contains("overlay"));
            assertFalse(NavPermissionStatus.check(context).captureGranted());
            assertFalse(events.stream().anyMatch(e -> e.startsWith("shell:")));
        };
        String key = LocalAdbBridge.adbKeyFingerprint(context);
        reject = true;
        LocalAdbBridge.Result denied = LocalAdbBridge.grantNavCapturePermissions(context,
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
        assertEquals(denied.message, LocalAdbBridge.Result.Code.AUTHORIZATION_REQUIRED, denied.code);
        assertFalse(LocalAdbBridge.isCurrentKeyKnownAuthorized(context));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("shell:")));

        reject = false;
        LocalAdbBridge.Result granted = LocalAdbBridge.grantNavCapturePermissions(context,
                LocalAdbBridge.AuthorizationPromptMode.FORCE);
        assertEquals(granted.message, LocalAdbBridge.Result.Code.GRANTED, granted.code);
        assertTrue(LocalAdbBridge.isCurrentKeyVerifiedThisProcess(context));
        assertTrue(NavRuntimePermissionStatus.check(context).readyForCapture());
        assertEquals(key, LocalAdbBridge.adbKeyFingerprint(context));
        assertTrue(Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners").contains(foreign));
        assertTrue(Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services").contains(foreign));
        assertTrue(events.indexOf("accepted") < firstShell());
        assertTrue(events.stream().anyMatch(e -> e.contains("pm grant") && e.contains("READ_EXTERNAL_STORAGE")));
        assertTrue(events.stream().anyMatch(e -> e.contains("pm grant") && e.contains("WRITE_EXTERNAL_STORAGE")));
        ReflectionHelpers.callInstanceMethod(activity, "handleAdbGrantResult",
                from(LocalAdbBridge.Result.class, granted), from(long.class, 0L), from(boolean.class, false));
        ReflectionHelpers.callInstanceMethod(activity, "maybeRequestOverlayPermission");
        assertEquals("state=" + ReflectionHelpers.getField(activity, "autoAdbAuthorizationState")
                + " flow=" + ReflectionHelpers.getField(activity, "composeBlockingUiFlow")
                + " events=" + events, 1, Collections.frequency(events, "overlay"));
        assertTrue(events.indexOf("overlay") > firstShell());

        int prompts = Collections.frequency(events, "public-key");
        LocalAdbBridge.Result again = LocalAdbBridge.grantNavCapturePermissions(context);
        assertEquals(LocalAdbBridge.Result.Code.ALREADY_GRANTED, again.code);
        assertEquals(prompts, Collections.frequency(events, "public-key"));
        assertEquals(key, LocalAdbBridge.adbKeyFingerprint(context));
    }

    private static int firstShell() {
        for (int i = 0; i < events.size(); i++) if (events.get(i).startsWith("shell:")) return i;
        throw new AssertionError("no permission commands");
    }

    // Only replace the socket acquisition: production key persistence, handshake and grant flow execute.
    @Implements(className = "com.bydhud.app.LocalAdbBridge$Connection", isInAndroidSdk = false)
    public static class SocketBoundary {
        @Implementation protected static @ClassName("com.bydhud.app.LocalAdbBridge$OpenResult") Object open(
                Context context, LocalAdbBridge.AuthorizationPromptMode mode) throws Exception {
            KeyPair keys = ReflectionHelpers.callStaticMethod(LocalAdbBridge.class, "loadOrCreateKeyPair", from(Context.class, context));
            long generation = ReflectionHelpers.callStaticMethod(LocalAdbBridge.class, "authorizationCancellationGeneration");
            Class<?> connection = Class.forName("com.bydhud.app.LocalAdbBridge$Connection");
            return ReflectionHelpers.callStaticMethod(connection, "openConnectedSocket",
                    from(Context.class, context), from(LocalAdbBridge.AuthorizationPromptMode.class, mode),
                    from(KeyPair.class, keys), from(String.class, LocalAdbBridge.adbKeyFingerprint(context)),
                    from(Socket.class, new Peer(context, keys)), from(String.class, "memory-only"), from(long.class, generation));
        }
    }

    static final class Peer extends Socket {
        final ArrayDeque<Integer> input = new ArrayDeque<>();
        final Context context;
        final KeyPair keys;
        final byte[] token = new byte[20];
        Peer(Context context, KeyPair keys) { this.context = context; this.keys = keys; Arrays.fill(token, (byte) 42); }
        @Override public void setSoTimeout(int timeout) { }
        @Override public InputStream getInputStream() {
            return new InputStream() { public int read() { return input.isEmpty() ? -1 : input.remove(); } };
        }
        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                @Override public void flush() throws IOException {
                    AdbPacket packet = AdbPacket.read(new ByteArrayInputStream(toByteArray()));
                    reset();
                    try { receive(packet); } catch (Exception error) { throw new IOException(error); }
                }
            };
        }
        void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AdbPacket.write(bytes, command, arg0, arg1, payload);
            for (byte b : bytes.toByteArray()) input.add(b & 255);
        }
        void receive(AdbPacket p) throws Exception {
            if (p.command == AdbPacket.A_CNXN) {
                send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
            } else if (p.command == AdbPacket.A_AUTH && p.arg0 == AdbPacket.AUTH_SIGNATURE) {
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.DECRYPT_MODE, keys.getPublic());
                byte[] signed = rsa.doFinal(p.payload);
                assertArrayEquals(token, Arrays.copyOfRange(signed, signed.length - 20, signed.length));
                events.add("signature");
                send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
            } else if (p.command == AdbPacket.A_AUTH && p.arg0 == AdbPacket.AUTH_RSAPUBLICKEY) {
                assertEquals(AdbKeyFormatter.formatPublicKey((RSAPublicKey) keys.getPublic()),
                        new String(p.payload, StandardCharsets.UTF_8).replace("\0", ""));
                beforeConsent.run();
                events.add("public-key");
                if (reject) send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
                else {
                    accepted = true;
                    events.add("accepted");
                    send(AdbPacket.A_CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, new byte[0]);
                }
            } else if (p.command == AdbPacket.A_OPEN) {
                assertTrue("no grants before RSA consent", accepted);
                String command = new String(p.payload, StandardCharsets.UTF_8).replace("\0", "");
                command = command.substring("shell:".length(), command.indexOf("; echo "));
                events.add("shell:" + command);
                String output = execute(command);
                send(AdbPacket.A_OKAY, 1, p.arg0, new byte[0]);
                send(AdbPacket.A_WRTE, 1, p.arg0, (output + "\n__BYDHUD_EXIT__:0\n").getBytes(StandardCharsets.UTF_8));
                send(AdbPacket.A_CLSE, 1, p.arg0, new byte[0]);
            }
        }
        String execute(String command) {
            if (command.equals("dumpsys accessibility")) {
                NavRuntimePermissionStatus actual = NavRuntimePermissionStatus.check(context);
                assertTrue(actual.summary() + " commands=" + events, actual.readyForCapture());
                return "Enabled services: {com.bydhud.app/com.bydhud.app.NavAccessibilityService}\nBound services: {com.bydhud.app/com.bydhud.app.NavAccessibilityService}\nCrashed services: {}";
            }
            String[] words = command.replace("'", "").split(" ");
            if (command.startsWith("settings get secure "))
                return String.valueOf(Settings.Secure.getString(context.getContentResolver(), words[3]));
            if (command.startsWith("settings put secure ")) {
                if (words[3].equals("accessibility_enabled"))
                    Settings.Secure.putInt(context.getContentResolver(), words[3], Integer.parseInt(words[4]));
                else Settings.Secure.putString(context.getContentResolver(), words[3], words[4]);
            }
            else if (command.startsWith("pm grant "))
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(words[words.length - 1]);
            else if (!command.startsWith("cmd notification allow_listener ") && !command.startsWith("appops set "))
                throw new AssertionError("unexpected grant command: " + command);
            return "";
        }
    }

    @Implements(NavNotificationListenerService.class) public static class NotificationRuntime {
        @Implementation protected static boolean isConnectedForRuntimeCheck() { return true; }
        @Implementation protected static void requestRuntimeRebind(Context context, String reason) { }
    }
    @Implements(NavAccessibilityService.class) public static class AccessibilityRuntime {
        @Implementation protected static boolean isConnectedForRuntimeCheck() { return true; }
        @Implementation protected static boolean isCrashedForRuntimeCheck() { return false; }
    }
    @Implements(InstrumentProxyManager.class) public static class InstrumentRuntime {
        @Implementation protected void onAuthorizationVerified() { events.add("runtime-authorized"); }
    }
    @Implements(MainActivity.class) public static class UiRefresh extends org.robolectric.shadows.ShadowActivity {
        @Implementation protected static void requestRuntimeStatusRefresh(Context c, boolean force, String reason) { }
    }
}
