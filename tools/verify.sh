#!/usr/bin/env bash
# tools/verify.sh — Comprehensive repository and project verification tool.
# Enforces guidelines from MASTER_PROMPT Annex B, F, G, and user instructions.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

echo "=========================================================="
echo "  Cinematic Android TV Launcher - System Verification     "
echo "=========================================================="

ERRORS=0

# 1. Verify MASTER_PROMPT.md integrity
if [ -f "$REPO_DIR/MASTER_PROMPT.md" ]; then
    echo "[OK] MASTER_PROMPT.md preserved."
else
    echo "[FAIL] MASTER_PROMPT.md is missing!"
    ERRORS=$((ERRORS + 1))
fi

# 2. Verify Android Project structure
MANDATORY_FILES=(
    "launcher/app/src/main/java/com/cinematic/tvlauncher/UpdateManager.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/UpdateVersionPolicy.java"
    "launcher/app/src/main/res/xml/file_paths.xml"
    ".github/workflows/release.yml"
    "settings.gradle"
    "build.gradle"
    "launcher/app/build.gradle"
    "launcher/app/src/main/AndroidManifest.xml"
    "launcher/app/src/main/res/values/config.xml"
    "launcher/app/src/main/res/values/strings.xml"
    "launcher/app/src/main/res/values/colors.xml"
    "launcher/app/src/main/res/values/styles.xml"
    "launcher/app/src/main/res/layout/activity_main.xml"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/MainActivity.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/background/CinematicBackgroundManager.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/background/RotationQueue.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/carousel/AppCarouselView.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/carousel/CarouselLayoutCalculator.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/controls/TvControlManager.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/discovery/AppDiscoveryManager.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/navigation/FocusNavigationController.java"
    "launcher/app/src/main/java/com/cinematic/tvlauncher/config/LauncherConfig.java"
    "launcher/app/proguard-rules.pro"
    "gradlew"
    "gradle/wrapper/gradle-wrapper.properties"
    "gradle/wrapper/gradle-wrapper.jar"
    ".github/workflows/build-apk.yml"
)

for file in "${MANDATORY_FILES[@]}"; do
    if [ -f "$REPO_DIR/$file" ]; then
        echo "[OK] Found: $file"
    else
        echo "[FAIL] Missing: $file"
        ERRORS=$((ERRORS + 1))
    fi
done

# 3. Check OpenJDK compiler
if command -v javac >/dev/null 2>&1; then
    echo "[OK] javac available: $(javac -version 2>&1)"
else
    echo "[FAIL] javac not found!"
    ERRORS=$((ERRORS + 1))
fi

# 4. Run unit test suite
echo ""
echo "Running unit tests..."
if bash tools/run_tests.sh; then
    echo "[OK] Unit test suite passed."
else
    echo "[FAIL] Unit test suite failed!"
    ERRORS=$((ERRORS + 1))
fi

echo ""
echo "=========================================================="
if [ "$ERRORS" -eq 0 ]; then
    echo "  ALL VERIFICATIONS PASSED SUCCESSFULLY                   "
    echo "=========================================================="
    exit 0
else
    echo "  VERIFICATION FAILED WITH $ERRORS ERROR(S)                "
    echo "=========================================================="
    exit 1
fi
