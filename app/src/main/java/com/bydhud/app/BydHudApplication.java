package com.bydhud.app;

import android.app.Application;

/** One asynchronous cache catch-up per process, including service-only starts. */
public final class BydHudApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ConfigurationExportArtifacts.checkAsync(this);
    }
}
