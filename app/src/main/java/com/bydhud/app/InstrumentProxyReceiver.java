package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Accepts only the nonce-bound Binder handoff from the shell helper. */
public final class InstrumentProxyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !InstrumentProxyContract.ACTION_CONNECTED.equals(intent.getAction())) {
            return;
        }
        try {
            intent.setExtrasClassLoader(InstrumentProxyBinder.class.getClassLoader());
            InstrumentProxyBinder handoff = intent.getParcelableExtra(
                    InstrumentProxyContract.EXTRA_BINDER);
            if (handoff == null) return;
            if (intent.getBooleanExtra("recover_runtime", false)) {
                InstrumentProxyStore.Identity identity = InstrumentProxyStore.load(context);
                long generation = intent.getLongExtra(InstrumentProxyContract.EXTRA_GENERATION, -1L);
                if (!InstrumentProxyStore.canReconnect(context, identity)
                        || identity.generation != generation
                        || !identity.nonce.equals(intent.getStringExtra(InstrumentProxyContract.EXTRA_NONCE))) return;
                if (!ShellRuntimeSession.mayRestore(context)) {
                    // The app owns this launch, but the user has explicitly ended its session.
                    IInstrumentNavigationProxy.Stub.asInterface(handoff.binder()).shutdown(generation);
                    return;
                }
                UserRuntimeSession.PROCESS.activate();
                InstrumentProxyManager.get(context).awaitExistingRuntime();
                HudRuntimeService.startPersistent(context, "shell-binder-reentry");
            }
            InstrumentProxyManager.get(context).acceptHandoff(
                    intent.getLongExtra(InstrumentProxyContract.EXTRA_GENERATION, -1L),
                    intent.getStringExtra(InstrumentProxyContract.EXTRA_NONCE),
                    handoff.binder());
        } catch (android.os.RemoteException | RuntimeException error) {
            AppEventLogger.event(context,
                    "instrument_proxy malformed_handoff type="
                            + error.getClass().getSimpleName());
        }
    }
}
