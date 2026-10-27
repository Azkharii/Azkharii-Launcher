#!/usr/bin/env bash
# tools/build.sh — Builds the Cinematic Android TV Launcher APK.
# Fulfills MASTER_PROMPT Annex F (Build and Installation).

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

echo "=== Checking Build Environment ==="

if command -v javac >/dev/null 2>&1; then
    echo "Java compiler (javac): $(javac -version 2>&1)"
else
    echo "Error: javac not found."
    exit 1
fi

# Check for Gradle
if command -v gradle >/dev/null 2>&1; then
    echo "Gradle found: $(gradle -v | head -n 3)"
    echo "Executing Gradle assembleRelease..."
    gradle assembleRelease
    exit 0
fi

if [ -f "./gradlew" ]; then
    echo "Gradle wrapper found. Executing ./gradlew assembleRelease..."
    ./gradlew assembleRelease
    exit 0
fi

echo "Notice: Gradle build tool is not available in this local environment."
echo "Running standalone verification and test-runner..."
bash tools/run_tests.sh
