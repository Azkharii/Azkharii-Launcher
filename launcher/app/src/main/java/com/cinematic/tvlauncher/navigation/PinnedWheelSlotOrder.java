package com.cinematic.tvlauncher.navigation;

import java.util.ArrayList;
import java.util.List;

/** Circular-wheel transition that keeps retained views attached to their parent. */
public final class PinnedWheelSlotOrder {
    private PinnedWheelSlotOrder() {}
    public static <T> List<T> rotate(List<T> currentSlots, T incoming, int delta) {
        if (currentSlots == null || currentSlots.isEmpty()) return new ArrayList<>();
        if (delta != -1 && delta != 1) throw new IllegalArgumentException("delta must be -1 or 1");
        int n = currentSlots.size();
        List<T> result = new ArrayList<>(n);
        if (delta > 0) { result.addAll(currentSlots.subList(1,n)); result.add(incoming); }
        else { result.add(incoming); result.addAll(currentSlots.subList(0,n-1)); }
        return result;
    }
    public static <T> T outgoing(List<T> currentSlots, int delta) {
        if (currentSlots == null || currentSlots.isEmpty()) return null;
        if (delta != -1 && delta != 1) throw new IllegalArgumentException("delta must be -1 or 1");
        return currentSlots.get(delta > 0 ? 0 : currentSlots.size()-1);
    }
}