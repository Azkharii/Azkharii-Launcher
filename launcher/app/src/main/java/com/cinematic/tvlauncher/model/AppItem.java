package com.cinematic.tvlauncher.model;

import android.graphics.drawable.Drawable;
import java.util.Objects;

/**
 * AppItem — Represents an installed application displayed in the bottom carousel.
 */
public class AppItem {
    private final String packageName;
    private final String activityName;
    private final String label;
    private final Drawable icon;
    private final Drawable banner;
    private final boolean isLeanback;

    public AppItem(String packageName, String activityName, String label, Drawable icon, Drawable banner, boolean isLeanback) {
        this.packageName = packageName;
        this.activityName = activityName;
        this.label = label != null ? label : packageName;
        this.icon = icon;
        this.banner = banner;
        this.isLeanback = isLeanback;
    }

    public String getPackageName() { return packageName; }
    public String getActivityName() { return activityName; }
    public String getLabel() { return label; }
    public Drawable getIcon() { return icon; }
    public Drawable getBanner() { return banner != null ? banner : icon; }
    public boolean isLeanback() { return isLeanback; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppItem appItem = (AppItem) o;
        return Objects.equals(packageName, appItem.packageName) &&
               Objects.equals(activityName, appItem.activityName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, activityName);
    }

    @Override
    public String toString() {
        return "AppItem{" + label + " (" + packageName + ")}";
    }
}
