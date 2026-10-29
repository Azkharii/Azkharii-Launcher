#!/usr/bin/env bash
# tools/run_tests.sh — Compiles and executes the unit test suite using OpenJDK 17.
# Tests business logic: 10s rotation queue, carousel math/scaling/alpha, config validation.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

BUILD_DIR="$REPO_DIR/build/test-classes"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "=== Compiling Unit Tests with OpenJDK 17 ==="
javac -d "$BUILD_DIR" \
    launcher/app/src/main/java/com/cinematic/tvlauncher/config/LauncherConfig.java \
    launcher/app/src/main/java/com/cinematic/tvlauncher/background/RotationQueue.java \
    launcher/app/src/main/java/com/cinematic/tvlauncher/carousel/CarouselLayoutCalculator.java \
    launcher/app/src/main/java/com/cinematic/tvlauncher/navigation/PinnedWheelSlotOrder.java \
    launcher/app/src/main/java/com/cinematic/tvlauncher/WatchNextEntryPolicy.java \
    launcher/app/src/main/java/com/cinematic/tvlauncher/UpdateVersionPolicy.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/UpdateVersionPolicyTest.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/WatchNextEntryPolicyTest.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/PinnedWheelSlotOrderTest.java \
    launcher/app/src/test/java/android/content/Context.java \
    launcher/app/src/test/java/android/content/res/Resources.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/R.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/RotationQueueTest.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/CarouselMathTest.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/LauncherConfigTest.java \
    launcher/app/src/test/java/com/cinematic/tvlauncher/TestMain.java

echo "Compilation successful."
echo ""
echo "=== Executing Tests with Java Assertions (-ea) ==="
java -ea -cp "$BUILD_DIR" com.cinematic.tvlauncher.TestMain

echo "=== All Tests Completed Successfully ==="
