package com.bydhud.app;

import android.os.Bundle;
import com.bydhud.app.IInstrumentNavigationClient;

interface IInstrumentNavigationProxy {
    oneway void connect(long generation, String nonce, IInstrumentNavigationClient client);
    oneway void ping(long generation, long token);
    Bundle sendNavigationStatus(long generation, int status);
    Bundle sendGuidance(long generation, int icon, int distanceMeters, String road);
    oneway void shutdown(long generation);
}
