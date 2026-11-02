package com.bydhud.mapcapture;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

/** Applies the HUD palette before the source-built capture bridge dispatches the request. */
public final class LifecycleJournalBridge {
    public static Bundle poll(ContentResolver resolver, Uri uri, String method, String arg,
            Bundle hello) throws Exception {
        Bundle reply = resolver.call(uri, method, arg, hello);
        if (reply == null) throw new IllegalStateException("empty_collector_reply");
        if (reply.getBoolean("active")) com.bydhud.mapshud.HudScene.palette(reply.getIntArray(MapPalette.KEY));
        return reply;
    }
}
