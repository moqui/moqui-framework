#!/usr/bin/env bash
set -euo pipefail

# Helpers
log() { printf '[yb-start] %s %s\n' "$(date -Is)" "$*"; }
error() { printf '[yb-start] ERROR: %s\n' "$*" >&2; exit 1; }

# Superuser credentials (to create roles/db/extensions)
YSQL_HOST="${YSQL_HOST:-moqui-storage-engine1}"
YSQL_PORT="${YSQL_PORT:-5433}"
YSQL_SUPERUSER="${YSQL_SUPERUSER:-yugabyte}"
YSQL_SUPERDB="${YSQL_SUPERDB:-yugabyte}"
# Moqui credentials
MOQUI_USER="${MOQUI_USER:-moqui}"
MOQUI_PASSWORD="${MOQUI_PASSWORD:-moqui}"
MOQUI_DB="${MOQUI_DB:-moqui}"

# Initial tablet count for sharded tables. Start with 1 per node so writes are
# immediately distributed. YugabyteDB auto-splits each tablet at 2GB (configured
# in yb-start.sh), so no need to pre-provision more tablets upfront.
PARAMETER_LOG_TABLETS="${PARAMETER_LOG_TABLETS:-3}"

YSQLSH="/home/yugabyte/bin/ysqlsh"
export PGPASSWORD="${YSQL_SUPERPASS:-}"

pg_ready() {
  for i in $(seq 1 360); do
    if "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$YSQL_SUPERDB" -tAc "SELECT 1" >/dev/null 2>&1; then
      return 0
    fi
    [ $((i % 15)) -eq 0 ] && log "Still waiting YSQL ($i/360)..."
    sleep 2
  done
  log "Timeout waiting YSQL on $YSQL_HOST:$YSQL_PORT"
  return 1
}

# Setup functions

create_role() {
  local has_role
  has_role="$("$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$YSQL_SUPERDB" -tAc \
    "SELECT 1 FROM pg_roles WHERE rolname='${MOQUI_USER}'" || true)"
  if [[ "$has_role" != "1" ]]; then
    log "Create role ${MOQUI_USER}"
    "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$YSQL_SUPERDB" -c \
      "CREATE ROLE ${MOQUI_USER} LOGIN PASSWORD '${MOQUI_PASSWORD}' INHERIT;
       ALTER ROLE ${MOQUI_USER} SET search_path TO public;"
  else
    log "Role ${MOQUI_USER} already defined"
  fi
}

create_database() {
  local has_db
  has_db="$("$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$YSQL_SUPERDB" -tAc \
    "SELECT 1 FROM pg_database WHERE datname='${MOQUI_DB}'" || true)"
  if [[ "$has_db" != "1" ]]; then
    log "Create database ${MOQUI_DB} (colocated) owner ${MOQUI_USER}"
    # COLOCATION = true: all tables share a single tablet group by default.
    # This avoids the per-table tablet limit when hundreds of entities are created.
    # Large tables that need sharding are pre-created below with colocation = false.
    "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$YSQL_SUPERDB" -c \
      "CREATE DATABASE ${MOQUI_DB} OWNER ${MOQUI_USER} ENCODING 'UTF8' COLOCATION = true;"
  else
    log "Database ${MOQUI_DB} already defined"
  fi
}

install_pgvector() {
  log "Installing pgvector extension on ${MOQUI_DB} (schema: extensions)..."
  # Install pgvector in a dedicated 'extensions' schema instead of 'public'.
  # This avoids a name conflict: pgvector creates a type named 'vector' and
  # Moqui has an entity mantle.math.Vector whose table is also named VECTOR.
  # PostgreSQL/YugabyteDB prevents creating a table whose implicit composite
  # type name clashes with an existing type in the same schema.
  # By isolating pgvector in 'extensions', both can coexist.
  "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$MOQUI_DB" -c \
    "CREATE SCHEMA IF NOT EXISTS extensions;
     GRANT USAGE ON SCHEMA extensions TO ${MOQUI_USER};
     CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;"
  # Add 'extensions' to the moqui role's search_path so vector operations
  # work transparently without schema-qualifying every call.
  "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$MOQUI_DB" -c \
    "ALTER ROLE ${MOQUI_USER} SET search_path TO public, extensions;"
  log "pgvector extension installed in schema 'extensions'."
}

