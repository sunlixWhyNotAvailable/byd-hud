package com.bydhud.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates user-facing applications that can be selected for dashboard transfer.
 *
 * <p>{@link #load(Collection)} performs a PackageManager query and icon rasterization, so callers must
 * invoke it from a worker thread. The returned list and entries are immutable snapshots.</p>
 */
public final class InstalledTransferAppCatalog {
    static final int DEFAULT_ICON_SIZE_PX = 96;
    private static final int FALLBACK_ICON_COLOR = 0xFF64748B;

    private final Context context;
    private final int iconSizePx;

    public InstalledTransferAppCatalog(Context context) {
        this(context, DEFAULT_ICON_SIZE_PX);
    }

    InstalledTransferAppCatalog(Context context, int iconSizePx) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        if (iconSizePx <= 0) {
            throw new IllegalArgumentException("iconSizePx <= 0");
        }
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
        this.iconSizePx = iconSizePx;
    }

    /**
     * Loads launchable non-system user applications, sorted by localized label and package name.
     * The operation is intentionally synchronous so the caller controls its executor/lifecycle.
     */
    /** Reuses unchanged entries so runtime refreshes do not decode the same icons again. */
    public List<Entry> load(Collection<Entry> cachedEntries) {
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            return Collections.emptyList();
        }
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try {
            resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
        if (resolved == null || resolved.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Entry> cachedByPackage = new HashMap<>();
        if (cachedEntries != null) {
            for (Entry entry : cachedEntries) {
                if (entry != null) cachedByPackage.put(entry.packageName, entry);
            }
        }
        Map<String, Entry> unique = new HashMap<>();
        String selfPackage = context.getPackageName();
        for (ResolveInfo resolveInfo : resolved) {
            ActivityInfo activityInfo = resolveInfo == null ? null : resolveInfo.activityInfo;
            ApplicationInfo appInfo = activityInfo == null ? null : activityInfo.applicationInfo;
            String packageName = appInfo == null
                    ? activityPackageName(activityInfo)
                    : appInfo.packageName;
            if (!isLaunchableUserPackage(packageName, appInfo, selfPackage)) {
                continue;
            }
            String label = loadLabel(packageManager, appInfo, resolveInfo, packageName);
            if (unique.containsKey(packageName)) {
                continue;
            }
            Entry cached = cachedByPackage.get(packageName);
            if (cached != null && cached.label.equals(label)) {
                unique.put(packageName, cached);
            } else {
                IconSnapshot icon = loadIcon(packageManager, appInfo, resolveInfo, iconSizePx);
                unique.put(packageName, new Entry(packageName, label, icon));
            }
        }

        List<Entry> result = new ArrayList<>(unique.values());
        result.sort(entryComparator());
        return Collections.unmodifiableList(result);
    }

    /** Returns an existing selection or a package-preserving neutral fallback. */
    public static Entry selectionOrFallback(Collection<Entry> entries, String packageName) {
        String normalized = normalizePackage(packageName);
        if (entries != null && !normalized.isEmpty()) {
            for (Entry entry : entries) {
                if (entry != null && normalized.equals(entry.packageName)) {
                    return entry;
                }
            }
        }
        return Entry.fallback(normalized.isEmpty() ? packageName : normalized);
    }

    static List<Candidate> filterAndSortCandidates(
            Collection<Candidate> candidates,
            String selfPackage) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedSelf = normalizePackage(selfPackage);
        Map<String, Candidate> unique = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String packageName = normalizePackage(candidate.packageName);
            if (packageName.isEmpty()
                    || packageName.equals(normalizedSelf)
                    || !candidate.launchable
                    || candidate.system) {
                continue;
            }
            String label = fallbackLabel(candidate.label, packageName);
            if (!unique.containsKey(packageName)) {
                unique.put(packageName, new Candidate(packageName, label, false, true));
            }
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        result.sort(candidateComparator());
        return Collections.unmodifiableList(result);
    }

    static String fallbackLabel(String label, String packageName) {
        if (label == null || label.trim().isEmpty()) {
            return packageName == null ? "" : packageName;
        }
        return label.trim();
    }

    private static Comparator<Entry> entryComparator() {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        return (left, right) -> {
            int labelOrder = collator.compare(left.label, right.label);
            if (labelOrder != 0) {
                return labelOrder;
            }
            return left.packageName.compareToIgnoreCase(right.packageName);
        };
    }

    private static Comparator<Candidate> candidateComparator() {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        return (left, right) -> {
            int labelOrder = collator.compare(left.label, right.label);
            if (labelOrder != 0) {
                return labelOrder;
            }
            return left.packageName.compareToIgnoreCase(right.packageName);
        };
    }

    private static String activityPackageName(ActivityInfo activityInfo) {
        return activityInfo == null ? "" : activityInfo.packageName;
    }

    private static boolean isLaunchableUserPackage(
            String packageName,
            ApplicationInfo appInfo,
            String selfPackage) {
        String normalizedPackage = normalizePackage(packageName);
        if (normalizedPackage.isEmpty()
                || normalizedPackage.equals(normalizePackage(selfPackage))) {
            return false;
        }
        if (appInfo == null) {
            return true;
        }
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (appInfo.flags & systemFlags) == 0;
    }

    private static String loadLabel(
            PackageManager packageManager,
            ApplicationInfo appInfo,
            ResolveInfo resolveInfo,
            String packageName) {
        try {
            CharSequence label = appInfo == null
                    ? (resolveInfo == null ? null : resolveInfo.loadLabel(packageManager))
                    : appInfo.loadLabel(packageManager);
            return fallbackLabel(label == null ? null : label.toString(), packageName);
        } catch (RuntimeException ignored) {
            return fallbackLabel(null, packageName);
        }
    }

    private static IconSnapshot loadIcon(
            PackageManager packageManager,
            ApplicationInfo appInfo,
            ResolveInfo resolveInfo,
            int iconSizePx) {
        try {
            Drawable drawable = appInfo == null
                    ? (resolveInfo == null ? null : resolveInfo.loadIcon(packageManager))
                    : appInfo.loadIcon(packageManager);
            if (drawable != null) {
                return IconSnapshot.fromDrawable(drawable, iconSizePx);
            }
        } catch (RuntimeException ignored) {
            // Package removal or a broken icon must not discard a valid package selection.
        }
        return IconSnapshot.neutral();
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    /** Immutable selector entry. */
    public static final class Entry {
        private final String packageName;
        private final String label;
        private final IconSnapshot icon;

        private Entry(String packageName, String label, IconSnapshot icon) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = fallbackLabel(label, this.packageName);
            this.icon = icon == null ? IconSnapshot.neutral() : icon;
        }

        static Entry fallback(String packageName) {
            String normalized = normalizePackage(packageName);
            return new Entry(normalized, normalized, IconSnapshot.neutral());
        }

        public String packageName() {
            return packageName;
        }

        public String label() {
            return label;
        }

        public IconSnapshot icon() {
            return icon;
        }

        public boolean isFallback() {
            return icon.isNeutral();
        }
    }

    /** Immutable ARGB icon snapshot; convert to a Compose bitmap at the UI boundary. */
    public static final class IconSnapshot {
        private final int width;
        private final int height;
        private final int[] pixels;
        private final boolean neutral;

        private IconSnapshot(int width, int height, int[] pixels, boolean neutral) {
            this.width = width;
            this.height = height;
            this.pixels = pixels.clone();
            this.neutral = neutral;
        }

        static IconSnapshot fromDrawable(Drawable source, int sizePx) {
            Drawable drawable = source.mutate();
            int intrinsicWidth = Math.max(1, drawable.getIntrinsicWidth());
            int intrinsicHeight = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min(
                    (float) sizePx / intrinsicWidth,
                    (float) sizePx / intrinsicHeight);
            int drawWidth = Math.max(1, Math.round(intrinsicWidth * scale));
            int drawHeight = Math.max(1, Math.round(intrinsicHeight * scale));
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int left = (sizePx - drawWidth) / 2;
            int top = (sizePx - drawHeight) / 2;
            drawable.setBounds(left, top, left + drawWidth, top + drawHeight);
            drawable.draw(canvas);
            int[] pixels = new int[sizePx * sizePx];
            bitmap.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            bitmap.recycle();
            return new IconSnapshot(sizePx, sizePx, pixels, false);
        }

        static IconSnapshot neutral() {
            return new IconSnapshot(1, 1, new int[]{FALLBACK_ICON_COLOR}, true);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public boolean isNeutral() {
            return neutral;
        }

        public int[] copyPixels() {
            return pixels.clone();
        }

        public Bitmap toBitmap() {
            return Bitmap.createBitmap(copyPixels(), width, height, Bitmap.Config.ARGB_8888);
        }
    }

    static final class Candidate {
        final String packageName;
        final String label;
        final boolean system;
        final boolean launchable;

        Candidate(String packageName, String label, boolean system, boolean launchable) {
            this.packageName = packageName;
            this.label = label;
            this.system = system;
            this.launchable = launchable;
        }
    }
}
