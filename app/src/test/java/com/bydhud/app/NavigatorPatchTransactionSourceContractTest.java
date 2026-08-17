package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the crash-safe transaction-directory publication order in preparation. */
public final class NavigatorPatchTransactionSourceContractTest {
    @Test
    public void transactionDirectoryIsPublishedBeforeInterruptibleOutputVerification()
            throws IOException {
        String source = source("NavigatorPatchPipeline.java");
        String prepare = between(source,
                "static PreparedPatch prepare(Context context, NavigatorPatchStore.Profile profile)",
                "static void deleteTree(");

        int mkdir = prepare.indexOf("if (!transaction.mkdirs())");
        int persist = prepare.indexOf(
                "NavigatorPatchStore.setTransactionDirectory(context, profile, transaction)");
        int outputVerify = prepare.indexOf("NavigatorPatchStore.OUTPUT_VERIFY");
        int setTransaction = prepare.indexOf("NavigatorPatchStore.setTransaction(");

        assertTrue("transaction directory must be created", mkdir >= 0);
        assertTrue("transaction directory must be persisted after mkdir", persist > mkdir);
        assertTrue("directory persistence must precede OUTPUT_VERIFY", outputVerify > persist);
        assertTrue("directory persistence must precede final transaction metadata", setTransaction > persist);
    }

    @Test
    public void retryRemovesAnyPriorProfileStagingBeforeIssuingNewToken() throws IOException {
        String source = source("NavigatorPatchStore.java");
        String claim = between(source,
                "static synchronized void claim(Context context, Profile profile, String kind,",
                "static synchronized void claimRecovery(");

        int capture = claim.indexOf("File previousTransaction = localOperation(context, profile)");
        int clear = claim.indexOf("clearTransactionMetadata(context, profile)");
        int delete = claim.indexOf("deleteTreeQuietly(previousTransaction)");
        int newToken = claim.indexOf("KEY_OPERATION_TOKEN");

        assertTrue("retry must capture the prior profile transaction", capture >= 0);
        assertTrue("retry must clear prior metadata", clear > capture);
        assertTrue("retry must remove prior staging", delete > clear);
        assertTrue("retry must issue its new token after cleanup", newToken > delete);
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
