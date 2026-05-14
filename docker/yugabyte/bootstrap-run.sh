#!/usr/bin/env bash
set -euo pipefail

# Helpers
log() { printf '[bootstrap-run] %s %s\n' "$(date -Is)" "$*"; }
error() { printf '[bootstrap-run] ERROR: %s\n' "$*" >&2; exit 1; }

export YSQL_SUPERUSER="$(cat /run/secrets/yb_superuser)"
export YSQL_SUPERDB="$(cat /run/secrets/yb_superdb)"
export MOQUI_USER="$(cat /run/secrets/moqui_user)"
export MOQUI_PASSWORD="$(cat /run/secrets/moqui_password)"

log "Waiting for master leader on ${YSQL_HOST:-yb-node1}:7000 ..."
for _ in $(seq 1 300); do
  if curl -fsS "http://${YSQL_HOST:-yb-node1}:7000/api/v1/masters" | grep -q '"role":"LEADER"'; then
    break
  fi
  sleep 2
done

log "Running bootstrap.sh ..."
exec /bootstrap/bootstrap.sh
