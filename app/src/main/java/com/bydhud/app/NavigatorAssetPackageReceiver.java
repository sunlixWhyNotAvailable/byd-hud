package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Refreshes navigator actions and native ETA receiver availability after package changes. */
public final class NavigatorAssetPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String packageName = data == null ? "" : data.getSchemeSpecificPart();
        if (intent != null && !Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) {
            NavigatorAssetManager.onCatalogPackageChanged(context, packageName);
        }
        NavHudLiveSender.onNavigatorPackageChanged(packageName);
    }
}
