package com.bydhud.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;

/** Job-local scratch and preferences. Never exposes the application's private directory. */
final class ShellWorkContext extends ContextWrapper {
    final File job;
    private final Context preferencesContext;

    ShellWorkContext(Context base, Context preferencesContext, File job) throws IOException {
        super(base);
        this.job = job;
        this.preferencesContext = preferencesContext;
        ShellWorkFiles.directory(getFilesDir());
        ShellWorkFiles.directory(getCacheDir());
    }

    @Override public Context getApplicationContext() { return this; }
    @Override public File getFilesDir() { return new File(job, "files"); }
    @Override public File getCacheDir() { return new File(job, "cache"); }
    @Override public File getExternalCacheDir() { return getCacheDir(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid preference name");
        try {
            // Framework implementation supplies atomic XML writes; this file belongs only to this job.
            java.lang.reflect.Method method = preferencesContext.getClass()
                    .getMethod("getSharedPreferences", File.class, int.class);
            method.setAccessible(true);
            return (SharedPreferences) method.invoke(preferencesContext,
                    new File(getFilesDir(), name + ".xml"), mode);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}
