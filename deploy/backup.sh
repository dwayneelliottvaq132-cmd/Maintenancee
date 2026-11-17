#!/usr/bin/env bash
# Nightly backup: PostgreSQL dump + uploaded documents + generated reports.
# Reads settings from the environment (systemd loads /etc/cmms/cmms.env).
set -euo pipefail
DIR="${CMMS_BACKUP_DIR:-/var/backups/cmms}"
KEEP="${CMMS_BACKUP_KEEP_DAYS:-30}"
STAMP="$(date +%Y%m%d-%H%M%S)"
URL="${CMMS_DB_URL:-jdbc:postgresql://localhost:5432/cmms}"
HOSTPORT="${URL#jdbc:postgresql://}"; HOSTPORT="${HOSTPORT%%/*}"
DB="${URL##*/}"; DB="${DB%%\?*}"
HOST="${HOSTPORT%%:*}"; PORT="5432"; [[ "$HOSTPORT" == *:* ]] && PORT="${HOSTPORT##*:}"

mkdir -p "$DIR"
PGPASSWORD="${CMMS_DB_PASSWORD:-}" pg_dump -h "$HOST" -p "$PORT" -U "${CMMS_DB_USER:-cmms}" -Fc -f "$DIR/cmms-db-$STAMP.dump" "$DB"
tar -czf "$DIR/cmms-files-$STAMP.tar.gz" -C /var/lib/cmms uploads reports 2>/dev/null || true
find "$DIR" -maxdepth 1 -type f \( -name 'cmms-db-*.dump' -o -name 'cmms-files-*.tar.gz' \) -mtime +"$KEEP" -delete
echo "Backup written to $DIR (stamp $STAMP)"
# Restore:  pg_restore -h localhost -U cmms -d cmms --clean --if-exists cmms-db-STAMP.dump
#           tar -xzf cmms-files-STAMP.tar.gz -C /var/lib/cmms
