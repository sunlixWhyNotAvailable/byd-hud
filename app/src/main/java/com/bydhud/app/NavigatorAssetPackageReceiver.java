package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Refreshes cached navigator actions after supported packages are added or removed. */
public final class NavigatorAssetPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String packageName = data == null ? "" : data.getSchemeSpecificPart();
        NavigatorAssetManager.onCatalogPackageChanged(context, packageName);
    }
}
