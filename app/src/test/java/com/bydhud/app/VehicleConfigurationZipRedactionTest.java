package com.bydhud.app;

import org.junit.Test;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VehicleConfigurationZipRedactionTest {
    @Test public void largeTextConfigurationIsCompleteAndRedactsStructuredSecrets() throws Exception {
        File source = File.createTempFile("bydhud-large-config-", ".conf");
        try {
            try (Writer output = new OutputStreamWriter(
                    new java.io.FileOutputStream(source), StandardCharsets.UTF_8)) {
                for (int i = 0; i < 60_000; i++) {
                    output.write("navigation.rule=retained-configuration\n");
                }
                output.write("service.password=property-private\n");
                output.write("json = {\n  \"apiKey\": \"json-private\"\n}\n");
                output.write("<password>xml-private</password>\n");
                output.write("<entry key=\"accessToken\" value=\"xml-attribute-private\"/>\n");
                output.write("password = {\n  \"nestedToken\": \"multiline-private\"\n}\n");
                output.write("header = Bearer header-private-token\n");
                output.write("endpoint=192.168.10.15\n");
                output.write("-----BEGIN PRIVATE KEY-----\npem-private\n"
                        + "-----END PRIVATE KEY-----\n");
                output.write("final-marker=complete\n");
            }
            assertTrue("fixture must exceed the former 2 MiB limit",
                    source.length() > 2L * 1024L * 1024L);

            VehicleConfigurationFiles.Entry entry = new VehicleConfigurationFiles.Entry(
                    "/etc/navi/large.conf", "firmware/etc/navi/large.conf", source.length(),
                    0L, 0L, 0L, "regular", "config");
            try (VehicleConfigurationZip.Collector collector =
                    new VehicleConfigurationZip.Collector(null)) {
                assertTrue(VehicleConfigurationZip.redactFirmwareText(source, entry, collector));
            }

            String result = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            assertEquals(60_000, occurrences(result, "navigation.rule=retained-configuration\n"));
            assertTrue(result.contains("final-marker=complete\n"));
            assertFalse(result.contains("property-private"));
            assertFalse(result.contains("json-private"));
            assertFalse(result.contains("xml-private"));
            assertFalse(result.contains("xml-attribute-private"));
            assertFalse(result.contains("multiline-private"));
            assertFalse(result.contains("header-private-token"));
            assertFalse(result.contains("192.168.10.15"));
            assertFalse(result.contains("pem-private"));
            assertTrue(result.contains("service.password=[REDACTED]"));
            assertTrue(result.contains("password = [REDACTED]"));
            assertTrue(result.contains("header = Bearer [REDACTED]"));
            assertTrue(result.contains("[REDACTED PEM]"));
        } finally {
            if (source.exists()) assertTrue(source.delete());
        }
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int next = 0;
        while ((next = text.indexOf(value, next)) >= 0) {
            count++;
            next += value.length();
        }
        return count;
    }
}
