package com.cinematic.tvlauncher;

/**
 * TestMain — Test suite executor.
 * Compilable and executable using OpenJDK 17.
 */
public class TestMain {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  Cinematic Android TV Launcher - Unit Test Suite ");
        System.out.println("==================================================");

        int totalSuites = 7;
        int passedSuites = 0;

        try {
            System.out.println("1. Running RotationQueueTest (Annex C.2)...");
            RotationQueueTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] RotationQueueTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("2. Running CarouselMathTest (Annex C.1, D.3)...");
            CarouselMathTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] CarouselMathTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("3. Running LauncherConfigTest (Annex C, D)...");
            LauncherConfigTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] LauncherConfigTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("4. Running PinnedWheelSlotOrderTest (stable focus slot transitions)...");
            PinnedWheelSlotOrderTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] PinnedWheelSlotOrderTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("5. Running WatchNextEntryPolicyTest (empty intent URI and playback progress)...");
            WatchNextEntryPolicyTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] WatchNextEntryPolicyTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("6. Running UpdateVersionPolicyTest (monotonic release tags)...");
            UpdateVersionPolicyTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] UpdateVersionPolicyTest: " + t.getMessage());
            t.printStackTrace();
        }

        try {
            System.out.println("7. Running UpdateCheckPolicyTest (foreground update interval and retry backoff)...");
            UpdateCheckPolicyTest.runTests();
            passedSuites++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] UpdateCheckPolicyTest: " + t.getMessage());
            t.printStackTrace();
        }

        System.out.println("==================================================");
        System.out.println("Test Results: " + passedSuites + " / " + totalSuites + " test suites PASSED.");
        System.out.println("==================================================");

        if (passedSuites != totalSuites) {
            System.exit(1);
        }
    }
}
