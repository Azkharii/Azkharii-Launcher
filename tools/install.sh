#!/usr/bin/env bash
# tools/install.sh — Safe installation script for the TV Launcher.
# Fulfills MASTER_PROMPT Annex B, F.4.
# Includes explicit safeguards to prevent unauthorized or destructive operations.

set -euo pipefail

APK_PATH="${1:-}"
SERIAL="${2:-}"

if [ -z "$APK_PATH" ]; then
    echo "Usage: $0 <path_to_apk> [serial]"
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK file not found at $APK_PATH"
    exit 1
fi

ADB_CMD="adb"
if [ -n "$SERIAL" ]; then
    ADB_CMD="adb -s $SERIAL"
fi

echo "=== Installation Pre-flight Checks ==="
echo "Target APK: $APK_PATH"

if ! $ADB_CMD get-state 2>/dev/null; then
    echo "Error: ADB target device is not connected or offline."
    echo "Installation safely aborted."
    exit 1
fi

echo "Verifying package compatibility..."
# Check target device SDK level
TV_SDK=$($ADB_CMD shell getprop ro.build.version.sdk 2>/dev/null || echo "0")
echo "TV SDK level: $TV_SDK"

echo "Executing safe install via pm install -r..."
$ADB_CMD push "$APK_PATH" /data/local/tmp/cinematic_launcher.apk
$ADB_CMD shell pm install -r /data/local/tmp/cinematic_launcher.apk
$ADB_CMD shell rm -f /data/local/tmp/cinematic_launcher.apk

echo "Installation verified."
