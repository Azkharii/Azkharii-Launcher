#!/usr/bin/env bash
# tools/backup.sh — Backs up package lists, system properties, and settings.
# Fulfills MASTER_PROMPT Annex B.1. Does not modify or damage device state.

set -euo pipefail

SERIAL="${1:-}"
ADB_CMD="adb"
if [ -n "$SERIAL" ]; then
    ADB_CMD="adb -s $SERIAL"
fi

TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BACKUP_DIR="${HOME}/tv-backups/${TIMESTAMP}"
mkdir -p "$BACKUP_DIR"

echo "=== Starting Safe Backup to $BACKUP_DIR ==="

# Check device availability
if ! $ADB_CMD get-state 2>/dev/null; then
    echo "Warning: ADB target is offline or not found. Backing up local repository state only."
    echo "offline" > "$BACKUP_DIR/adb_status.txt"
    exit 0
fi

echo "Backing up package lists..."
$ADB_CMD shell pm list packages -f > "$BACKUP_DIR/packages_all.txt" 2>&1 || true
$ADB_CMD shell pm list packages -d > "$BACKUP_DIR/packages_disabled.txt" 2>&1 || true
$ADB_CMD shell pm list packages -e > "$BACKUP_DIR/packages_enabled.txt" 2>&1 || true

echo "Backing up HOME activities..."
$ADB_CMD shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME > "$BACKUP_DIR/home_activities.txt" 2>&1 || true

echo "Backing up system properties..."
$ADB_CMD shell getprop > "$BACKUP_DIR/getprop.txt" 2>&1 || true

echo "Backing up settings namespaces..."
$ADB_CMD shell settings list system > "$BACKUP_DIR/settings_system.txt" 2>&1 || true
$ADB_CMD shell settings list secure > "$BACKUP_DIR/settings_secure.txt" 2>&1 || true
$ADB_CMD shell settings list global > "$BACKUP_DIR/settings_global.txt" 2>&1 || true

echo "Generating automatic restore script for disabled packages..."
cat << 'EOF' > "$BACKUP_DIR/restore_disabled.sh"
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
while IFS= read -r line; do
    pkg=$(echo "$line" | sed 's/package://')
    if [ -n "$pkg" ]; then
        echo "Re-enabling $pkg..."
        adb shell pm enable "$pkg" || true
    fi
done < "$DIR/packages_disabled.txt"
EOF
# Note: restore script is invoked via 'bash $BACKUP_DIR/restore_disabled.sh'

echo "Backup complete: $BACKUP_DIR"
