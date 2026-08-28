package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ShareCachePrivacySourceContractTest {
    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app").resolve(relative);
        if (!Files.isRegularFile(file)) file = root.resolve(relative);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    public void newShareArtifactsUseInternalCacheOnly() throws Exception {
        String share = source("src/main/java/com/bydhud/app/LogShareZip.java");
        String paths = source("src/main/res/xml/update_file_paths.xml");

        int writer = share.indexOf("static File writableShareDir(Context context)");
        int end = share.indexOf("private static boolean isShareArtifact", writer);
        String method = share.substring(writer, end);
        assertTrue(method.contains("context.getCacheDir()"));
        assertFalse(method.contains("getExternalCacheDir"));
        assertFalse(paths.contains("external-cache-path"));
        assertTrue(paths.contains("bydhud-log-shares-internal"));
    }

    @Test
    public void staleLegacyExternalArtifactsAreStillCleaned() throws Exception {
        String share = source("src/main/java/com/bydhud/app/LogShareZip.java");
        int cleanup = share.indexOf("static int cleanupStaleArtifacts(Context context)");
        int checkedDays = share.indexOf("private static List<String> checkedDays", cleanup);
        String method = share.substring(cleanup, checkedDays);
        assertTrue(method.contains("app.getExternalCacheDir()"));
        assertTrue(method.contains("app.getCacheDir()"));
    }
}
