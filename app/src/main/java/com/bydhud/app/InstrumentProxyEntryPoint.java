package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.Process;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;

/** app_process entry point for the private shell-UID Instrument navigation helper. */
public final class InstrumentProxyEntryPoint {
    private static final String TAG = "BydHudInstrumentProxy";
    private static final int SHELL_UID = 2000;

    private InstrumentProxyEntryPoint() {
    }

    public static void main(String[] arguments) {
        Args args = Args.parse(arguments);
        long generation = args.valid ? args.generation : -1L;
        if (!args.valid) {
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.ENTRY_REJECTED,
                    InstrumentProxyStartupLog.Outcome.INVALID_ARGUMENTS);
            Log.e(TAG, "refusing invalid proxy launch arguments");
            return;
        }
        if (Process.myUid() != SHELL_UID) {
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.ENTRY_REJECTED,
                    InstrumentProxyStartupLog.Outcome.WRONG_UID);
            Log.e(TAG, "refusing invalid proxy launch uid=" + Process.myUid());
            return;
        }
        InstrumentProxyStartupLog.record(generation,
                InstrumentProxyStartupLog.Stage.ENTRY_VALIDATED,
                InstrumentProxyStartupLog.Outcome.OK);
        InstrumentProxyStartupLog.Stage stage =
                InstrumentProxyStartupLog.Stage.LOOPER_PREPARE;
        try {
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.STARTED);
            Looper.prepareMainLooper();
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.OK);
            stage = InstrumentProxyStartupLog.Stage.CONTEXT_CREATE;
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.STARTED);
            Context systemContext = systemContext();
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.CONTEXT_READY,
                    InstrumentProxyStartupLog.Outcome.OK);
            stage = InstrumentProxyStartupLog.Stage.SERVICE_CREATE;
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.STARTED);
            InstrumentNavigationProxyService proxy = new InstrumentNavigationProxyService(
                    systemContext, args.generation, args.nonce, args.appUid,
                    args.launchToken, args.versionCode);
            InstrumentProxyStartupLog.record(generation,
                    InstrumentProxyStartupLog.Stage.SERVICE_READY,
                    InstrumentProxyStartupLog.Outcome.OK);
            Intent connected = new Intent(InstrumentProxyContract.ACTION_CONNECTED);
            connected.setPackage("com.bydhud.app");
            connected.putExtra(InstrumentProxyContract.EXTRA_GENERATION, args.generation);
            connected.putExtra(InstrumentProxyContract.EXTRA_NONCE, args.nonce);
            connected.putExtra(InstrumentProxyContract.EXTRA_BINDER,
                    new InstrumentProxyBinder(proxy.asBinder()));
            stage = InstrumentProxyStartupLog.Stage.BINDER_HANDOFF;
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.STARTED);
            systemContext.sendBroadcast(connected);
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.OK);
            Log.i(TAG, "proxy handoff sent generation=" + args.generation);
            new Handler(Looper.getMainLooper()).postDelayed(
                    proxy::stopIfUnconnected, 10_000L);
            stage = InstrumentProxyStartupLog.Stage.LOOPER;
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.STARTED);
            Looper.loop();
            InstrumentProxyStartupLog.record(generation, stage,
                    InstrumentProxyStartupLog.Outcome.OK);
        } catch (Throwable error) {
            InstrumentProxyStartupLog.recordException(generation, stage, error);
            Log.e(TAG, "proxy startup failed", error);
        }
    }

    @SuppressLint("PrivateApi")
    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }

    private static final class Args {
        final long generation;
        final String nonce;
        final int appUid;
        final String launchToken;
        final int versionCode;
        final boolean valid;

        private Args(long generation, String nonce, int appUid,
                String launchToken, int versionCode, boolean valid) {
            this.generation = generation;
            this.nonce = nonce;
            this.appUid = appUid;
            this.launchToken = launchToken;
            this.versionCode = versionCode;
            this.valid = valid;
        }

        static Args parse(String[] arguments) {
            long generation = -1L;
            int appUid = -1;
            int versionCode = -1;
            String nonce = "";
            String launchToken = "";
            if (arguments != null) {
                for (String argument : arguments) {
                    String value = argument == null ? "" : argument.trim();
                    try {
                        if (value.startsWith("--generation=")) {
                            generation = Long.parseLong(value.substring(13));
                        } else if (value.startsWith("--app-uid=")) {
                            appUid = Integer.parseInt(value.substring(10));
                        } else if (value.startsWith("--nonce=")) {
                            nonce = value.substring(8);
                        } else if (value.startsWith("--launch-token=")) {
                            launchToken = value.substring(15);
                        } else if (value.startsWith("--version-code=")) {
                            versionCode = Integer.parseInt(value.substring(15));
                        }
                    } catch (NumberFormatException ignored) {
                        return new Args(-1L, "", -1, "", -1, false);
                    }
                }
            }
            boolean valid = generation > 0L && appUid >= 10_000
                    && nonce.matches("[0-9a-f]{32}")
                    && InstrumentProxyContract.validLaunchToken(launchToken)
                    && versionCode > 0;
            return new Args(generation, nonce, appUid,
                    launchToken, versionCode, valid);
        }
    }
}