# Pre-create tables that must NOT be colocated (large tables that need sharding).
# Moqui uses CREATE TABLE IF NOT EXISTS, so if the table already exists it is kept as-is.
# colocation = false opts this table out of the shared tablet group.
# SPLIT INTO n TABLETS controls how many tablets (shards) are created; each is replicated RF times.
create_sharded_tables() {
  log "Pre-creating sharded tables (colocation=false) in ${MOQUI_DB}..."

  # PARAMETER_LOG: high-volume append-only log table — needs parallel writes across tablets.
  local has_table
  has_table="$("$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$MOQUI_DB" -tAc \
    "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relname='parameter_log'" || true)"

  if [[ "$has_table" != "1" ]]; then
    log "Creating PARAMETER_LOG with ${PARAMETER_LOG_TABLETS} tablets (colocation=false)"
    # Schema from mantle-udm/entity/MathEntities.xml — entity mantle.math.ParameterLog
    # Type mapping: id→VARCHAR(40), number-integer→NUMERIC(20,0), date-time→TIMESTAMP,
    #               number-decimal→NUMERIC(26,6), text-short→VARCHAR(63)
    "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$YSQL_SUPERUSER" -d "$MOQUI_DB" -c \
      "CREATE TABLE public.PARAMETER_LOG (
         PARAMETER_LOG_ID VARCHAR(40) NOT NULL,
         PARAMETER_ID VARCHAR(40) NOT NULL,
         SEQUENCE_NUM NUMERIC(20,0),
         OBSERVED_DATE TIMESTAMP,
         NUMERIC_VALUE NUMERIC(26,6),
         SYMBOLIC_VALUE VARCHAR(63),
         PARAMETER_ENUM_ID VARCHAR(40),
         LAST_UPDATED_STAMP TIMESTAMP,
         CONSTRAINT PK_PARAMETER_LOG PRIMARY KEY (PARAMETER_LOG_ID)
       ) WITH (colocation = false) SPLIT INTO ${PARAMETER_LOG_TABLETS} TABLETS;

       CREATE UNIQUE INDEX PLOG_TS ON public.PARAMETER_LOG (PARAMETER_ID, OBSERVED_DATE);
       CREATE UNIQUE INDEX PLOG_SEQ ON public.PARAMETER_LOG (PARAMETER_ID, SEQUENCE_NUM);

       ALTER TABLE public.PARAMETER_LOG OWNER TO ${MOQUI_USER};"
    log "PARAMETER_LOG created with indexes."
  else
    log "PARAMETER_LOG already exists, skipping."
  fi

  # Add further large/sharded tables here following the same pattern.
}

# Smoke Tests

smoke_test() {
  log "Smoke test with user ${MOQUI_USER} on database ${MOQUI_DB}"
  "$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$MOQUI_USER" -d "$MOQUI_DB" -tAc \
    "SELECT version(), current_user, current_database();" || {
      log "Smoke test failed"; exit 1; }
  log "OK."
}

smoke_test_pgvector() {
  log "Smoke test: verifying pgvector visibility for ${MOQUI_USER}..."
  local has_vector
  has_vector="$("$YSQLSH" -h "$YSQL_HOST" -p "$YSQL_PORT" -U "$MOQUI_USER" -d "$MOQUI_DB" -tAc \
    "SELECT 1 FROM pg_extension WHERE extname = 'vector'" || true)"

  if [[ "$has_vector" != "1" ]]; then
    log "Smoke test FAILED: User ${MOQUI_USER} cannot see 'vector' extension."
    exit 1
  fi
  log "pgvector check successful."
}

# Main

log "Waiting YSQL on ${YSQL_HOST}:${YSQL_PORT}..."
pg_ready

log "Database setup"
create_role
create_database

log "Extension setup"
install_pgvector

log "Sharded table setup"
create_sharded_tables

log "Smoke tests"
smoke_test
smoke_test_pgvector

log "Bootstrap complete"
