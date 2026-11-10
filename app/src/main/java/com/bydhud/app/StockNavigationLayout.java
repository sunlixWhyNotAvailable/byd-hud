package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Looper;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Cached OEM service discovery shared by automatic TBT, widget and native layout. */
final class StockNavigationLayout {
    private static final String LAUNCHER = "com.byd.launchermap";
    private static final String NAVIAUTO = "com.byd.naviauto";
    private static final String SERVICE = "com.autosdk.protocol.service.ProtocolService";
    private static volatile FutureTask<Selection> pending;

    private StockNavigationLayout() { }

    static synchronized void initialize(Context context) {
        if (pending != null) return;
        Context app = context.getApplicationContext();
        FutureTask<Selection> task = new FutureTask<>(() -> discover(app));
        pending = task;
        new Thread(task, "BydHudStockNavigation").start();
    }

    static synchronized void onPackageChanged(Context context, String packageName) {
        if (!LAUNCHER.equals(packageName) && !NAVIAUTO.equals(packageName)) return;
        pending = null;
        initialize(context);
    }

    static String dispatch(Context context, int operation, BooleanSupplier current) {
        if (!StockMapProtocol30011.isSupportedOperation(operation)) {
            return "unsupported operation=" + operation;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "stock layout requires background dispatch";
        }
        FutureTask<Selection> task;
        synchronized (StockNavigationLayout.class) {
            initialize(context);
            task = pending;
        }
        try {
            Selection selection = task.get(NaviautoLayout.TIMEOUT_MS, TimeUnit.MILLISECONDS);
            BooleanSupplier valid = () -> pending == task && current != null
                    && current.getAsBoolean();
            if (!valid.getAsBoolean()) return "stock layout cancelled: stale selection or request";
            if (!selection.error.isEmpty()) return selection.error;
            if (LAUNCHER.equals(selection.packageName)) {
                return StockMapProtocol30011.dispatch(context, operation, valid);
            }
            InstrumentProxyManager.Result result = InstrumentProxyManager.get(context)
                    .setNaviautoLayout(operation, valid);
            String detail = "provider=" + selection.packageName + " operation=" + operation
                    + " " + result.navigationDetail + " error=" + result.error;
            AppEventLogger.event(context, "stock_layout " + detail);
            if (!result.available || result.intentionallySkipped) {
                return result.error.isEmpty() ? "naviauto layout unavailable: " + result.skipReason
                        : result.error;
            }
            return result.error;
        } catch (ExecutionException failure) {
            synchronized (StockNavigationLayout.class) {
                if (pending == task) pending = null;
            }
            return FailureDiagnostics.format(failure, "stock-layout", "discovery");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return FailureDiagnostics.format(failure, "stock-layout", "wait");
        } catch (Exception failure) {
            return FailureDiagnostics.format(failure, "stock-layout", "dispatch");
        }
    }

    private static Selection discover(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Candidate launcher = candidate(pm, LAUNCHER);
            Candidate naviauto = candidate(pm, NAVIAUTO);
            String selected = "";
            String error = "";
            if (launcher.available && naviauto.available) {
                error = "stock layout provider ambiguous: LauncherMap and naviauto services enabled";
            } else if (launcher.available) selected = LAUNCHER;
            else if (naviauto.available) selected = NAVIAUTO;
            else error = "stock layout service unavailable: " + launcher.detail + "; " + naviauto.detail;
            AppEventLogger.event(context, "stock_layout discovery " + launcher.detail
                    + "; " + naviauto.detail + " selected=" + selected + " error=" + error);
            return new Selection(selected, error);
        } catch (Exception failure) {
            String error = FailureDiagnostics.format(failure, "stock-layout", "discovery");
            AppEventLogger.event(context, "stock_layout " + error);
            throw new IllegalStateException(error, failure);
        }
    }

    private static Candidate candidate(PackageManager pm, String packageName) throws Exception {
        try {
            ComponentName component = new ComponentName(packageName, SERVICE);
            ServiceInfo service = pm.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            boolean appEnabled = enabled(pm.getApplicationEnabledSetting(packageName),
                    service.applicationInfo.enabled);
            boolean serviceEnabled = enabled(pm.getComponentEnabledSetting(component), service.enabled);
            boolean installed = (service.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            boolean available = installed && appEnabled && serviceEnabled && service.exported;
            return new Candidate(available, packageName + " version=" + info.getLongVersionCode()
                    + " appEnabled=" + appEnabled + " serviceEnabled=" + serviceEnabled
                    + " installed=" + installed + " exported=" + service.exported);
        } catch (PackageManager.NameNotFoundException absent) {
            return new Candidate(false, packageName + " service absent");
        }
    }

    private static boolean enabled(int setting, boolean manifestEnabled) {
        return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && manifestEnabled);
    }

    private static final class Candidate {
        final boolean available;
        final String detail;
        Candidate(boolean available, String detail) { this.available = available; this.detail = detail; }
    }

    private static final class Selection {
        final String packageName, error;
        Selection(String packageName, String error) { this.packageName = packageName; this.error = error; }
    }
}
