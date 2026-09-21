package com.bydhud.app;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/** One asynchronous cache catch-up per process, including service-only starts. */
public final class BydHudApplication extends Application {
    private final NavigatorAssetPackageReceiver navigatorAssetPackageReceiver =
            new NavigatorAssetPackageReceiver();
    private boolean navigatorAssetPackageReceiverRegistered;

    @Override public void onCreate() {
        super.onCreate();
        ConfigurationExportArtifacts.checkAsync(this);
        if (!getPackageName().equals(Application.getProcessName())) return;
        BootCleanupGate.initialize(this);
        ShanghaiTestController.get(this).recoverOwned("process-start");
        AppUpdateManager.initialize(this);
        UpdateHintManager.initialize(this);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        ContextCompat.registerReceiver(
                this,
                navigatorAssetPackageReceiver,
                packages,
                ContextCompat.RECEIVER_EXPORTED);
        navigatorAssetPackageReceiverRegistered = true;
    }

    @Override public void onTerminate() {
        if (navigatorAssetPackageReceiverRegistered) {
            unregisterReceiver(navigatorAssetPackageReceiver);
            navigatorAssetPackageReceiverRegistered = false;
        }
        super.onTerminate();
    }
}
