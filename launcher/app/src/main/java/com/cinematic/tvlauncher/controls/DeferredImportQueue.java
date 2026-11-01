package com.cinematic.tvlauncher.controls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Keeps user-selected import items pending until foreground work can process them.
 * Snapshot does not consume entries; callers remove them only after successful completion.
 */
public final class DeferredImportQueue<T> {
    private final LinkedHashSet<T> pending = new LinkedHashSet<>();

    public synchronized void addAll(Collection<? extends T> items) {
        if (items == null) return;
        for (T item : items) {
            if (item != null) pending.add(item);
        }
    }

    public synchronized List<T> snapshot(boolean foreground) {
        if (!foreground || pending.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(pending);
    }

    public synchronized void removeAll(Collection<? extends T> completed) {
        if (completed != null) pending.removeAll(completed);
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
}
