package com.bydhud.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.util.Arrays;

/** Read-only evidence for the existing Google registration fence; never sends/cancels a token. */
public final class GMapsIdentityDiagnostics {
    static final String BUILD = "identity-20260928-r2";
    static final String ACTION = "com.bydhud.app.gmaps.DIRECT_IDENTITY";
    static final int REQUEST = 2101;
    static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    private GMapsIdentityDiagnostics() {}

    static Intent identityIntent(Context context) {
        return new Intent(context, HudRuntimeService.class).setAction(ACTION);
    }

    static String environment(Context context) {
        StringBuilder out = new StringBuilder("build=" + BUILD + " sdk=" + Build.VERSION.SDK_INT
                + " pid=" + Process.myPid() + " processUid=" + Process.myUid()
                + " user=" + Process.myUid() / 100000);
        field(out, "contextPackage", context::getPackageName);
        field(out, "opPackage", context::getOpPackageName);
        for (String pkg : new String[]{"com.bydhud.app", GMapsDirectChannel.PACKAGE_NAME}) {
            field(out, pkg, () -> {
                PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
                ApplicationInfo app = info.applicationInfo;
                return "uid:" + app.uid + ",target:" + app.targetSdkVersion
                        + ",enabled:" + app.enabled + ",flags:" + app.flags
                        + ",version:" + info.versionName + ",code:" + info.getLongVersionCode();
            });
        }
        field(out, "receiver", () -> {
            android.content.pm.ActivityInfo receiver = context.getPackageManager().getReceiverInfo(
                    new android.content.ComponentName(GMapsDirectChannel.PACKAGE_NAME,
                            "com.google.android.libraries.geo.navcore.navinfo.NavigationInfoBroadcastReceiver"),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return "enabled:" + receiver.enabled + ",exported:" + receiver.exported
                    + ",permission:" + receiver.permission + ",uid:" + receiver.applicationInfo.uid;
        });
        field(out, "targetService", () -> {
            android.content.pm.ServiceInfo service = context.getPackageManager().getServiceInfo(
                    identityIntent(context).getComponent(), PackageManager.MATCH_DISABLED_COMPONENTS);
            return "enabled:" + service.enabled + ",exported:" + service.exported
                    + ",uid:" + service.applicationInfo.uid;
        });
        return out.toString();
    }

    static String snapshot(Context context, PendingIntent token, boolean lookup) {
        StringBuilder out = new StringBuilder("elapsedMs=" + SystemClock.elapsedRealtime()
                + " tokenPresent=" + (token != null));
        if (token != null) {
            metadata(out, "cached", token);
            Parcel parcel = Parcel.obtain();
            try {
                token.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                PendingIntent fresh = PendingIntent.CREATOR.createFromParcel(parcel);
                out.append(" parcelEqual=").append(token.equals(fresh))
                        .append(" freshObject=").append(token != fresh);
                // Android caches PendingIntentInfo per Java object. Re-parcel for a fresh system read.
                metadata(out, "fresh", fresh);
                // Binder inspection is supplementary; an OEM Parcel layout must not hide metadata.
                field(out, "binder", () -> {
                    parcel.setDataPosition(0);
                    IBinder binder = parcel.readStrongBinder();
                    return binder == null ? "null" : "alive:" + binder.isBinderAlive()
                            + ",descriptor:" + binder.getInterfaceDescriptor();
                });
            } catch (Throwable error) {
                out.append(" parcelError=").append(error(error));
            } finally {
                parcel.recycle();
            }
        }
        if (lookup) {
            try {
                PendingIntent existing = PendingIntent.getService(context, REQUEST,
                        identityIntent(context), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
                out.append(" existingPresent=").append(existing != null)
                        .append(" existingEqual=").append(token != null && token.equals(existing));
                if (existing != null) metadata(out, "existing", existing);
            } catch (Throwable error) {
                out.append(" lookupError=").append(error(error));
            }
        }
        return out.toString();
    }

    private static void metadata(StringBuilder out, String prefix, PendingIntent token) {
        if (token == null) { out.append(' ').append(prefix).append("=null"); return; }
        // Independent fields: one failed getter must not hide all the remaining evidence.
        field(out, prefix + "Package", token::getCreatorPackage);
        field(out, prefix + "Uid", token::getCreatorUid);
        if (Build.VERSION.SDK_INT >= 31) {
            field(out, prefix + "Immutable", token::isImmutable);
            field(out, prefix + "Service", token::isService);
            field(out, prefix + "Activity", token::isActivity);
            field(out, prefix + "Broadcast", token::isBroadcast);
            field(out, prefix + "ForegroundService", token::isForegroundService);
        } else {
            out.append(' ').append(prefix).append("TypeRead=unsupported_api_").append(Build.VERSION.SDK_INT);
        }
    }

    static String replySender(Context context, int uid) {
        StringBuilder out = new StringBuilder("senderUid=" + uid);
        field(out, "senderPackages", () -> Arrays.toString(context.getPackageManager().getPackagesForUid(uid)));
        field(out, "expectedSenderUid", () -> context.getPackageManager()
                .getApplicationInfo(GMapsDirectChannel.PACKAGE_NAME, 0).uid);
        return out.toString();
    }

    interface Read { Object get() throws Exception; }

    static void field(StringBuilder out, String name, Read read) {
        try { out.append(' ').append(name).append('=').append(text(String.valueOf(read.get()), 350)); }
        catch (Throwable error) { out.append(' ').append(name).append("Error=").append(error(error)); }
    }

    static String error(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; error != null && i < 3; i++, error = error.getCause()) {
            if (i != 0) out.append("<- ");
            out.append(error.getClass().getName()).append(':').append(text(error.getMessage(), 180));
            StackTraceElement[] frames = error.getStackTrace();
            if (frames.length > 0) out.append('@').append(frames[0]);
        }
        return text(out.toString(), 900);
    }

    static String text(String value, int max) {
        String safe = value == null ? "null" : value.replaceAll("[\\p{Cntrl}\\s]+", " ");
        return safe.length() <= max ? safe : safe.substring(0, max) + "[truncated]";
    }

    /** Fixed, read-only shell-UID entry: query actual Maps->HUD package visibility on Android 13. */
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || args.length != 1 || !args[0].matches("[0-9]{1,5}")) {
            System.out.println("visibilityError=invalid_shell_or_user");
            return;
        }
        StringBuilder out = new StringBuilder("visibilityProbe=" + BUILD + " user=" + args[0]);
        field(out, "mapsCanQueryHud", () -> {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object pm = activityThread.getMethod("getPackageManager").invoke(null);
            return Class.forName("android.content.pm.IPackageManager")
                    .getMethod("canPackageQuery", String.class, String.class, int.class)
                    .invoke(pm, GMapsDirectChannel.PACKAGE_NAME, "com.bydhud.app", Integer.parseInt(args[0]));
        });
        System.out.println(out);
    }
}
