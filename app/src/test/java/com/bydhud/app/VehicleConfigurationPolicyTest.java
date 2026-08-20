package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class VehicleConfigurationPolicyTest {
    @Test
    public void archiveFiltersOnlyRelevantSystemFiles() {
        assertTrue(VehicleConfigurationZip.isRelevantConfigPath(
                "/vendor/etc/someip/someip_stack.json"));
        assertTrue(VehicleConfigurationZip.isRelevantLibraryPath(
                "/system/lib64/libsomeip_client.so"));
        assertFalse(VehicleConfigurationZip.isRelevantConfigPath(
                "/vendor/etc/location/gps_hud.json"));
        assertFalse(VehicleConfigurationZip.isRelevantConfigPath(
                "/data/local/tmp/someip.json"));
        assertFalse(VehicleConfigurationZip.isRelevantConfigPath(
                "/vendor/etc/someip/../accounts.json"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "{\"vin\":\"secret\"}"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "<location><latitude>1</latitude></location>"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent("VIN=secret"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent("lat=1; lon=2"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "account_id=test"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent("user=root"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "vehicle.vin=secret"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "nav.current_lat=1"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "location.start_lon=2"));
        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(
                "user.name=root"));
        assertFalse(VehicleConfigurationZip.containsSensitiveConfigContent(
                "{\"service\":\"someip\",\"port\":52001}"));
    }

    @Test
    public void adbAllowlistRejectsArbitraryAndTraversalReads() {
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("id"));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "cat /vendor/etc/someip/someip_stack.json"));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "sha256sum /data/app/app.revanced.android.apps.maps/base.apk"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "cat /data/user/0/com.waze/shared_prefs/user.xml"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "cat /vendor/etc/someip/../shadow"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "sha256sum /data/app/app.revanced.android.apps.maps/base.apk; id"));
    }

    @Test
    public void configRedactionKeepsUsefulInventoryAndMasksCredentials() {
        String source = "{\"service\":\"someip\",\"password\":\"secret\","
                + "\"token\":\"abc\",\"port\":52001}";

        String redacted = VehicleConfigurationZip.redactConfigContent(source);

        assertTrue(redacted.contains("someip"));
        assertTrue(redacted.contains("52001"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertFalse(redacted.contains("secret"));
        assertFalse(redacted.contains("abc"));
    }

    @Test
    public void configRedactionMasksXmlAndAuthorizationValues() {
        String source = "<location><latitude>50.1</latitude></location>\n"
                + "Authorization: Bearer abc.def\n"
                + "port=52001";

        String redacted = VehicleConfigurationZip.redactConfigContent(source);

        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("port=52001"));
        assertFalse(redacted.contains("50.1"));
        assertFalse(redacted.contains("abc.def"));
    }

    @Test
    public void configRedactionMasksSensitiveXmlAttributePairs() {
        String source = "<property name=\"vin\" value=\"secret-vin\"/>\n"
                + "<entry key='token' data='secret-token'/>";

        assertTrue(VehicleConfigurationZip.containsSensitiveConfigContent(source));
        String redacted = VehicleConfigurationZip.redactConfigContent(source);

        assertFalse(redacted.contains("secret-vin"));
        assertFalse(redacted.contains("secret-token"));
        assertTrue(redacted.contains("name=\"vin\" value=\"[REDACTED]\""));
        assertTrue(redacted.contains("key='token' data='[REDACTED]'"));
    }

    @Test
    public void networkMaskingKeepsTopologyButRemovesStableIdentifiers() {
        List<String> masked = VehicleConfigurationZip.maskNetworkAddressesForTest(Arrays.asList(
                "wlan0 192.168.8.10/24 via 192.168.8.1 lladdr AA:BB:CC:DD:EE:FF",
                "tcp [fe80::1234%wlan0]:443 192.168.8.10:52001",
                "udp :::5353 11-22-33-44-55-66"));

        String detail = masked.toString();
        assertTrue(detail, masked.get(0).contains("wlan0 <IP_1>/24 via <IP_2>"));
        assertTrue(detail, masked.get(0).contains("<MAC_1>"));
        assertTrue(detail, masked.get(1).contains("[<IP_3>%wlan0]:443"));
        assertTrue(detail, masked.get(1).contains("<IP_1>:52001"));
        assertTrue(detail, masked.get(2).contains("<IP_4>:5353"));
        assertTrue(detail, masked.get(2).contains("<MAC_2>"));
        for (String value : masked) {
            assertFalse(value.contains("192.168.8"));
            assertFalse(value.contains("fe80::1234"));
            assertFalse(value.contains("AA:BB:CC"));
            assertFalse(value.contains("11-22-33"));
        }
    }
}
