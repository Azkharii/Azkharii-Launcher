package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.controls.DeferredImportQueue;
import java.util.Arrays;
import java.util.List;

/** Regression tests for photo-picker results arriving before Activity.onResume(). */
public final class DeferredImportQueueTest {
    public static void runTests() {
        DeferredImportQueue<String> queue = new DeferredImportQueue<>();
        queue.addAll(Arrays.asList("content://photos/1", "content://photos/2"));

        assert queue.snapshot(false).isEmpty()
                : "Paused manager must not start import work";
        assert queue.size() == 2
                : "Selections must remain pending while the manager is paused";

        List<String> resumed = queue.snapshot(true);
        assert resumed.equals(Arrays.asList("content://photos/1", "content://photos/2"))
                : "Resume must preserve all selected URIs and their order";
        assert queue.size() == 2
                : "Taking a work snapshot must not consume pending selections";

        queue.addAll(Arrays.asList("content://photos/2", "content://photos/3"));
        assert queue.snapshot(true).equals(Arrays.asList(
                "content://photos/1", "content://photos/2", "content://photos/3"))
                : "Repeated selections must be deduplicated without changing order";

        queue.removeAll(Arrays.asList("content://photos/1", "content://photos/2"));
        assert queue.snapshot(true).equals(Arrays.asList("content://photos/3"))
                : "Only successfully completed selections should be removed";
        System.out.println("  [PASS] DeferredImportQueueTest: all 4 assertions passed.");
    }
}
