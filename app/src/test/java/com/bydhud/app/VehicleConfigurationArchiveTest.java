package com.bydhud.app;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exercises the shared archive collector and published ZIP, without Android or ADB. */
public final class VehicleConfigurationArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyKnownTypedVersionFieldsBypassNetworkMasking() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        String version = "5.20.0.1";
        collector.addJson("app/packages.json", "package-manager", new JSONObject()
                .put("com.waze", new JSONObject().put("versionName", version)
                        .put("sourceDir", "/data/app/" + version + "/base.apk"))
                .put("com.google.android.apps.maps", new JSONObject().put("versionName", version + ":52001"))
                .put("com.bydhud.app", new JSONObject().put("versionName", "aa:bb:cc:dd:ee:ff"))
                .put("com.waze/versionName", version)
                .put("arbitrary.vendor", new JSONObject().put("versionName", version)));
        collector.addJson("app/device.json", "android", new JSONObject()
                .put("release", version).put("display", version));
        collector.addJson("adb/properties.json", "adb:getprop", new JSONObject()
                .put("ro.build.version.release", version).put("vendor.versionName", version));
        collector.addJson("app/runtime.json", "application", new JSONObject()
                .put("versionName", version).put("nested", new JSONObject().put("release", version)));
        collector.addText("adb/config/vendor/etc/hud.json", "adb", "{\"versionName\":\""
                + version + "\",\"enabled\":false,\"raw\":0,\"unknown\":null}", "");

        try (ZipFile zip = archive(collector)) {
            JSONObject packages = json(zip, "app/packages.json");
            assertEquals(version, packages.getJSONObject("com.waze").getString("versionName"));
            assertEquals(version, json(zip, "app/device.json").getString("release"));
            assertEquals(version, json(zip, "adb/properties.json").getString("ro.build.version.release"));
            String alias = json(zip, "app/runtime.json").getString("versionName");
            assertTrue(alias, alias.matches("<IP_[0-9]+>"));
            assertEquals(alias, packages.getJSONObject("arbitrary.vendor").getString("versionName"));
            assertEquals(alias, packages.getString("com.waze/versionName"));
            assertEquals(alias + ":52001", packages.getJSONObject("com.google.android.apps.maps")
                    .getString("versionName"));
            assertEquals("<MAC_1>", packages.getJSONObject("com.bydhud.app").getString("versionName"));
            assertEquals("/data/app/" + alias + "/base.apk",
                    packages.getJSONObject("com.waze").getString("sourceDir"));
            assertEquals(alias, json(zip, "app/device.json").getString("display"));
            assertEquals(alias, json(zip, "adb/properties.json").getString("vendor.versionName"));
            JSONObject remote = json(zip, "adb/config/vendor/etc/hud.json");
            assertEquals(alias, remote.getString("versionName"));
            assertFalse(remote.getBoolean("enabled"));
            assertEquals(0, remote.getInt("raw"));
            assertTrue(remote.isNull("unknown"));
        }
    }

    @Test public void oneMaskerCoversKeysValuesTextPathsErrorsAndManifest() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        String address = "192.168.8.10";
        collector.addJson("app/diagnostics.json", "from " + address, new JSONObject()
                .put(address, new JSONObject().put("peer", address + ":52001")
                        .put("password", "private-fixture-password"))
                .put("credential", new JSONObject().put("content", "private-fixture-credential"))
                .put("samples", new JSONArray().put(address + "/24").put(7).put(JSONObject.NULL)));
        collector.addText("adb/" + address + ".txt", "from " + address,
                "eth0 " + address + "/24 aa:bb:cc:dd:ee:ff\nAuthorization: Bearer fixture-secret",
                "peer=" + address);
        collector.unavailable.put(new JSONObject().put("path", address)
                .put("reason", "connect " + address + ":52001"));
        collector.excluded.put(new JSONObject().put("path", "/vendor/" + address)
                .put("error", "Bearer fixture-second-secret"));

        try (ZipFile zip = archive(collector)) {
            JSONObject diagnostic = json(zip, "app/diagnostics.json");
            String alias = diagnostic.getJSONArray("samples").getString(0).replace("/24", "");
            assertEquals(alias + ":52001", diagnostic.getJSONObject(alias).getString("peer"));
            assertEquals("[REDACTED]", diagnostic.getJSONObject(alias).getString("password"));
            assertEquals("[REDACTED]", diagnostic.getString("credential"));
            String entryPath = "adb/" + alias.replace('<', '[').replace('>', ']') + ".txt";
            assertTrue(text(zip, entryPath).contains("eth0 " + alias + "/24 <MAC_1>"));
            JSONObject manifest = json(zip, "manifest.json");
            assertEquals(alias, manifest.getJSONArray("unavailable").getJSONObject(0).getString("path"));
            assertEquals("connect " + alias + ":52001",
                    manifest.getJSONArray("unavailable").getJSONObject(0).getString("reason"));
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                String body = text(zip, entry.getName());
                assertFalse(body, body.contains(address));
                assertFalse(body, body.contains("aa:bb:cc:dd:ee:ff"));
                assertFalse(body, body.contains("private-fixture"));
                assertFalse(body, body.contains("fixture-secret"));
                assertFalse(body, body.contains("fixture-second-secret"));
            }
            assertTrue(manifest.toString().contains("from " + alias));
            assertTrue(manifest.toString().contains("peer=" + alias));
        }
    }

    @Test public void oversizedOrMalformedJsonIsOmittedAtomicallyWhileUsefulEntriesRemain() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        collector.addJson("app/basic.json", "fixture", new JSONObject().put("available", true));
        collector.addJson("app/large.json", "fixture", new JSONObject()
                .put("data", repeat('x', VehicleConfigurationZip.MAX_ENTRY_BYTES)));
        collector.addJson("app/null.json", "fixture", null);
        collector.addText("adb/config/vendor/etc/hud.json", "fixture", "{\"unfinished\":", "");
        collector.addText("adb/config/vendor/etc/trailing.json", "fixture", "{} trailing", "");
        collector.addText("app/array.JSON", "fixture", "[null,false,0,{\"text\":\"ok\"}]", "");

        try (ZipFile zip = archive(collector)) {
            assertEquals(3, zip.size());
            assertNotNull(zip.getEntry("app/basic.json"));
            assertNull(zip.getEntry("app/large.json"));
            assertNull(zip.getEntry("adb/config/vendor/etc/hud.json"));
            JSONArray array = new JSONArray(text(zip, "app/array.JSON"));
            assertTrue(array.isNull(0));
            assertFalse(array.getBoolean(1));
            assertEquals(0, array.getInt(2));
            JSONArray unavailable = json(zip, "manifest.json").getJSONArray("unavailable");
            assertEquals(4, unavailable.length());
            assertTrue(unavailable.toString().contains("JSON omitted: entry size limit"));
            assertTrue(unavailable.toString().contains("malformed JSON"));
        }
    }

    @Test public void jsonKeyCollisionFailsClosedWithoutDroppingOtherFields() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        collector.addJson("app/collision.json", "fixture", new JSONObject()
                .put("<IP_1>", "literal").put("10.0.0.1", "address"));
        collector.addJson("app/basic.json", "fixture", new JSONObject().put("ok", true));
        try (ZipFile zip = archive(collector)) {
            assertNull(zip.getEntry("app/collision.json"));
            assertNotNull(zip.getEntry("app/basic.json"));
            assertTrue(json(zip, "manifest.json").getJSONArray("unavailable")
                    .toString().contains("masked JSON key collision"));
        }
    }

    @Test public void structuredJsonExactByteBoundaryIsKeptAndOneMoreByteIsOmitted() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        int framingBytes = (new JSONObject().put("data", "").toString() + "\n")
                .getBytes(StandardCharsets.UTF_8).length;
        int payload = VehicleConfigurationZip.MAX_ENTRY_BYTES - framingBytes;
        collector.addJson("app/exact.json", "fixture", new JSONObject().put("data", repeat('x', payload)));
        collector.addJson("app/over.json", "fixture", new JSONObject().put("data", repeat('x', payload + 1)));
        try (ZipFile zip = archive(collector)) {
            assertEquals(VehicleConfigurationZip.MAX_ENTRY_BYTES, zip.getEntry("app/exact.json").getSize());
            assertEquals(payload, json(zip, "app/exact.json").getString("data").length());
            assertNull(zip.getEntry("app/over.json"));
            assertEquals(1, json(zip, "manifest.json").getJSONArray("unavailable").length());
        }
    }

    @Test public void fidCatalogRetainsSchemaAndDeterministicCompletePrefixWithinEntryLimit() throws Exception {
        JSONObject original = catalog(1500, repeat('f', 2000));
        VehicleConfigurationZip.Collector collector = collector();
        collector.addFidCatalog(original);
        try (ZipFile zip = archive(collector)) {
            JSONObject bounded = json(zip, "adb/fid-catalog.json");
            assertEquals(1, bounded.getInt("schemaVersion"));
            assertEquals("available", bounded.getString("status"));
            assertEquals(2, bounded.getInt("loadedRoots"));
            assertEquals(1500, bounded.getInt("entryCount"));
            assertEquals(1500, bounded.getInt("originalEntryCount"));
            int included = bounded.getInt("includedEntryCount");
            assertTrue(included > 0 && included < 1500);
            assertEquals(1500 - included, bounded.getInt("omittedEntryCount"));
            assertEquals(included, bounded.getJSONArray("entries").length());
            assertEquals(1, bounded.getInt("originalErrorCount"));
            assertEquals(0, bounded.getInt("includedErrorCount"));
            assertEquals(1, bounded.getInt("omittedErrorCount"));
            assertTrue(bounded.getBoolean("truncated"));
            for (int index = 0; index < included; index++) {
                assertEquals(index, bounded.getJSONArray("entries").getJSONObject(index).getInt("value"));
            }
            assertTrue(zip.getEntry("adb/fid-catalog.json").getSize() <= VehicleConfigurationZip.MAX_ENTRY_BYTES);
            JSONObject repeated = VehicleConfigurationZip.boundedCatalog(original, VehicleConfigurationZip.MAX_ENTRY_BYTES);
            assertEquals(repeated.getInt("includedEntryCount"), included);
            assertEquals(repeated.getInt("includedErrorCount"), bounded.getInt("includedErrorCount"));
            assertEquals(original.getJSONArray("entries").getJSONObject(included - 1).getString("field"),
                    bounded.getJSONArray("entries").getJSONObject(included - 1).getString("field"));
            assertEquals(repeated.toString(), VehicleConfigurationZip.boundedCatalog(original,
                    VehicleConfigurationZip.MAX_ENTRY_BYTES).toString());
        }
    }

    @Test public void fidCatalogExactBoundaryAndMetadataOnlyKeepAccurateCounts() throws Exception {
        JSONObject source = catalog(3, "field");
        JSONObject full = VehicleConfigurationZip.boundedCatalog(source, 10000);
        int bytes = (full.toString() + "\n").getBytes(StandardCharsets.UTF_8).length;
        assertFalse(VehicleConfigurationZip.boundedCatalog(source, bytes).getBoolean("truncated"));
        assertTrue(VehicleConfigurationZip.boundedCatalog(source, bytes - 1).getBoolean("truncated"));
        assertEquals(3, full.getInt("includedEntryCount"));
        assertEquals(1, full.getInt("includedErrorCount"));
        assertNull(VehicleConfigurationZip.boundedCatalog(source, 0));
        JSONObject empty = VehicleConfigurationZip.boundedCatalog(catalog(0, ""), 10000);
        assertEquals(0, empty.getInt("originalEntryCount"));
        assertEquals(1, empty.getInt("includedErrorCount"));
        assertFalse(empty.getBoolean("truncated"));
    }

    @Test public void textTruncationUsesCompleteUtf8CodePointsAndExactByteLimit() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        String exact = repeat('a', VehicleConfigurationZip.MAX_ENTRY_BYTES);
        String prefix = repeat('b', VehicleConfigurationZip.MAX_ENTRY_BYTES - 1);
        collector.addText("app/exact.txt", "fixture", exact, "");
        collector.addText("app/clipped.txt", "fixture", prefix + "\uD83D\uDE97tail", "");
        try (ZipFile zip = archive(collector)) {
            assertEquals(exact, text(zip, "app/exact.txt"));
            String clipped = text(zip, "app/clipped.txt");
            assertEquals(prefix, clipped);
            assertFalse(clipped.contains("\uFFFD"));
            assertEquals(VehicleConfigurationZip.MAX_ENTRY_BYTES - 1, zip.getEntry("app/clipped.txt").getSize());
            assertTrue(json(zip, "manifest.json").toString().contains("truncated=true"));
        }
        byte[] scalar = "a\uD83D\uDE97z".getBytes(StandardCharsets.UTF_8);
        for (int limit = 2; limit < 5; limit++) {
            assertEquals("a", new String(VehicleConfigurationZip.utf8Prefix(scalar, limit), StandardCharsets.UTF_8));
        }
        assertEquals("a\uD83D\uDE97", new String(VehicleConfigurationZip.utf8Prefix(scalar, 5), StandardCharsets.UTF_8));
    }

    @Test public void totalBudgetReservesManifestAndAccountsForEveryPublishedEntry() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        String data = repeat('x', VehicleConfigurationZip.MAX_ENTRY_BYTES);
        for (int index = 0; index < 12; index++) collector.addText("app/" + index + ".txt", "fixture", data, "");
        assertEquals(22 * 1024 * 1024, collector.totalBytes);
        try (ZipFile zip = archive(collector)) {
            assertEquals(12, zip.size()); //Eleven payload entries plus the reserved manifest.
            assertNull(zip.getEntry("app/11.txt"));
            long total = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                assertTrue(entry.getSize() <= VehicleConfigurationZip.MAX_ENTRY_BYTES);
                total += entry.getSize();
            }
            JSONObject manifest = json(zip, "manifest.json");
            assertEquals(22 * 1024 * 1024, manifest.getInt("payloadBytes"));
            assertEquals(2 * 1024 * 1024, manifest.getInt("manifestReservedBytes"));
            assertEquals(manifest.getInt("payloadBytes") + zip.getEntry("manifest.json").getSize(), total);
            assertTrue(total <= VehicleConfigurationZip.MAX_TOTAL_BYTES);
            assertEquals(1, manifest.getJSONArray("unavailable").length());
        }
    }

    @Test public void exhaustedPayloadOmitsCatalogAndOversizedManifestCannotPublish() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        collector.totalBytes = 22 * 1024 * 1024;
        collector.addFidCatalog(catalog(1, "fixture"));
        assertTrue(collector.manifest().getJSONArray("unavailable").toString().contains("FID JSON omitted"));
        VehicleConfigurationZip.Collector oversized = collector();
        oversized.unavailable.put(new JSONObject().put("reason", repeat('x', VehicleConfigurationZip.MAX_ENTRY_BYTES)));
        File output = new File(temporary.getRoot(), "oversized.zip");
        VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeArchive(output, oversized);
        assertFalse(result.ok);
        assertTrue(result.detail, result.detail.contains("manifest size limit"));
        assertFalse(output.exists());
        assertFalse(new File(output.getPath() + ".part").exists());
    }

    @Test public void cancellationPropagatesAndRemovesPartAndPublishedArtifacts() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> collector.addJson("app/basic.json", "fixture", new JSONObject()));
            File output = new File(temporary.getRoot(), "cancelled-before.zip");
            assertFalse(VehicleConfigurationZip.writeArchive(output, collector).ok);
            assertFalse(output.exists());
            assertFalse(new File(output.getPath() + ".part").exists());
        } finally { Thread.interrupted(); }

        collector.addText("app/basic.txt", "fixture", "complete", "");
        File published = new File(temporary.getRoot(), "cancelled-after.zip");
        File output = new File(published.getPath()) {
            //Probe a plain File: Windows File.isFile() itself dispatches through getPath().
            @Override public String getPath() {
                if (published.isFile()) Thread.currentThread().interrupt();
                return super.getPath();
            }
        };
        try {
            VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeArchive(output, collector);
            assertFalse(result.ok);
            assertTrue(result.detail, result.detail.contains("cancelled"));
            assertFalse(output.exists());
            assertFalse(new File(temporary.getRoot(), "cancelled-after.zip.part").exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void writeFailureCleansOwnedArtifactsAndExistingArtifactsAreNeverReplaced() throws Exception {
        VehicleConfigurationZip.Collector collector = collector();
        collector.addText("app/basic.txt", "fixture", "fixture", "");
        File regular = temporary.newFile("not-a-directory");
        File invalidOutput = new File(regular, "output.zip");
        assertFalse(VehicleConfigurationZip.writeArchive(invalidOutput, collector).ok);
        assertTrue(regular.isFile());
        assertFalse(invalidOutput.exists());
        for (String suffix : new String[]{"", ".part"}) {
            File output = new File(temporary.getRoot(), "existing" + suffix.length() + ".zip");
            File existing = new File(output.getPath() + suffix);
            byte[] original = "belongs to an earlier export".getBytes(StandardCharsets.UTF_8);
            Files.write(existing.toPath(), original);
            assertFalse(VehicleConfigurationZip.writeArchive(output, collector).ok);
            assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
        }
    }

    @Test public void oemFramingPreservesLastSuccessfulLineAndCompletedTimeoutRecords() throws Exception {
        String prefix = VehicleConfigurationReadback.RECORD_PREFIX;
        String record = "{\"parameter\":\"hud.height\",\"status\":\"success\",\"rawValue\":0}";
        LocalAdbBridge.ShellResult successful = LocalAdbBridge.ShellResult.parse(
                "OEM diagnostic noise\n" + prefix + record + "\n__BYDHUD_EXIT__:0\n");
        JSONObject complete = VehicleConfigurationZip.oemReadbackJson(successful);
        assertEquals("success", complete.getString("status"));
        assertEquals("success", complete.getString("recordParsingStatus"));
        assertEquals(1, complete.getInt("recordCount"));
        assertEquals(0, complete.getInt("incompleteRecordCount"));
        LocalAdbBridge.ShellResult timedOut = LocalAdbBridge.ShellResult.parse(
                prefix + record + "\n" + prefix + "{broken\n" + prefix + "{\"unfinished\":\n__BYDHUD_EXIT__:124\n", true, 25);
        JSONObject partial = VehicleConfigurationZip.oemReadbackJson(timedOut);
        assertEquals("timeout", partial.getString("status"));
        assertTrue(partial.getBoolean("transportTruncated"));
        assertEquals(1, partial.getInt("recordCount"));
        assertEquals(2, partial.getInt("malformedRecordCount"));
        assertEquals("partial", partial.getString("recordParsingStatus"));
        JSONObject incomplete = VehicleConfigurationZip.oemReadbackJson(LocalAdbBridge.ShellResult.parse(
                prefix + record + "\n" + prefix + "{\"cut\":"));
        assertEquals(1, incomplete.getInt("recordCount"));
        assertEquals(1, incomplete.getInt("incompleteRecordCount"));
        assertEquals("partial", incomplete.getString("recordParsingStatus"));
        JSONObject unavailable = VehicleConfigurationZip.oemReadbackJson(
                LocalAdbBridge.ShellResult.parse(prefix + "{broken\n__BYDHUD_EXIT__:0\n"));
        assertEquals("unavailable", unavailable.getString("recordParsingStatus"));
        assertEquals(0, unavailable.getInt("recordCount"));
        assertFalse(partial.toString().contains("OEM diagnostic noise"));
    }

    @Test public void exhaustedAcquisitionBudgetSkipsNativeReadsButFinalizesUsefulData() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            collector.addJson("app/basic.json", "fixture", new JSONObject().put("available", true));
            collector.deadlineNanos = System.nanoTime() - 1;
            java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
            collector.collectBaseline("app/skipped.json", "fixture", () -> {
                called.set(true);
                return new JSONObject();
            });
            assertFalse(called.get());
            assertEquals(0, collector.remainingBudgetMs());
            try (ZipFile zip = archive(collector)) {
                assertNotNull(zip.getEntry("app/basic.json"));
                assertNull(zip.getEntry("app/skipped.json"));
                assertTrue(json(zip, "manifest.json").getJSONArray("unavailable").toString().contains("budget_exhausted"));
            }
        }
    }

    @Test public void blockedNativeWorkerIsNotReplacedAndLateResultCannotMutateArchive() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
        try (VehicleConfigurationZip.Collector collector = collector()) {
            collector.deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300);
            collector.collectBaseline("app/blocked.json", "fixture", () -> {
                try {
                    boolean released = false;
                    while (!released) {
                        try { release.await(); released = true; }
                        catch (InterruptedException ignored) { /* Simulate an uninterruptible platform read. */ }
                    }
                    return new JSONObject().put("late", true);
                } finally { exited.countDown(); }
            });
            collector.deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
            collector.collectBaseline("app/later.json", "fixture", () -> {
                called.set(true);
                return new JSONObject();
            });
            assertFalse(called.get());
            release.countDown();
            assertTrue(exited.await(1, java.util.concurrent.TimeUnit.SECONDS));
            try (ZipFile zip = archive(collector)) {
                assertNull(zip.getEntry("app/blocked.json"));
                assertNull(zip.getEntry("app/later.json"));
                String failures = json(zip, "manifest.json").getJSONArray("unavailable").toString();
                assertTrue(failures, failures.contains("timeout: native_read"));
                assertTrue(failures, failures.contains("native_worker_unavailable_after_timeout"));
            }
        } finally { release.countDown(); }
    }

    @Test public void staticCatalogCancellationIsCooperativeAndMissingClassDoesNotHideLaterClasses() {
        String[] classes = {"fixture.DoesNotExist", FidFixture.class.getName()};
        FidCatalog.Result collected = FidCatalog.collect(classes, () -> false);
        assertEquals(1, collected.entries.size());
        assertEquals(42, collected.entries.get(0).value);
        assertEquals(1, collected.errors.size());
        FidCatalog.Result cancelled = FidCatalog.collect(classes, () -> true);
        assertEquals(0, cancelled.entries.size());
        assertEquals(0, cancelled.loadedRoots);
        assertTrue(cancelled.errors.get(0).contains("collection_cancelled"));
    }

    @Test public void fixedFrameworkMetadataHonorsExhaustedBudgetWithoutBinaryReads() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            collector.deadlineNanos = System.nanoTime() - 1;
            collector.collectFrameworkMetadata();
            JSONObject manifest = collector.manifest();
            String unavailable = manifest.getJSONArray("unavailable").toString();
            assertTrue(unavailable, unavailable.contains("/system/framework/services.jar"));
            assertTrue(unavailable, unavailable.contains("/system/framework/dilink-services.jar"));
            assertTrue(unavailable, unavailable.contains("budget_exhausted"));
            assertEquals(0, manifest.getJSONArray("files").length());
            assertEquals(0, manifest.getJSONArray("excluded").length());
            assertFalse(manifest.getJSONObject("policy").getBoolean("frameworkJarsIncluded"));
        }
    }

    @Test public void randomizedApkMetadataPathsAreRetainedAndRejectedPathsAreExplained() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            String installed = "/data/app/~~fixture==/com.bydhud.app-package==/base.apk";
            String rejected = "/data/app/10.0.0.7/base.apk;token=private-path-token";
            java.util.List<String> paths = collector.apkMetadataPaths("com.bydhud.app",
                    LocalAdbBridge.ShellResult.parse("package:" + installed + "\npackage:" + rejected
                            + "\n__BYDHUD_EXIT__:0\n"));
            assertEquals(Arrays.asList(installed), paths);
            String unavailable = collector.manifest().getJSONArray("unavailable").toString();
            assertTrue(unavailable, unavailable.contains("rejected APK metadata path"));
            assertFalse(unavailable, unavailable.contains("10.0.0.7"));
            assertFalse(unavailable, unavailable.contains("private-path-token"));
        }
    }

    @Test public void knownAbsentPackageIsNotConfusedWithUnknownOrDeniedQueries() throws Exception {
        String stockMaps = "com.google.android.apps.maps";
        try (VehicleConfigurationZip.Collector collector = collector()) {
            collector.addJson("app/packages.json", "package-manager", new JSONObject()
                    .put(stockMaps, new JSONObject().put("installed", false))
                    .put("com.waze", new JSONObject().put("installed", true)));
            LocalAdbBridge.ShellResult emptyFailure = LocalAdbBridge.ShellResult.parse("__BYDHUD_EXIT__:1\n");
            assertTrue(collector.apkMetadataPaths(stockMaps, emptyFailure).isEmpty());
            assertTrue(collector.apkMetadataPaths("com.waze", emptyFailure).isEmpty());
            assertTrue(collector.apkMetadataPaths("unknown.fixture", emptyFailure).isEmpty());
            JSONArray unavailable = collector.manifest().getJSONArray("unavailable");
            String absent = unavailable.getJSONObject(0).getString("reason");
            assertTrue(absent, absent.contains("not_installed (package-manager snapshot)"));
            assertTrue(absent, absent.contains("exit=1"));
            assertFalse(unavailable.getJSONObject(1).getString("reason").contains("not_installed"));
            assertFalse(unavailable.getJSONObject(2).getString("reason").contains("not_installed"));
            assertEquals("", emptyFailure.error); //The runtime parser's existing semantics stay unchanged.
        }
        for (LocalAdbBridge.ShellResult failed : Arrays.asList(
                LocalAdbBridge.ShellResult.parse("Error: permission denied\n__BYDHUD_EXIT__:1\n"),
                LocalAdbBridge.ShellResult.parse("Error: package service unavailable\n__BYDHUD_EXIT__:1\n"),
                LocalAdbBridge.ShellResult.parse("__BYDHUD_EXIT__:126\n"),
                LocalAdbBridge.ShellResult.parse("__BYDHUD_EXIT__:124\n"),
                LocalAdbBridge.ShellResult.parse("__BYDHUD_EXIT__:1\n", true, 20L))) {
            try (VehicleConfigurationZip.Collector collector = collector()) {
                collector.addJson("app/packages.json", "package-manager", new JSONObject()
                        .put(stockMaps, new JSONObject().put("installed", false)));
                collector.apkMetadataPaths(stockMaps, failed);
                String reason = collector.manifest().getJSONArray("unavailable").getJSONObject(0).getString("reason");
                assertFalse(reason, reason.contains("not_installed"));
                assertTrue(reason, reason.contains("exit=" + failed.exitCode));
            }
        }
    }

    @Test public void nonzeroFindRetainsUsefulConfigAndLibraryPathsAsPartialInventory() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            LocalAdbBridge.ShellResult configs = LocalAdbBridge.ShellResult.parse(
                    "/vendor/etc/hud/display.json\n/system/etc/someip/stack.conf\n"
                            + "find: /odm/etc: Permission denied\n__BYDHUD_EXIT__:1\n");
            assertEquals(2, collector.inventoryPaths("adb/config", configs, true, 256).size());
            LocalAdbBridge.ShellResult libraries = LocalAdbBridge.ShellResult.parse(
                    "/system/lib64/libsomeip.so\n/vendor/lib/libhud.so\n"
                            + "find: /odm/lib64: No such file or directory\n__BYDHUD_EXIT__:1\n");
            assertEquals(2, collector.inventoryPaths("adb/native-libraries", libraries, false, 64).size());
            JSONArray unavailable = collector.manifest().getJSONArray("unavailable");
            for (int index = 0; index < 2; index++) {
                String reason = unavailable.getJSONObject(index).getString("reason");
                assertTrue(reason, reason.contains("partial inventory; retainedPaths=2"));
                assertTrue(reason, reason.contains("exit=1"));
            }
            assertTrue(unavailable.getJSONObject(0).getString("reason").contains("Permission denied"));
            assertTrue(unavailable.getJSONObject(1).getString("reason").contains("No such file or directory"));
            assertTrue(collector.inventoryPaths("adb/empty", LocalAdbBridge.ShellResult.parse(
                    "find: /odm/etc: Permission denied\n__BYDHUD_EXIT__:1\n"), true, 256).isEmpty());
            assertTrue(collector.manifest().getJSONArray("unavailable").getJSONObject(2).getString("reason")
                    .contains("unavailable inventory; retainedPaths=0"));
        }
    }

    @Test public void shellErrorContextIsBoundedMaskedAndDoesNotExportArbitraryPayloads() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            LocalAdbBridge.ShellResult failed = LocalAdbBridge.ShellResult.parse(
                    "/vendor/etc/hud/valid.json\nstat: 10.0.0.7: Permission denied; password='private-error-password'; detail="
                            + repeat('\u6E2C', 2000) + "\n__BYDHUD_EXIT__:1\n");
            String detail = collector.shellDetail(failed);
            assertTrue(detail, detail.contains("exit=1"));
            assertTrue(detail, detail.contains("Permission denied"));
            assertTrue(detail, detail.contains("<IP_1>"));
            assertTrue(detail, detail.contains("[truncated]"));
            assertTrue(detail.getBytes(StandardCharsets.UTF_8).length <= 512);
            assertFalse(detail.contains("\uFFFD"));
            assertFalse(detail.contains("10.0.0.7"));
            assertFalse(detail.contains("private-error-password"));
            assertFalse(detail.contains("valid.json"));
            String arbitrary = collector.shellDetail(LocalAdbBridge.ShellResult.parse(
                    "private-navigation-content\n{\"token\":\"private-json-token\"}\n__BYDHUD_EXIT__:1\n"));
            assertTrue(arbitrary, arbitrary.contains("unrecognized output omitted"));
            assertFalse(arbitrary.contains("private-navigation-content"));
            assertFalse(arbitrary.contains("private-json-token"));
            String missingReason = collector.shellDetail(LocalAdbBridge.ShellResult.parse("__BYDHUD_EXIT__:1\n"));
            assertTrue(missingReason, missingReason.contains("exit=1; reason=no diagnostic output"));
            assertFalse(missingReason.toLowerCase(java.util.Locale.ROOT).contains("not found"));
            String truncated = collector.shellDetail(LocalAdbBridge.ShellResult.parse(
                    "stat: /system/framework/dilink-services.jar: Permission denied\n__BYDHUD_EXIT__:1\n", true, 10));
            assertTrue(truncated, truncated.contains("exit=1; transportTruncated=true"));
            assertTrue(truncated, truncated.contains("Permission denied"));
        }
    }

    @Test public void exportErrorFormattingAndPartialInventoryNeverSwallowCancellation() throws Exception {
        try (VehicleConfigurationZip.Collector collector = collector()) {
            LocalAdbBridge.ShellResult failed = LocalAdbBridge.ShellResult.parse("Error: denied\n__BYDHUD_EXIT__:1\n");
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> collector.shellDetail(failed));
                assertThrows(InterruptedIOException.class, () -> collector.inventoryPaths("adb/config", failed, true, 256));
                assertThrows(InterruptedIOException.class, () -> collector.apkMetadataPaths("com.waze", failed));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    public static final class FidFixture { public static final int VALUE = 42; }

    private static VehicleConfigurationZip.Collector collector() {
        return new VehicleConfigurationZip.Collector(null);
    }

    private ZipFile archive(VehicleConfigurationZip.Collector collector) throws Exception {
        File output = new File(temporary.newFolder(), "vehicle.zip");
        VehicleConfigurationZip.Result result = VehicleConfigurationZip.writeArchive(output, collector);
        assertTrue(result.detail, result.ok);
        assertFalse(new File(output.getPath() + ".part").exists());
        return new ZipFile(output);
    }

    private static JSONObject json(ZipFile zip, String path) throws Exception {
        return new JSONObject(text(zip, path));
    }

    private static String text(ZipFile zip, String path) throws Exception {
        assertNotNull(path, zip.getEntry(path));
        try (InputStream input = zip.getInputStream(zip.getEntry(path));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject catalog(int count, String field) throws Exception {
        JSONArray entries = new JSONArray();
        for (int index = 0; index < count; index++) entries.put(new JSONObject()
                .put("class", "fixture.Constants").put("field", field).put("type", "int").put("value", index));
        return new JSONObject().put("schemaVersion", 1).put("status", "available")
                .put("loadedRoots", 2).put("entryCount", count).put("entries", entries)
                .put("errors", new JSONArray().put("class_load fixture.Optional unavailable"));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
