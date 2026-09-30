package com.bydhud.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Exported, UID-checked endpoint used by the patched navigator producers. */
public final class NavigatorMapProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        NavigatorMapCapture.initialize(getContext());
        return getContext() != null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return NavigatorMapCapture.providerCall(
                getContext(), method, extras, Binder.getCallingUid());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
