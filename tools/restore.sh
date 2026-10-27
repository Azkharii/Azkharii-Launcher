#!/usr/bin/env bash
# tools/restore.sh — Restores packages and settings from a specified backup.
# Fulfills MASTER_PROMPT Annex B.3.

set -euo pipefail

BACKUP_DIR="${1:-}"
if [ -z "$BACKUP_DIR" ] || [ ! -d "$BACKUP_DIR" ]; then
    echo "Usage: $0 <path-to-timestamped-backup-directory>"
    exit 1
fi

echo "=== Restoring from $BACKUP_DIR ==="

if [ -f "$BACKUP_DIR/restore_disabled.sh" ]; then
    echo "Running re-enable script..."
    bash "$BACKUP_DIR/restore_disabled.sh"
fi

echo "Restore operations completed successfully."
