package com.bydhud.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fixed export recipes, not a general vehicle command or FID dispatcher. */
final class VehicleConfigurationReadback {
    static final long COMMAND_TIMEOUT_MS = 5_000L;
    static final long GETTER_TIMEOUT_MS = 1_500L;
    static final long OEM_TIMEOUT_MS = 15_000L;
    static final long SESSION_TIMEOUT_MS = 60_000L;
    static final String RECORD_PREFIX = "BYDHUD_READBACK ";
    static final String SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    static final String INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";

    //Evidence/provenance: analysis/reports/2026-09-02-vehicle-configuration-readback-proposal.md.
    //Reference mappings remain interpretations, never target capability or rendering claims.
    static final List<Read> READS = Collections.unmodifiableList(Arrays.asList(
            setting("hud.variant", 0x38B00015, "SET_HUD_CONFIG"),
            setting("hud.master", 0x38B0001C, "SET_HUD_SWITCH_STATUS_FEEDBACK"),
            setting("hud.mode", 0x38B00038, "SET_HUD_MODE_CHOICE"),
            setting("hud.theme", 0x38B0000D, "SET_HUD_MODE_FEEDBACK"),
            setting("hud.navigationFusion", 0x38B00034, "SET_NAVIGATION_FUSION_SWITCH"),
            setting("hud.dynamicNavigation", 0x38B00028, "SET_DYNAMIC_NAVI_FUNCTION_STATUS_FEEDBACK"),
            instrument("hud.navigationMap", 0x38B0002E, "INSTRUMENT_HUD_NAVIGATION_MAP_STATUS"),
            instrument("hud.navigationMapCapability", 0x38B00030, "INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG"),
            setting("hud.drivingFusion", 0x38B0001E, "SET_SAFE_DRIVING_ASSIST_STATUS_FEEDBACK"),
            setting("hud.safeDrivingFusion", 0x38B00032, "SET_SAFETY_DRIVING_AID_FUSION_SWITCH"),
            setting("hud.brightness", 0x38B00010, "SET_BRIGHTNESS_GEAR_FEEDBACK"),
            setting("hud.height", 0x38B00008, "SET_HEIGHT_GEAR_FEEDBACK"),
            new Read("hud.angle", SETTING, 0x38B00018, "SET_ANGEL_GERA_FEEDBACK", Double.TYPE, ""),
            instrument("hud.arImageColor", 0x34C00032, ""),
            instrument("hud.arImageType", 0x34C00026, ""),
            instrument("instrument.navigationStatus", 0x43E0003A, "INSTRUMENT_SEND_NAVI_STATUS_SET"),
            instrument("instrument.stockNavigationType", 0x40C03032, "INSTRUMENT_NAVI_TYPE"),
            instrument("instrument.menuType", 0x28C02016, "INSTRUMENT_MENU_TYPE"),
            instrument("instrument.menuVersion", 0x28C02013, "INSTRUMENT_MENU_VERSION"),
            instrument("instrument.menuConfiguration", 0x28C02019, "INSTRUMENT_MENU_DISPLAY_STATUS"),
            instrument("instrument.themeContent", 0x28C0201E, "INSTRUMENT_THEME_CONTENT"),
            instrument("instrument.themeConfiguration", 0x28C02021, "INSTRUMENT_THEME_STATUS"),
            instrument("instrument.themeVersion", 0x28C0201B, "INSTRUMENT_THEME_VERSION"),
            instrument("instrument.leftPanel", 0x40C0B028, "INSTRUMENT_LEFT_SIDE_COVER_PANEL"),
            instrument("instrument.rightPanel", 0x40C0B02A, "INSTRUMENT_RIGHT_SIDE_COVER_PANEL"),
            instrument("instrument.dayNight", 0x40C0B02C, "INSTRUMENT_PANEL_THEME_DEPTH"),
            new Read("instrument.speedUnit", INSTRUMENT, 0x14400010, "INSTRUMENT_DD_SPEED_UNIT",
                    Integer.TYPE, "getSpeedUnit"),
            instrument("instrument.mileageUnit03", 0x4A50303C, "INSTRUMENT_DD_MILEAGE_UNIT_03")
    ));

    private VehicleConfigurationReadback() { }

    static final class Read {
        final String parameter;
        final String deviceClass;
        final int id;
        final String constant;
        final Class<?> type;
        final String namedGetter;

        Read(String parameter, String deviceClass, int id, String constant, Class<?> type, String namedGetter) {
            this.parameter = parameter;
            this.deviceClass = deviceClass;
            this.id = id;
            this.constant = constant;
            this.type = type;
            this.namedGetter = namedGetter;
        }

