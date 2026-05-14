#!/usr/bin/env bash
set -euo pipefail

# Helpers
log() { printf '[yb-start] %s %s\n' "$(date -Is)" "$*"; }
error() { printf '[yb-start] ERROR: %s\n' "$*" >&2; exit 1; }

BASE_DIR="${YB_BASE_DIR:-/mnt/disk0/yb-data}"
JOIN_HOST="${YB_JOIN:-}"

# Optional: If you want to use YSQL Connection Manager for connection pooling
ENABLE_CONN_MGR="${YB_ENABLE_YSQL_CONN_MGR:-false}"
CONN_MGR_PORT="${YB_YSQL_CONN_MGR_PORT:-6433}"

IP="$(hostname -i | awk '{print $1}')"
[[ -n "$IP" ]] || error "could not determine IP via hostname -i"

log "IP=$IP BASE_DIR=$BASE_DIR JOIN_HOST=${JOIN_HOST:-<none>}"
log "ENABLE_CONN_MGR=$ENABLE_CONN_MGR CONN_MGR_PORT=$CONN_MGR_PORT"

wait_port() {
  local host="$1" port="$2"
  for _ in $(seq 1 180); do
    if (echo >/dev/tcp/"$host"/"$port") >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

if [[ -n "$JOIN_HOST" ]]; then
  log "Waiting join target $JOIN_HOST:7100..."
  wait_port "$JOIN_HOST" 7100 || error "Timeout waiting $JOIN_HOST:7100"
fi

# Defining extra flags to force binding to 0.0.0.0 (for external UI and SQL query)
EXTRA_FLAGS="rpc_bind_addresses=0.0.0.0,webserver_interface=0.0.0.0"
# Auto-split: each tablet is split when it reaches 2GB.
# Keeps write hotspots small and scales transparently as data grows.
TSERVER_EXTRAS="${EXTRA_FLAGS},pgsql_proxy_bind_address=0.0.0.0,enable_automatic_tablet_splitting=true,tablet_split_size_threshold_bytes=2147483648"

ARGS=(start
  "--base_dir=$BASE_DIR"
  "--advertise_address=$IP"
  "--master_flags=$EXTRA_FLAGS"
  "--tserver_flags=$TSERVER_EXTRAS"
)

if [[ -n "$JOIN_HOST" ]]; then
  ARGS+=("--join=$JOIN_HOST")
fi

# YSQL Connection Manager
if [[ "$ENABLE_CONN_MGR" == "true" ]]; then
  ARGS+=("--enable_ysql_conn_mgr=true" "--ysql_conn_mgr_port=$CONN_MGR_PORT")
fi

log "Exec: /home/yugabyte/bin/yugabyted ${ARGS[*]}"
/home/yugabyte/bin/yugabyted "${ARGS[@]}"

trap 'log "Stopping..."; /home/yugabyte/bin/yugabyted stop || true; exit 0' TERM INT
log "Started; entering sleep infinity"
sleep infinity
