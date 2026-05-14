#!/bin/bash
set -euo pipefail

# Differential backup for the PARAMETER_LOG table (high-volume time-series data).
#
# Strategy: on each run, export only the rows written since the last run using
# LAST_UPDATED_STAMP as a watermark. This produces small, fast incremental files
# that complement the full snapshot produced by yugabyte_backup.sh.
#
# Watermark file: $BACKUP_PATH/paramlog_watermark
#   - Contains the ISO-8601 timestamp of the upper bound of the last export.
#   - If absent (first run), all existing rows are exported.
#
# Output files: paramlog-YYYYMMDD-HHMMSS.csv.gz
#   - CSV with header row, compressed with pigz.
#   - Empty exports (no new rows) produce an empty .csv.gz and are still kept
#     so the watermark advances.
#
# Retention: keep last RETAIN_DAYS days of differential files (default 30).
#
# Example crontab (every 4 hours): 0 */4 * * * /opt/moqui/yugabyte_paramlog_backup.sh
#
# Restore procedure:
#   1. Restore the main snapshot (yugabyte_backup.sh) to re-create all other tables.
#   2. Re-create PARAMETER_LOG with colocation=false (see bootstrap.sh).
#   3. Replay differential files in chronological order:
#        for f in $(ls "$BACKUP_PATH"/paramlog-*.csv.gz | sort); do
#            pigz -d -c "$f" | tail -n +2 | \  # strip header from 2nd+ file
#            ysqlsh -h moqui-database1 -U moqui -d moqui \
#              -c "\COPY PARAMETER_LOG FROM STDIN CSV HEADER"
#        done
#      (For the first file, keep the header; strip it for subsequent files.)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
YSQLSH="${YSQLSH:-/home/yugabyte/bin/ysqlsh}"
YSQL_HOST="${YSQL_HOST:-moqui-database1}"
YSQL_PORT="${YSQL_PORT:-5433}"
YSQL_USER="${YSQL_USER:-moqui}"         # use the app user — no superuser needed
DB_NAME="${DB_NAME:-moqui}"
TABLE_NAME="PARAMETER_LOG"
WATERMARK_COL="LAST_UPDATED_STAMP"      # column used to detect new rows
BACKUP_PATH="${BACKUP_PATH:-/opt/pgbackups/paramlog}"
PIGZ_JOBS="${PIGZ_JOBS:-$(nproc)}"
# How many days of differential files to retain
RETAIN_DAYS="${RETAIN_DAYS:-30}"
# Uncomment if the moqui DB user requires a password:
# export PGPASSWORD="${YSQL_PASSWORD:-}"

# ---------------------------------------------------------------------------
# Derived values
# ---------------------------------------------------------------------------
WATERMARK_FILE="${BACKUP_PATH}/paramlog_watermark"
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BACKUP_FILE="${BACKUP_PATH}/paramlog-${TIMESTAMP}.csv.gz"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() { printf '[%s] [paramlog_backup] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

ysql() {
    "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_USER" -d "$DB_NAME" "$@"
}

cleanup() {
    local exit_code=$?
    if [ $exit_code -ne 0 ]; then
        log "ERROR: script failed (exit $exit_code). Removing partial file..."
        rm -f "$BACKUP_FILE"
    fi
    exit $exit_code
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
mkdir -p "$BACKUP_PATH"
umask 177

# Read watermark (default: epoch → export all rows)
if [ -f "$WATERMARK_FILE" ]; then
    LAST_TS=$(cat "$WATERMARK_FILE")
    log "Watermark: ${LAST_TS} (exporting rows with ${WATERMARK_COL} > this value)"
else
    LAST_TS="1970-01-01 00:00:00"
    log "No watermark found — exporting all rows (first run)."
fi

# Record the upper bound BEFORE starting the export to avoid race conditions.
# Rows inserted/updated after this point will be captured in the next run.
UPPER_TS=$(ysql -tAc "SELECT to_char(NOW(), 'YYYY-MM-DD HH24:MI:SS.US')")
log "Upper bound: ${UPPER_TS}"

# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------
log "Exporting ${TABLE_NAME} rows where ${WATERMARK_COL} > '${LAST_TS}'..."

ROW_COUNT=$(ysql -tAc \
    "SELECT COUNT(*) FROM public.${TABLE_NAME}
      WHERE ${WATERMARK_COL} > '${LAST_TS}'
        AND ${WATERMARK_COL} <= '${UPPER_TS}';" | tr -d '[:space:]')

log "Rows to export: ${ROW_COUNT}"

# Export as CSV and compress on the fly
ysql -c "\COPY (
    SELECT * FROM public.${TABLE_NAME}
     WHERE ${WATERMARK_COL} > '${LAST_TS}'
       AND ${WATERMARK_COL} <= '${UPPER_TS}'
     ORDER BY ${WATERMARK_COL}
) TO STDOUT CSV HEADER" \
    | pigz -p "$PIGZ_JOBS" > "$BACKUP_FILE"

BACKUP_SIZE=$(du -sh "$BACKUP_FILE" | cut -f1)
log "Export complete. File: $(basename "$BACKUP_FILE") (${BACKUP_SIZE}, ${ROW_COUNT} rows)"

# ---------------------------------------------------------------------------
# Advance watermark
# ---------------------------------------------------------------------------
echo "$UPPER_TS" > "$WATERMARK_FILE"
log "Watermark updated to: ${UPPER_TS}"

# ---------------------------------------------------------------------------
# Retention — keep last RETAIN_DAYS days of differential files
# ---------------------------------------------------------------------------
log "Applying retention policy (keep last ${RETAIN_DAYS} days)..."

find "$BACKUP_PATH" -maxdepth 1 -name "paramlog-*.csv.gz" \
    -mtime +"${RETAIN_DAYS}" -print | while IFS= read -r old_file; do
    log "Deleting expired file: $(basename "$old_file")"
    rm -f "$old_file"
done

log "ParameterLog differential backup complete: $BACKUP_FILE"
