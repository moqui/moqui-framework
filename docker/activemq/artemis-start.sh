#!/usr/bin/env bash
set -euo pipefail

# Helpers
log() { printf '[artemis-start] %s %s\n' "$(date -Is)" "$*"; }
error() { printf '[artemis-start] ERROR: %s\n' "$*" >&2; exit 1; }

INSTANCE_DIR="${ARTEMIS_INSTANCE_DIR:-/var/lib/artemis-instance}"
DATA_DIR="${ARTEMIS_DATA_DIR:-/var/lib/artemis/data}"

BROKER_XML_SRC="${BROKER_XML_SRC:-/var/lib/broker-custom.xml}"

# Resolve "value or docker secret file path"
resolve_secret_or_value() {
  local v="${1:-}"
  if [[ "$v" == /run/secrets/* ]] && [[ -f "$v" ]]; then
    cat "$v"
  else
    printf '%s' "$v"
  fi
}

ARTEMIS_USER="$(resolve_secret_or_value "${ARTEMIS_USER:-${ARTEMIS_User:-}}")"
ARTEMIS_PASSWORD="$(resolve_secret_or_value "${ARTEMIS_PASSWORD:-${ARTEMIS_Password:-}}")"

CLUSTER_USER="$(resolve_secret_or_value "${ARTEMIS_CLUSTER_USER:-${ARTEMIS_ClUSTER_USER:-}}")"
CLUSTER_PASSWORD="$(resolve_secret_or_value "${ARTEMIS_CLUSTER_PASSWORD:-${ARTEMIS_ClUSTER_PASSWORD:-}}")"

ARTEMIS_INSTANCE_NAME="${ARTEMIS_INSTANCE:-${HOSTNAME:-artemis}}"

# Defaults
: "${ARTEMIS_USER:=artemis}"
: "${ARTEMIS_PASSWORD:=artemis}"
: "${CLUSTER_USER:=cluster}"
: "${CLUSTER_PASSWORD:=cluster}"
: "${ARTEMIS_EXPIRY_DELAY_MS:=172800000}" # 2 days

ROLE="${ARTEMIS_ROLE:-}"
if [[ -z "$ROLE" ]]; then
  if [[ "${HOSTNAME:-}" == *"broker1"* ]] || [[ "$ARTEMIS_INSTANCE_NAME" == *"broker1"* ]]; then
    ROLE="primary"
  else
    ROLE="backup"
  fi
fi

# Peer naming is fixed by your compose/stack service names
BROKER1_HOST="${ARTEMIS_BROKER1_HOST:-moqui-broker1}"
BROKER2_HOST="${ARTEMIS_BROKER2_HOST:-moqui-broker2}"

THIS_HOST="${ARTEMIS_CONNECTOR_HOST:-${HOSTNAME:-$ARTEMIS_INSTANCE_NAME}}"

log "INSTANCE_DIR=$INSTANCE_DIR DATA_DIR=$DATA_DIR INSTANCE=$ARTEMIS_INSTANCE_NAME HOST=$THIS_HOST ROLE=$ROLE"
log "CLUSTER peer hosts: $BROKER1_HOST , $BROKER2_HOST"

# Create instance if missing
if [[ ! -x "$INSTANCE_DIR/bin/artemis" ]]; then
  log "Creating Artemis instance at $INSTANCE_DIR"
  mkdir -p "$INSTANCE_DIR"
  # ARTEMIS_HOME is set in the official image; fallback to common paths
  ARTEMIS_CMD="${ARTEMIS_HOME:-}/bin/artemis"
  if [[ -z "${ARTEMIS_HOME:-}" ]]; then
    if [[ -x "/opt/activemq-artemis/bin/artemis" ]]; then
      ARTEMIS_CMD="/opt/activemq-artemis/bin/artemis"
    elif [[ -x "/var/lib/artemis-instance/bin/artemis" ]]; then
      ARTEMIS_CMD="/var/lib/artemis-instance/bin/artemis"
    elif command -v artemis >/dev/null 2>&1; then
      ARTEMIS_CMD="$(command -v artemis)"
    else
      log "ERROR: cannot find 'artemis' command in image"
      exit 2
    fi
  fi

  "$ARTEMIS_CMD" create "$INSTANCE_DIR" \
    --silent \
    --user "$ARTEMIS_USER" \
    --password "$ARTEMIS_PASSWORD" \
    --require-login \
    --http-host 0.0.0.0 \
    --relax-jolokia
fi

mkdir -p "$DATA_DIR"
mkdir -p \
  "$DATA_DIR/journal" \
  "$DATA_DIR/bindings" \
  "$DATA_DIR/paging" \
  "$DATA_DIR/large-messages"

# Build HA policy snippet + cluster static connectors
tmp_ha="$(mktemp)"
tmp_sc="$(mktemp)"

if [[ "$ROLE" == "primary" ]]; then
  cat >"$tmp_ha" <<EOF
    <ha-policy>
      <replication>
        <primary>
          <group-name>moqui-ha</group-name>
          <cluster-name>artemis-cluster</cluster-name>
          <initial-replication-sync-timeout>60000</initial-replication-sync-timeout>
        </primary>
      </replication>
    </ha-policy>
EOF
  cat >"$tmp_sc" <<EOF
        <connector-ref>broker2-connector</connector-ref>
EOF
else
  cat >"$tmp_ha" <<EOF
    <ha-policy>
      <replication>
        <backup>
          <group-name>moqui-ha</group-name>
          <cluster-name>artemis-cluster</cluster-name>
          <allow-failback>true</allow-failback>
          <restart-backup>true</restart-backup>
          <initial-replication-sync-timeout>60000</initial-replication-sync-timeout>
          <max-saved-replicated-journals-size>2</max-saved-replicated-journals-size>
        </backup>
      </replication>
    </ha-policy>
EOF
  cat >"$tmp_sc" <<EOF
        <connector-ref>broker1-connector</connector-ref>
EOF
fi

# Render broker.xml from template
if [[ ! -f "$BROKER_XML_SRC" ]]; then
  log "ERROR: broker template not found at $BROKER_XML_SRC"
  exit 3
fi

tmp_xml="$(mktemp)"

sed \
  -e "s|@@BROKER_NAME@@|$ARTEMIS_INSTANCE_NAME|g" \
  -e "s|@@DATA_DIR@@|$DATA_DIR|g" \
  -e "s|@@THIS_HOST@@|$THIS_HOST|g" \
  -e "s|@@BROKER1_HOST@@|$BROKER1_HOST|g" \
  -e "s|@@BROKER2_HOST@@|$BROKER2_HOST|g" \
  -e "s|@@CLUSTER_USER@@|$CLUSTER_USER|g" \
  -e "s|@@CLUSTER_PASSWORD@@|$CLUSTER_PASSWORD|g" \
  -e "s|@@EXPIRY_DELAY_MS@@|$ARTEMIS_EXPIRY_DELAY_MS|g" \
  "$BROKER_XML_SRC" > "$tmp_xml"

sed \
  -e "/@@HA_POLICY@@/r $tmp_ha" \
  -e "/@@HA_POLICY@@/d" \
  -e "/@@CLUSTER_STATIC_CONNECTORS@@/r $tmp_sc" \
  -e "/@@CLUSTER_STATIC_CONNECTORS@@/d" \
  "$tmp_xml" > "$INSTANCE_DIR/etc/broker.xml"

rm -f "$tmp_xml" "$tmp_ha" "$tmp_sc"

log "Starting broker..."
exec "$INSTANCE_DIR/bin/artemis" run
