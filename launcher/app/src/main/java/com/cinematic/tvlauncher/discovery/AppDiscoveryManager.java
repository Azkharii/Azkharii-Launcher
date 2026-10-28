package com.cinematic.tvlauncher.discovery;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.cinematic.tvlauncher.model.AppItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers launchable applications and ranks the home row by actual recent usage when allowed.
 * Package metadata is cached until Android reports a package change; heavyweight icons and TV
 * banners are materialized only for items a caller will actually display.
 */
public class AppDiscoveryManager {
    private static final String TAG = "AppDiscoveryManager";
    private final Context context;
    private final PackageManager packageManager;
    private final String ownPackageName;
    private List<AppMetadata> installedAppMetadata;

    private static final String PREFS_NAME = "cinematic_launcher_prefs";
    private static final String KEY_RECENT_LAUNCH_PACKAGES = "recent_launch_packages";
    private static final int MAX_TRACKED_LAUNCHES = 20;

    public AppDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
        this.ownPackageName = this.context.getPackageName();
    }

    /** Drop package metadata after LauncherApps reports an install, removal, or component change. */
    public synchronized void invalidateInstalledAppsCache() {
        installedAppMetadata = null;
    }

    /** Alphabetical full library; maxLimit <= 0 means unlimited. */
    public List<AppItem> discoverApps(int maxLimit) {
        List<AppMetadata> apps = getInstalledAppMetadata();
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return materialize(limitMetadata(apps, maxLimit));
    }

    /** Returns only the requested package icons, avoiding loading artwork for the whole library. */
    public List<AppItem> discoverApps(Set<String> packageNames) {
        if (packageNames == null || packageNames.isEmpty()) return new ArrayList<>();
        List<AppMetadata> selected = new ArrayList<>();
        for (AppMetadata item : getInstalledAppMetadata()) {
            if (packageNames.contains(item.packageName)) selected.add(item);
        }
        Collections.sort(selected, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return materialize(selected);
    }

    /**
     * Records launches initiated by this launcher. This is a truthful fallback when Android's
     * Usage Access special permission is unavailable; it does not claim to observe launches
     * performed by other launchers or system surfaces.
     */
    public void recordAppLaunch(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()
                || packageName.equals(ownPackageName)) return;
        String normalized = packageName.trim();
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = prefs.getString(KEY_RECENT_LAUNCH_PACKAGES, "");
        List<String> recent = new ArrayList<>();
        if (!encoded.isEmpty()) {
            for (String item : encoded.split(",")) {
                if (!item.isEmpty() && !normalized.equals(item) && !recent.contains(item)) {
                    recent.add(item);
                }
            }
        }
        recent.add(0, normalized);
        if (recent.size() > MAX_TRACKED_LAUNCHES) {
            recent = new ArrayList<>(recent.subList(0, MAX_TRACKED_LAUNCHES));
        }
        prefs.edit().putString(KEY_RECENT_LAUNCH_PACKAGES,
                android.text.TextUtils.join(",", recent)).apply();
    }

    public List<AppItem> discoverRecentApps(int maxLimit) {
        List<AppMetadata> apps = getInstalledAppMetadata();
        Map<String, Integer> locallyTrackedOrder = getLocallyTrackedOrder();

        if (!hasUsageAccess()) {
            Collections.sort(apps, (a, b) -> {
                int ra = locallyTrackedOrder.containsKey(a.packageName)
                        ? locallyTrackedOrder.get(a.packageName) : Integer.MAX_VALUE;
                int rb = locallyTrackedOrder.containsKey(b.packageName)
                        ? locallyTrackedOrder.get(b.packageName) : Integer.MAX_VALUE;
                if (ra != rb) return Integer.compare(ra, rb);
                return a.label.compareToIgnoreCase(b.label);
            });
            return materialize(limitMetadata(apps, maxLimit));
        }

        Map<String, Long> lastUsed = new HashMap<>();
        long now = System.currentTimeMillis();
        long begin = now - 7L * 24L * 60L * 60L * 1000L;
        try {
            UsageStatsManager manager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager != null) {
                List<UsageStats> stats = manager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, begin, now);
                if (stats != null) {
                    for (UsageStats stat : stats) {
                        if (stat != null && stat.getLastTimeUsed() > 0) {
                            Long old = lastUsed.get(stat.getPackageName());
                            if (old == null || stat.getLastTimeUsed() > old) {
                                lastUsed.put(stat.getPackageName(), stat.getLastTimeUsed());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Usage statistics unavailable; using alphabetical fallback", e);
        }

        final long oldestAllowed = now - 30L * 24L * 60L * 60L * 1000L;
        Collections.sort(apps, (a, b) -> {
            long ta = lastUsed.containsKey(a.packageName) ? lastUsed.get(a.packageName) : 0L;
            long tb = lastUsed.containsKey(b.packageName) ? lastUsed.get(b.packageName) : 0L;
            boolean ra = ta >= oldestAllowed;
            boolean rb = tb >= oldestAllowed;
            if (ra != rb) return ra ? -1 : 1;
            if (ta != tb) return Long.compare(tb, ta);
            int recentOrderA = locallyTrackedOrder.containsKey(a.packageName)
                    ? locallyTrackedOrder.get(a.packageName) : Integer.MAX_VALUE;
            int recentOrderB = locallyTrackedOrder.containsKey(b.packageName)
                    ? locallyTrackedOrder.get(b.packageName) : Integer.MAX_VALUE;
            if (recentOrderA != recentOrderB) return Integer.compare(recentOrderA, recentOrderB);
            return a.label.compareToIgnoreCase(b.label);
        });
        return materialize(limitMetadata(apps, maxLimit));
    }

    private List<String> getLocallyTrackedPackages() {
        return java.util.Arrays.asList(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RECENT_LAUNCH_PACKAGES, "").split(","));
    }

    private Map<String, Integer> getLocallyTrackedOrder() {
        Map<String, Integer> order = new HashMap<>();
        List<String> packages = getLocallyTrackedPackages();
        for (int i = 0; i < packages.size(); i++) {
            String packageName = packages.get(i);
            if (!packageName.isEmpty() && !order.containsKey(packageName)) order.put(packageName, i);
        }
        return order;
    }

    private List<AppMetadata> limitMetadata(List<AppMetadata> apps, int maxLimit) {
        if (maxLimit > 0 && apps.size() > maxLimit) {
            return new ArrayList<>(apps.subList(0, maxLimit));
        }
        return apps;
    }

    public boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.w(TAG, "Unable to check usage access", e);
            return false;
        }
    }

    public Intent createUsageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    private synchronized List<AppMetadata> getInstalledAppMetadata() {
        if (installedAppMetadata == null) installedAppMetadata = scanInstalledAppMetadata();
        return new ArrayList<>(installedAppMetadata);
    }

    private List<AppMetadata> scanInstalledAppMetadata() {
        List<AppMetadata> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        collectApps(apps, seenPackages, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collectApps(apps, seenPackages, Intent.CATEGORY_LAUNCHER, false);
        return apps;
    }

    private void collectApps(List<AppMetadata> apps, Set<String> seenPackages,
            String category, boolean isLeanback) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        if (resolved == null) return;
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            String activity = info.activityInfo.name;
            if (packageName.equals(ownPackageName) || !seenPackages.add(packageName)) continue;
            try {
                CharSequence labelSeq = info.loadLabel(packageManager);
                String label = labelSeq != null ? labelSeq.toString().trim() : packageName;
                apps.add(new AppMetadata(packageName, activity, label, isLeanback));
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve app label for " + packageName, e);
            }
        }
    }

    private List<AppItem> materialize(List<AppMetadata> metadata) {
        List<AppItem> items = new ArrayList<>(metadata.size());
        for (AppMetadata item : metadata) {
            AppItem materialized = materialize(item);
            if (materialized != null) items.add(materialized);
        }
        return items;
    }

    private AppItem materialize(AppMetadata item) {
        try {
            ActivityInfo info = packageManager.getActivityInfo(
                    new ComponentName(item.packageName, item.activityName), 0);
            Drawable icon = info.loadIcon(packageManager);
            Drawable banner = null;
            if (item.isLeanback) {
                try { banner = info.loadBanner(packageManager); } catch (Exception ignored) {}
            }
            return new AppItem(item.packageName, item.activityName, item.label, icon, banner, item.isLeanback);
        } catch (Exception e) {
            // A package may have been removed between metadata lookup and artwork loading.
            Log.d(TAG, "App disappeared while materializing " + item.packageName, e);
            return null;
        }
    }

    private static final class AppMetadata {
        final String packageName;
        final String activityName;
        final String label;
        final boolean isLeanback;

        AppMetadata(String packageName, String activityName, String label, boolean isLeanback) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.isLeanback = isLeanback;
        }
    }
}
