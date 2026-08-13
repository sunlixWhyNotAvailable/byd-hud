package com.bydhud.app;

import android.os.Bundle;

oneway interface IInstrumentNavigationClient {
    void onProxyConnected(long generation, in Bundle result);
    void onProxyPong(long generation, long token);
    void onProxyStopping(long generation, String reason);
}
