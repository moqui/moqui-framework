#!/bin/bash
set -euo pipefail

# Rotating backup for YugabyteDB using native distributed snapshots (yb-admin).
# Snapshots are cluster-wide consistent and require no application downtime.
#
# This script backs up ALL tables EXCEPT PARAMETER_LOG (and other tables listed
# in EXCLUDED_TABLES). PARAMETER_LOG is a high-volume append-only time-series
# table managed separately by yugabyte_paramlog_backup.sh (differential export).
#
# Retention policy:
#   - Daily  : last 7 days
#   - Monthly: most recent backup per calendar month, up to 6 months
#   - Yearly : most recent backup per calendar year,  up to 1 year
#
# The script waits for the snapshot to reach COMPLETE state before exporting it
# (create_snapshot is asynchronous in YugabyteDB).
#
# If TLS is enabled on your cluster, set YB_CERTS_DIR.
#
# Example crontab (daily at midnight): 0 0 * * * /opt/moqui/yugabyte_backup.sh
#
# Restore (uncomment / adapt):
#   pigz -d -c <backup>.tar.gz | tar -x -C /tmp
#   yb-admin -master_addresses $YB_MASTER_ADDRESSES \
#     import_snapshot "file:///tmp/<backup-dir>" "ysql.${DB_NAME}" "ysql.<target-db>"

# ---------------------------------------------------------------------------
# Configuration — override via environment variables if desired
# ---------------------------------------------------------------------------
YB_MASTER_ADDRESSES="${YB_MASTER_ADDRESSES:-moqui-database1:7100,moqui-database2:7100,moqui-database3:7100}"
DB_NAME="${DB_NAME:-moqui}"
YB_ADMIN="${YB_ADMIN:-/home/yugabyte/bin/yb-admin}"
YSQLSH="${YSQLSH:-/home/yugabyte/bin/ysqlsh}"
YSQL_HOST="${YSQL_HOST:-moqui-database1}"
YSQL_PORT="${YSQL_PORT:-5433}"
YSQL_USER="${YSQL_USER:-yugabyte}"
# Full path to backup root (use absolute path for crontab compatibility)
BACKUP_PATH="${BACKUP_PATH:-/opt/pgbackups}"
# Seconds to wait for snapshot COMPLETE state (default 15 minutes)
SNAPSHOT_TIMEOUT="${SNAPSHOT_TIMEOUT:-900}"
# Parallel pigz jobs — defaults to all available CPU cores
PIGZ_JOBS="${PIGZ_JOBS:-$(nproc)}"
# Comma-separated table names (lowercase) to EXCLUDE from the main snapshot.
# These are handled by dedicated backup scripts (e.g., yugabyte_paramlog_backup.sh).
EXCLUDED_TABLES="${EXCLUDED_TABLES:-parameter_log}"
# Uncomment and set if TLS is enabled:
# YB_CERTS_DIR="${YB_CERTS_DIR:-/path/to/certs}"

# ---------------------------------------------------------------------------
# Derived values
# ---------------------------------------------------------------------------
KEYSPACE_NAME="ysql.${DB_NAME}"
DATE=$(date +"%Y%m%d")
BACKUP_DIR_NAME="${DB_NAME}-${DATE}"
BACKUP_DIR_PATH="${BACKUP_PATH}/${BACKUP_DIR_NAME}"
BACKUP_FILE="${BACKUP_PATH}/${BACKUP_DIR_NAME}.tar.gz"
SNAPSHOT_ID=""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() { printf '[%s] [yb_backup] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

yb_admin() {
    "$YB_ADMIN" -master_addresses "$YB_MASTER_ADDRESSES" \
        ${YB_CERTS_DIR:+--certs_dir_path "$YB_CERTS_DIR"} "$@"
}

ysql() {
    export PGPASSWORD="${YSQL_PASSWORD:-}"
    "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_USER" -d "$DB_NAME" "$@"
}

cleanup() {
    local exit_code=$?
    if [ $exit_code -ne 0 ]; then
        log "ERROR: script failed (exit $exit_code). Cleaning up..."
        if [ -n "$SNAPSHOT_ID" ]; then
            log "Deleting partial snapshot $SNAPSHOT_ID..."
            yb_admin delete_snapshot "$SNAPSHOT_ID" 2>/dev/null || true
        fi
        [ -d "$BACKUP_DIR_PATH" ] && rm -rf "$BACKUP_DIR_PATH"
    fi
    exit $exit_code
}
trap cleanup EXIT

wait_for_snapshot() {
    local sid="$1"
    local elapsed=0
    local interval=10

    log "Waiting for snapshot $sid to reach COMPLETE state..."
    while true; do
        local state
        state=$(yb_admin list_snapshots 2>/dev/null \
            | awk -v id="$sid" '$1==id {print $2; exit}')

        case "${state:-}" in
            COMPLETE)
                log "Snapshot $sid is COMPLETE."
                return 0
                ;;
            FAILED)
                log "ERROR: Snapshot $sid FAILED."
                return 1
                ;;
        esac

        if [ "$elapsed" -ge "$SNAPSHOT_TIMEOUT" ]; then
            log "ERROR: Snapshot $sid did not complete within ${SNAPSHOT_TIMEOUT}s (state: ${state:-unknown})."
            return 1
        fi

        sleep "$interval"
        elapsed=$((elapsed + interval))
        log "  ...still waiting (${elapsed}s, state: ${state:-unknown})"
    done
}

