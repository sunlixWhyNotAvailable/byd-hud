package com.bydhud.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ADB-only prerelease probe for Waze Transportation SDK route state. */
public final class WazeSdkProbeActivity extends Activity {
    private static final String TAG = "BydHudWazeSdkProbe";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final String WAZE_SDK_SERVICE = "com.waze.sdk.SdkService";
    private static final long DEFAULT_TIMEOUT_MS = 180_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger callback = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleCallback));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            log("service_connected", "component", String.valueOf(name));
            register(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            log("service_disconnected", "component", String.valueOf(name));
            complete("DISCONNECTED");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            log("binding_died", "component", String.valueOf(name));
            complete("BINDING_DIED");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            log("null_binding", "component", String.valueOf(name));
            complete("NULL_BINDING");
        }
    };

    private String runId = "unknown";
    private Messenger remote;
    private boolean bound;
    private boolean subscribed;
    private boolean cleanedUp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String mode = intent == null ? "start" : safe(intent.getStringExtra("mode"));
        if (!"stop".equalsIgnoreCase(mode) && !cleanedUp && (bound || remote != null)) {
            log("duplicate_start_refused", "requestedRunId",
                    intent == null ? "" : safe(intent.getStringExtra("runId")));
            return;
        }
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        String mode = intent == null ? "start" : safe(intent.getStringExtra("mode"));
        String requestedRunId = intent == null ? "" : safe(intent.getStringExtra("runId"));
        if (!requestedRunId.isEmpty()) runId = requestedRunId;
        if ("stop".equalsIgnoreCase(mode)) {
            complete("STOP_REQUESTED");
            return;
        }
        startProbe(intent == null ? DEFAULT_TIMEOUT_MS
                : Math.max(10_000L, intent.getLongExtra("timeoutMs", DEFAULT_TIMEOUT_MS)));
    }

    private void startProbe(long timeoutMs) {
        if (NavHudLiveSender.get(this).isWazeDirectChannelActiveForProbe()) {
            complete("REFUSED_DIRECT_CHANNEL_ACTIVE");
            return;
        }
        log("start",
                "appVersion", packageVersion(getPackageName()),
                "wazeVersion", packageVersion(WAZE_PACKAGE),
                "timeoutMs", String.valueOf(timeoutMs));
        Intent service = new Intent().setComponent(
                new ComponentName(WAZE_PACKAGE, WAZE_SDK_SERVICE));
        try {
            bound = bindService(service, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            log("bind_exception", "error", error.getClass().getSimpleName());
            complete("BIND_EXCEPTION");
            return;
        }
        if (!bound) {
            complete("BIND_FAILED");
            return;
        }
        handler.postDelayed(() -> complete("TIMEOUT"), timeoutMs);
    }

    private void register(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(WazeSdkProbeProtocol.SERVICE_DESCRIPTOR);
            data.writeString(getPackageName());
            Bundle configuration = new Bundle();
            data.writeInt(1);
            configuration.writeToParcel(data, 0);
            data.writeInt(1);
            callback.writeToParcel(data, 0);
            if (!binder.transact(WazeSdkProbeProtocol.TRANSACTION_REGISTER, data, reply, 0)) {
                complete("REGISTER_TRANSACTION_REJECTED");
                return;
            }
            reply.readException();
            if (reply.readInt() == 0) {
                complete("REJECTED_AT_REGISTRATION");
                return;
            }
            remote = Messenger.CREATOR.createFromParcel(reply);
            log("registered", "messenger", "present");
            sendSubscription(true);
        } catch (Throwable error) {
            log("register_exception", "error", error.getClass().getSimpleName());
            complete("REGISTER_EXCEPTION");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean handleCallback(Message message) {
        if (message == null) return true;
        try {
            Bundle data = message.getData();
            boolean hasNavigationState = data != null && data.containsKey("isNavigating");
            String verdict = WazeSdkProbeProtocol.callbackVerdict(
                    message.what, hasNavigationState);
            if (message.what == WazeSdkProbeProtocol.CALLBACK_NAVIGATION_STATUS) {
                log("callback",
                        "what", String.valueOf(message.what),
                        "verdict", verdict,
                        "isNavigating", hasNavigationState
                                ? String.valueOf(data.getBoolean("isNavigating")) : "missing",
                        "keys", bundleKeys(data));
                return true;
            }
            if (message.what == WazeSdkProbeProtocol.CALLBACK_SDK_ERROR) {
                int reason = data == null ? -1 : data.getInt("reason", -1);
                log("callback",
                        "what", String.valueOf(message.what),
                        "verdict", verdict,
                        "reason", String.valueOf(reason),
                        "keys", bundleKeys(data));
                complete(verdict + "_REASON_" + reason);
                return true;
            }
            log("callback",
                    "what", String.valueOf(message.what),
                    "verdict", verdict,
                    "keys", bundleKeys(data));
        } catch (Throwable error) {
            log("callback_exception", "error", error.getClass().getSimpleName());
            complete("CALLBACK_EXCEPTION");
        }
        return true;
    }

    private void sendSubscription(boolean request) throws RemoteException {
        if (remote == null) return;
        Message message = Message.obtain(
                null, WazeSdkProbeProtocol.MESSAGE_REQUEST_NAVIGATION_DATA);
        Bundle data = new Bundle();
        data.putString("token", getPackageName());
        data.putBoolean("request", request);
        message.setData(data);
        remote.send(message);
        subscribed = request;
        log("subscription", "request", String.valueOf(request));
    }

    private void complete(String verdict) {
        if (cleanedUp) return;
        log("complete", "verdict", verdict);
        cleanup();
        finishAndRemoveTask();
    }

    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        handler.removeCallbacksAndMessages(null);
        if (remote != null) {
            try {
                if (subscribed) sendSubscription(false);
                Message disconnect = Message.obtain(
                        null, WazeSdkProbeProtocol.MESSAGE_DISCONNECT);
                Bundle data = new Bundle();
                data.putString("token", getPackageName());
                disconnect.setData(data);
                remote.send(disconnect);
                log("disconnect_sent");
            } catch (RemoteException error) {
                log("cleanup_remote_exception", "error", error.getClass().getSimpleName());
            }
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException error) {
                log("cleanup_unbind_exception", "error", error.getClass().getSimpleName());
            }
            bound = false;
        }
    }

    private String packageVersion(String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return "missing";
        }
    }

    private void log(String phase, String... values) {
        StringBuilder json = new StringBuilder("{\"runId\":\"")
                .append(escape(runId))
                .append("\",\"phase\":\"").append(escape(phase)).append('"');
        for (int index = 0; index + 1 < values.length; index += 2) {
            json.append(",\"").append(escape(values[index])).append("\":\"")
                    .append(escape(values[index + 1])).append('"');
        }
        json.append('}');
        Log.i(TAG, json.toString());
    }

    private static String bundleKeys(Bundle bundle) {
        if (bundle == null || bundle.isEmpty()) return "";
        List<String> keys = new ArrayList<>(bundle.keySet());
        Collections.sort(keys);
        if (keys.size() > 16) keys = keys.subList(0, 16);
        return String.join(",", keys);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
