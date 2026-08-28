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
            InstrumentProxyManager.get(context).acceptHandoff(
                    intent.getLongExtra(InstrumentProxyContract.EXTRA_GENERATION, -1L),
                    intent.getStringExtra(InstrumentProxyContract.EXTRA_NONCE),
                    handoff.binder());
        } catch (RuntimeException error) {
            AppEventLogger.event(context,
                    "instrument_proxy malformed_handoff type="
                            + error.getClass().getSimpleName());
        }
    }
}
