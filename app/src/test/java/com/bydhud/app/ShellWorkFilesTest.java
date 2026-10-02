package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ShellWorkFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void shellStorageDoesNotInvokeAppAttributedStorageManager() {
        ShadowProcess.setUid(2000);
        Context app = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getExternalFilesDir(String type) {
                throw new SecurityException("callingPackage does not match UID");
            }
        };
        assertEquals(new File("/storage/emulated/" + app.getApplicationInfo().uid / 100000
                + "/Android/data/" + app.getPackageName() + "/files"), ShellWorkFiles.externalFiles(app));
    }

    @Test public void resultIsNotSettledUntilOwnerReleasesFileLock() throws Exception {
        File job = temporary.newFolder();
        ShellWorkFiles.write(new File(job, "result.json"), new JSONObject().put("ok", true));
        try (RandomAccessFile owner = new RandomAccessFile(new File(job, "owner.lock"), "rw");
             FileLock lock = owner.getChannel().lock()) {
            assertTrue(ShellWorkFiles.running(job));
            assertFalse(ShellWorkFiles.settled(job));
        }
        assertTrue(ShellWorkFiles.settled(job));
    }

    @Test public void durableBundleKeepsSmallLongsAndRejectsEscapingPaths() throws Exception {
        File root = temporary.newFolder();
        Bundle source = new Bundle(); source.putLong("generation", 7L);
        Bundle nested = new Bundle(); nested.putLong("elapsed", 50L); source.putBundle("state", nested);
        File message = new File(root, "message.json");
        ShellWorkFiles.write(message, ShellWorkFiles.json(source));
        Bundle restored = ShellWorkFiles.bundle(ShellWorkFiles.read(message));
        assertEquals(7L, restored.getLong("generation"));
        assertEquals(50L, restored.getBundle("state").getLong("elapsed"));
        assertEquals(message.getCanonicalFile(), ShellWorkFiles.inside(root, message.getPath()));
        assertThrows(IOException.class, () -> ShellWorkFiles.inside(root, new File(root, "../outside").getPath()));
        assertEquals(1, root.list().length);
    }
}