# Build SQL NOT IN clause from EXCLUDED_TABLES (comma-separated → SQL list)
build_exclusion_sql() {
    # Input:  "parameter_log,some_other"
    # Output: "'parameter_log','some_other'"
    echo "$EXCLUDED_TABLES" | tr ',' '\n' | sed "s/^[[:space:]]*/'/;s/[[:space:]]*$/'/" | paste -sd ','
}

# Returns a space-separated list of table names to snapshot (all public tables
# except those in EXCLUDED_TABLES). Falls back to empty string if no tables yet
# (fresh cluster before Moqui's first run).
get_table_list() {
    local exclusion_sql
    exclusion_sql=$(build_exclusion_sql)
    local tables
    tables=$(ysql -tAc \
        "SELECT tablename FROM pg_tables
          WHERE schemaname = 'public'
            AND tablename NOT IN (${exclusion_sql})
          ORDER BY tablename;" 2>/dev/null || true)
    echo "$tables" | tr '\n' ' '
}

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
mkdir -p "$BACKUP_PATH"
umask 177

# Remove same-day files if re-running
if [ -e "$BACKUP_FILE" ]; then
    log "Removing existing backup file: $BACKUP_FILE"
    rm "$BACKUP_FILE"
fi
if [ -d "$BACKUP_DIR_PATH" ]; then
    log "Removing existing export directory: $BACKUP_DIR_PATH"
    rm -rf "$BACKUP_DIR_PATH"
fi

# ---------------------------------------------------------------------------
# Build table list (excluding PARAMETER_LOG and other excluded tables)
# ---------------------------------------------------------------------------
log "Querying table list (excluding: ${EXCLUDED_TABLES})..."
TABLE_LIST=$(get_table_list)
TABLE_COUNT=$(echo "$TABLE_LIST" | wc -w)

if [ "$TABLE_COUNT" -eq 0 ]; then
    log "WARNING: No tables found in schema 'public' (fresh cluster?). " \
        "Falling back to full keyspace snapshot."
    SNAPSHOT_ARGS=("$KEYSPACE_NAME")
else
    log "Snapshotting ${TABLE_COUNT} tables (${EXCLUDED_TABLES} excluded)."
    # yb-admin syntax: create_snapshot <keyspace> [table1 table2 ...]
    # shellcheck disable=SC2086  # word-split is intentional for TABLE_LIST
    SNAPSHOT_ARGS=("$KEYSPACE_NAME" $TABLE_LIST)
fi

# ---------------------------------------------------------------------------
# Snapshot
# ---------------------------------------------------------------------------
log "Creating snapshot..."
SNAPSHOT_ID=$(yb_admin create_snapshot "${SNAPSHOT_ARGS[@]}" \
    | grep -oP '(?<=Snapshot ID: )[0-9a-f-]+')

if [ -z "$SNAPSHOT_ID" ]; then
    log "ERROR: Failed to parse Snapshot ID from create_snapshot output."
    exit 1
fi
log "Snapshot ID: $SNAPSHOT_ID"

wait_for_snapshot "$SNAPSHOT_ID"

# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------
log "Exporting snapshot $SNAPSHOT_ID → ${BACKUP_DIR_PATH}..."
yb_admin export_snapshot "$SNAPSHOT_ID" "file://${BACKUP_DIR_PATH}"
log "Export complete."

log "Deleting snapshot $SNAPSHOT_ID from cluster..."
yb_admin delete_snapshot "$SNAPSHOT_ID"
SNAPSHOT_ID=""  # clear so the trap doesn't try to delete it again

# ---------------------------------------------------------------------------
# Compression (parallel)
# ---------------------------------------------------------------------------
log "Compressing ${BACKUP_DIR_NAME} → ${BACKUP_FILE} (pigz -p ${PIGZ_JOBS})..."
tar -cf - -C "$BACKUP_PATH" "$BACKUP_DIR_NAME" | pigz -p "$PIGZ_JOBS" > "$BACKUP_FILE"
log "Compression complete. Size: $(du -sh "$BACKUP_FILE" | cut -f1)"

log "Removing temporary export directory ${BACKUP_DIR_PATH}..."
rm -rf "$BACKUP_DIR_PATH"

# ---------------------------------------------------------------------------
# Retention policy
# ---------------------------------------------------------------------------
log "Applying retention policy..."

apply_retention() {
    local pattern="${DB_NAME}-*.tar.gz"
    declare -A by_month by_year
    local -a all_dates=()

    while IFS= read -r f; do
        local fname
        fname=$(basename "$f")
        local ds="${fname#${DB_NAME}-}"
        ds="${ds%.tar.gz}"
        [[ "$ds" =~ ^[0-9]{8}$ ]] || continue
        all_dates+=("$ds")
        local ym="${ds:0:6}"
        local yr="${ds:0:4}"
        if [[ -z "${by_month[$ym]:-}" || "$ds" > "${by_month[$ym]}" ]]; then
            by_month[$ym]="$ds"
        fi
        if [[ -z "${by_year[$yr]:-}" || "$ds" > "${by_year[$yr]}" ]]; then
            by_year[$yr]="$ds"
        fi
    done < <(find "$BACKUP_PATH" -maxdepth 1 -name "$pattern" | sort)

    local today_ts
    today_ts=$(date +%s)
    declare -A keep=()

    # 1. Daily: last 7 days
    for ds in "${all_dates[@]}"; do
        local file_ts
        file_ts=$(date -d "${ds}" +%s 2>/dev/null || date -j -f "%Y%m%d" "${ds}" +%s)
        local age_days=$(( (today_ts - file_ts) / 86400 ))
        [ "$age_days" -le 7 ] && keep[$ds]=1
    done

    # 2. Monthly: most recent per month, up to 6 months back
    local six_months_ago
    six_months_ago=$(date -d "6 months ago" +%Y%m 2>/dev/null || date -v-6m +%Y%m)
    for ym in "${!by_month[@]}"; do
        [[ "$ym" > "$six_months_ago" || "$ym" == "$six_months_ago" ]] && keep[${by_month[$ym]}]=1
    done

    # 3. Yearly: most recent per year, up to 1 year back
    local one_year_ago
    one_year_ago=$(date -d "1 year ago" +%Y 2>/dev/null || date -v-1y +%Y)
    for yr in "${!by_year[@]}"; do
        [[ "$yr" -ge "$one_year_ago" ]] && keep[${by_year[$yr]}]=1
    done

    for ds in "${all_dates[@]}"; do
        if [[ -z "${keep[$ds]:-}" ]]; then
            local target="${BACKUP_PATH}/${DB_NAME}-${ds}.tar.gz"
            log "Deleting expired backup: $(basename "$target")"
            rm -f "$target"
        fi
    done
}

apply_retention
log "Retention policy applied."

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
log "YugabyteDB backup complete: $BACKUP_FILE"
log "NOTE: PARAMETER_LOG is not included — run yugabyte_paramlog_backup.sh separately."

# ---------------------------------------------------------------------------
# Restore reference (commented out)
# ---------------------------------------------------------------------------
# Decompress:
#   pigz -d -c "$BACKUP_FILE" | tar -x -C /tmp
#
# Create target database if needed:
#   "$YSQLSH" -h moqui-database1 -U yugabyte \
#     -c "CREATE DATABASE moqui_restore;" 2>/dev/null || true
#
# Import snapshot (tables that were included in this backup):
#   yb_admin import_snapshot \
#     "file:///tmp/${DB_NAME}-${DATE}" \
#     "${KEYSPACE_NAME}" "ysql.moqui_restore"
#
# Restore PARAMETER_LOG separately from differential backups:
#   See yugabyte_paramlog_backup.sh restore section.
#
# Clean up:
#   rm -rf "/tmp/${DB_NAME}-${DATE}"