        String api() {
            return deviceClass + (namedGetter.isEmpty() ? ".get(int[],Class)" : "." + namedGetter + "()");
        }

        String catalogClass() {
            return "android.hardware.bydauto.BYDAutoFeatureIds$"
                    + (SETTING.equals(deviceClass) ? "Setting" : "Instrument");
        }
    }

    private static Read setting(String name, int id, String constant) {
        return new Read(name, SETTING, id, constant, Integer.TYPE, "");
    }

    private static Read instrument(String name, int id, String constant) {
        return new Read(name, INSTRUMENT, id, constant, Integer.TYPE, "");
    }

    static String interpretation(Read read, Number raw) {
        if (raw == null || raw.doubleValue() != raw.intValue()) return "";
        int value = raw.intValue();
        switch (read.parameter) {
            case "hud.variant": return value == 1 ? "reference: W-HUD" : value == 2 ? "reference: AR-HUD" : "";
            case "hud.mode": return value == 1 ? "reference: standard" : value == 2 ? "reference: simple"
                    : value == 3 ? "reference: off-road" : "";
            case "hud.theme": return value == 1 ? "reference: classic" : value == 2 ? "reference: snow" : "";
            case "hud.height": return value >= 1 && value <= 21 ? "reference semantic height: " + (value - 11) : "";
            case "hud.master":
            case "hud.dynamicNavigation":
            case "hud.drivingFusion": return value == 1 ? "reference: on" : value == 2 ? "reference: off" : "";
            case "hud.navigationFusion":
            case "hud.navigationMap":
            case "hud.safeDrivingFusion": return value == 2 ? "reference: on" : value == 1 ? "reference: off" : "";
            default: return "";
        }
    }

    static boolean isSentinel(Number value) {
        double raw = value.doubleValue();
        return raw == -2_147_482_648d || raw == -999_999_999d;
    }

    static String launchCommand(String apkPath) {
        if (apkPath == null || apkPath.contains("..")
                || !apkPath.matches("/data/app/[A-Za-z0-9_./+=:~-]{1,500}/base\\.apk")) {
            throw new SecurityException("Unsupported installed APK path for fixed readback");
        }
        //Foreground timeout owns only this child. No nohup, files, persistent service or runtime-helper PID.
        return "if [ -x /system/bin/timeout ]; then /system/bin/timeout -s KILL 15"
                + " /system/bin/app_process -Djava.class.path=/system/framework/services.jar:"
                + "/system/framework/dilink-services.jar:" + apkPath
                + " -Djava.library.path=/system/lib64:/product/lib64:" + apkPath + "!/lib/arm64-v8a"
                + " /system/bin --nice-name=bydhud-config-readback"
                + " com.bydhud.app.VehicleConfigurationReadbackEntryPoint"
                + "; else echo readback_timeout_facility_unavailable; (exit 127); fi";
    }

    static boolean isAllowedCommand(String command) {
        if (command == null || command.contains("..")) return false;
        switch (command) {
            case "dumpsys display":
            case "dumpsys activity activities":
            case "dumpsys accessibility":
            case "dumpsys window windows":
            case "dumpsys cpuinfo":
            case "dumpsys audio":
            case "dumpsys thermalservice":
            case "dumpsys meminfo com.bydhud.app":
            case "dumpsys package com.bydhud.app":
            case "cat /proc/loadavg":
            case "settings get secure enabled_notification_listeners":
            case "settings get secure enabled_accessibility_services":
            case "settings get secure accessibility_enabled":
            case "settings get global instrument_navigation_display_config":
            case "appops get com.bydhud.app":
            case "getprop ro.build.system.fission_single_os":
            case "getprop sys.byd.countrycode":
            case "getprop persist.sys.cust_variant.cust":
            case "getprop persist.sys.locale":
            case "getprop ro.product.locale":
            case "getprop persist.sys.timezone":
            case "pm path com.example.amapservice":
            case "pm path com.byd.amapservice":
            case "pm path com.byd.containerservice":
            case "pm path com.byd.someipsystemservice":
            case "pm path com.byd.clusterdebug":
            case "pm path com.android.launcher3":
            case "stat -c %s /system/framework/services.jar":
            case "sha256sum /system/framework/services.jar":
            case "stat -c %s /system/framework/dilink-services.jar":
            case "sha256sum /system/framework/dilink-services.jar":
                return true;
            default: return false;
        }
    }
}
