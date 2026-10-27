package com.cinematic.tvlauncher.discovery;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

/** Discovers launchable apps and ranks the home row by actual recent usage when allowed. */
public class AppDiscoveryManager {
    private static final String TAG = "AppDiscoveryManager";
    private final Context context;
    private final PackageManager packageManager;
    private final String ownPackageName;

    public AppDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
        this.ownPackageName = this.context.getPackageName();
    }

    /** Alphabetical full library; maxLimit <= 0 means unlimited. */
    public List<AppItem> discoverApps(int maxLimit) {
        List<AppItem> apps = discoverAllApps();
        Collections.sort(apps, (a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        if (maxLimit > 0 && apps.size() > maxLimit) {
            return new ArrayList<>(apps.subList(0, maxLimit));
        }
        return apps;
    }

    /**
     * Five most recently used launchable apps. Usage access is a user-controlled
     * Android special access; without it, use a deterministic alphabetical fallback.
     */
    private static final String PREFS_NAME = "cinematic_launcher_prefs";
    private static final String KEY_RECENT_LAUNCH_PACKAGES = "recent_launch_packages";
    private static final int MAX_TRACKED_LAUNCHES = 20;

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
        prefs.edit().putString(KEY_RECENT_LAUNCH_PACKAGES, android.text.TextUtils.join(",", recent)).apply();
    }

    public List<AppItem> discoverRecentApps(int maxLimit) {
        List<AppItem> apps = discoverAllApps();
        Map<String, Integer> locallyTrackedOrder = getLocallyTrackedOrder();
        if (!hasUsageAccess()) {
            Collections.sort(apps, (a, b) -> {
                int ra = locallyTrackedOrder.containsKey(a.getPackageName())
                        ? locallyTrackedOrder.get(a.getPackageName()) : Integer.MAX_VALUE;
                int rb = locallyTrackedOrder.containsKey(b.getPackageName())
                        ? locallyTrackedOrder.get(b.getPackageName()) : Integer.MAX_VALUE;
                if (ra != rb) return Integer.compare(ra, rb);
                return a.getLabel().compareToIgnoreCase(b.getLabel());
            });
            return limit(apps, maxLimit);
        }

        Map<String, Long> lastUsed = new HashMap<>();
        long now = System.currentTimeMillis();
        long begin = now - 7L * 24L * 60L * 60L * 1000L;
        try {
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager != null) {
                List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now);
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
            long ta = lastUsed.containsKey(a.getPackageName()) ? lastUsed.get(a.getPackageName()) : 0L;
            long tb = lastUsed.containsKey(b.getPackageName()) ? lastUsed.get(b.getPackageName()) : 0L;
            boolean ra = ta >= oldestAllowed;
            boolean rb = tb >= oldestAllowed;
            if (ra != rb) return ra ? -1 : 1;
            if (ta != tb) return Long.compare(tb, ta);
            int recentOrderA = locallyTrackedOrder.containsKey(a.getPackageName())
                    ? locallyTrackedOrder.get(a.getPackageName()) : Integer.MAX_VALUE;
            int recentOrderB = locallyTrackedOrder.containsKey(b.getPackageName())
                    ? locallyTrackedOrder.get(b.getPackageName()) : Integer.MAX_VALUE;
            if (recentOrderA != recentOrderB) return Integer.compare(recentOrderA, recentOrderB);
            return a.getLabel().compareToIgnoreCase(b.getLabel());
        });
        return limit(apps, maxLimit);
    }

    private Map<String, Integer> getLocallyTrackedOrder() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = prefs.getString(KEY_RECENT_LAUNCH_PACKAGES, "");
        Map<String, Integer> order = new HashMap<>();
        if (!encoded.isEmpty()) {
            String[] packages = encoded.split(",");
            for (int i = 0; i < packages.length; i++) {
                if (!packages[i].isEmpty() && !order.containsKey(packages[i])) {
                    order.put(packages[i], i);
                }
            }
        }
        return order;
    }

    private List<AppItem> limit(List<AppItem> apps, int maxLimit) {
        if (maxLimit > 0 && apps.size() > maxLimit) return new ArrayList<>(apps.subList(0, maxLimit));
        return apps;
    }

    public boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.w(TAG, "Unable to check usage access", e);
            return false;
        }
    }

    public Intent createUsageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    private List<AppItem> discoverAllApps() {
        List<AppItem> apps = new ArrayList<>();
        Set<String> seenActivities = new HashSet<>();
        collectApps(apps, seenActivities, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collectApps(apps, seenActivities, Intent.CATEGORY_LAUNCHER, false);
        return apps;
    }

    private void collectApps(List<AppItem> apps, Set<String> seenActivities, String category, boolean isLeanback) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        if (resolved == null) return;
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            String activity = info.activityInfo.name;
            String key = pkg;
            if (pkg.equals(ownPackageName) || !seenActivities.add(key)) continue;
            AppItem item = createAppItem(info, isLeanback);
            if (item != null) apps.add(item);
        }
    }

    private AppItem createAppItem(ResolveInfo info, boolean isLeanback) {
        try {
            String pkg = info.activityInfo.packageName;
            String activity = info.activityInfo.name;
            CharSequence labelSeq = info.loadLabel(packageManager);
            String label = labelSeq != null ? labelSeq.toString().trim() : pkg;
            android.graphics.drawable.Drawable banner = null;
            if (isLeanback) {
                try { banner = info.activityInfo.loadBanner(packageManager); } catch (Exception ignored) {}
            }
            android.graphics.drawable.Drawable icon = null;
            try { icon = info.activityInfo.loadIcon(packageManager); } catch (Exception ignored) {}
            return new AppItem(pkg, activity, label, icon, banner, isLeanback);
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve app item info", e);
            return null;
        }
    }
}
