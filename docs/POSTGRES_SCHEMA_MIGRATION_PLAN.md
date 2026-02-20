# PostgreSQL Schema Migration Plan

**Objective**: Migrate Moqui from `moqui.public` schema to `fivex.moqui` schema

**Created**: 2025-12-07
**Status**: Draft - Pending Review

---

## Executive Summary

This plan outlines the migration of the Moqui Framework database configuration from:
- **Current**: Database `moqui`, Schema `public`
- **Target**: Database `fivex`, Schema `moqui`

This change aligns with the FiveX monorepo database naming conventions and provides better namespace isolation for multi-application deployments.

---

## Current State Analysis

### Database Configuration
| Setting | Current Value | Target Value |
|---------|---------------|--------------|
| Database | `moqui` | `fivex` |
| Schema | `public` | `moqui` |
| User | `moqui` | `moqui` (unchanged) |
| Password | `moqui` | `moqui` (unchanged) |
| Host | `127.0.0.1` / `postgres` | unchanged |
| Port | `5432` | unchanged |

### Existing Databases
```
fivex           - Already exists (target database)
moqui           - Current Moqui database with tables in public schema
```

### Files to Modify
1. `framework/src/main/resources/MoquiDefaultConf.xml` - Default configuration
2. `runtime/conf/MoquiDevConf.xml` - Development configuration
3. `runtime/conf/MoquiProductionConf.xml` - Production configuration
4. `docker/conf/MoquiDockerConf.xml` - Docker configuration
5. `docker/.env.example` - Docker environment template
6. `docker-compose.yml` - Docker Compose services

---

## Implementation Plan

### Phase 1: Database Preparation

#### Task 1.1: Create Schema in fivex Database
```sql
-- Connect to fivex database
\c fivex

-- Create moqui schema
CREATE SCHEMA IF NOT EXISTS moqui AUTHORIZATION moqui;

-- Grant permissions
GRANT ALL PRIVILEGES ON SCHEMA moqui TO moqui;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA moqui TO moqui;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA moqui TO moqui;
ALTER DEFAULT PRIVILEGES IN SCHEMA moqui GRANT ALL ON TABLES TO moqui;
ALTER DEFAULT PRIVILEGES IN SCHEMA moqui GRANT ALL ON SEQUENCES TO moqui;

-- Set search path for moqui user (optional, helps with unqualified table names)
ALTER USER moqui SET search_path TO moqui, public;
```

#### Task 1.2: Verify Schema Creation
```sql
\c fivex
\dn
-- Should show: moqui | moqui
```

---

### Phase 2: Configuration Updates

#### Task 2.1: Update MoquiDefaultConf.xml

**File**: `framework/src/main/resources/MoquiDefaultConf.xml`

**Changes**:
```xml
<!-- Line ~48: Update default database name -->
<default-property name="entity_ds_database" value="fivex"/>

<!-- Line ~50: Update default schema -->
<default-property name="entity_ds_schema" value="moqui"/>
```

**Full datasource section** (around line 477):
```xml
<datasource group-name="transactional" database-conf-name="${entity_ds_db_conf}"
            schema-name="${entity_ds_schema}"
            startup-add-missing="${entity_add_missing_startup}"
            runtime-add-missing="${entity_add_missing_runtime}">
</datasource>
```

#### Task 2.2: Update MoquiDevConf.xml

**File**: `runtime/conf/MoquiDevConf.xml`

**Changes**:
```xml
<!-- Add/update these properties -->
<default-property name="entity_ds_database" value="fivex"/>
<default-property name="entity_ds_schema" value="moqui"/>

<!-- Update datasource -->
<entity-facade query-stats="true">
    <datasource group-name="transactional" database-conf-name="postgres" schema-name="moqui"
                startup-add-missing="true" runtime-add-missing="false">
        <inline-jdbc jdbc-uri="jdbc:postgresql://127.0.0.1/fivex"
                     jdbc-username="moqui" jdbc-password="moqui"/>
    </datasource>
</entity-facade>
```

#### Task 2.3: Update MoquiProductionConf.xml

**File**: `runtime/conf/MoquiProductionConf.xml`

**Changes**: Same pattern as MoquiDevConf.xml with production-specific settings.

#### Task 2.4: Update MoquiDockerConf.xml

**File**: `docker/conf/MoquiDockerConf.xml`

**Changes**:
```xml
<!-- Line ~20-21 -->
<default-property name="entity_ds_database" value="${DB_NAME:-fivex}"/>
<default-property name="entity_ds_schema" value="${DB_SCHEMA:-moqui}"/>

<!-- Line ~75: Update datasource -->
<datasource group-name="transactional" database-conf-name="postgres" schema-name="${entity_ds_schema}"
            startup-add-missing="true" runtime-add-missing="false">
    <inline-jdbc jdbc-uri="jdbc:postgresql://${entity_ds_host}:${entity_ds_port}/${entity_ds_database}"
                 jdbc-username="${entity_ds_user}" jdbc-password="${entity_ds_password}"/>
</datasource>
```

#### Task 2.5: Update Docker Environment

**File**: `docker/.env.example`

**Changes**:
```bash
# Database Configuration (PostgreSQL)
DB_HOST=postgres
DB_PORT=5432
DB_NAME=fivex          # Changed from moqui
DB_SCHEMA=moqui        # Changed from public
DB_USER=moqui
DB_PASSWORD=moqui
```

#### Task 2.6: Update docker-compose.yml

**File**: `docker-compose.yml`

**Changes**:
```yaml
services:
  moqui:
    environment:
      - DB_NAME=fivex          # Changed from moqui
      - DB_SCHEMA=moqui        # Added

  postgres:
    environment:
      POSTGRES_DB: fivex       # Changed from moqui (for new deployments)
```

**Note**: For Docker, we may want to keep creating `moqui` database for backwards compatibility or add init scripts to create both databases.

---

### Phase 3: Data Migration (Optional)

If migrating existing data from `moqui.public` to `fivex.moqui`:

#### Task 3.1: Export Data
```bash
# Export all tables from moqui.public
pg_dump -h localhost -U moqui -d moqui -n public \
  --no-owner --no-privileges \
  -f moqui_public_backup.sql
```

#### Task 3.2: Transform Schema References
```bash
# Replace public schema with moqui schema in dump
sed -i 's/public\./moqui./g' moqui_public_backup.sql
sed -i 's/SET search_path = public/SET search_path = moqui/g' moqui_public_backup.sql
```

#### Task 3.3: Import to New Location
```bash
# Import into fivex.moqui
PGPASSWORD=moqui psql -h localhost -U moqui -d fivex -f moqui_public_backup.sql
```

#### Task 3.4: Verify Migration
```sql
\c fivex
SET search_path TO moqui;
\dt
-- Should list all Moqui tables
SELECT count(*) FROM moqui.moqui_entity_definition;
```

---

### Phase 4: PostgreSQL Connection String Updates

#### JDBC URL Format
```
# Current
jdbc:postgresql://127.0.0.1/moqui

# New (schema is set via schema-name attribute, not in URL)
jdbc:postgresql://127.0.0.1/fivex
```

#### Search Path Configuration
The `schema-name` attribute in Moqui configuration handles schema qualification. However, for tools and direct connections, set:

```sql
-- For the moqui user, set default search path
ALTER USER moqui SET search_path TO moqui, public;
```

Or in JDBC URL (alternative approach):
```
jdbc:postgresql://127.0.0.1/fivex?currentSchema=moqui
```

---

### Phase 5: Testing

#### Task 5.1: Unit Tests
```bash
# Run framework tests with new configuration
./gradlew framework:test
```

#### Task 5.2: Integration Tests
```bash
# Clean start with new schema
./gradlew cleanDb
./gradlew load -Ptypes=seed
./gradlew run
```

#### Task 5.3: Verification Queries
```sql
-- Connect to fivex database
\c fivex

-- Check tables exist in moqui schema
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'moqui'
ORDER BY table_name
LIMIT 10;

-- Verify no tables in public schema (for fivex db)
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_catalog = 'fivex';

-- Check record counts
SELECT count(*) FROM moqui.moqui_entity_definition;
SELECT count(*) FROM moqui.user_account;
```

#### Task 5.4: Docker Testing
```bash
# Test Docker deployment
docker-compose down -v
docker-compose up -d
# Wait for startup, then verify
docker-compose logs -f moqui
```

---

## Rollback Plan

If issues are encountered:

### Quick Rollback
1. Revert configuration files to previous commit
2. Restart application with old database

### Data Rollback
```bash
# If data was migrated, keep old database intact
# Simply point configuration back to moqui.public
```

---

## File Change Summary

| File | Change Type | Priority |
|------|-------------|----------|
| `framework/src/main/resources/MoquiDefaultConf.xml` | Modify | High |
| `runtime/conf/MoquiDevConf.xml` | Modify | High |
| `runtime/conf/MoquiProductionConf.xml` | Modify | Medium |
| `docker/conf/MoquiDockerConf.xml` | Modify | High |
| `docker/.env.example` | Modify | Medium |
| `docker-compose.yml` | Modify | Medium |
| `docker/postgres/init/01-create-schema.sql` | Create | High |

---

## New File: Docker PostgreSQL Init Script

**File**: `docker/postgres/init/01-create-schema.sql`

```sql
-- Create fivex database if not exists (handled by POSTGRES_DB env var)
-- Create moqui schema
CREATE SCHEMA IF NOT EXISTS moqui;

-- Grant permissions to moqui user
GRANT ALL PRIVILEGES ON SCHEMA moqui TO moqui;
ALTER DEFAULT PRIVILEGES IN SCHEMA moqui GRANT ALL ON TABLES TO moqui;
ALTER DEFAULT PRIVILEGES IN SCHEMA moqui GRANT ALL ON SEQUENCES TO moqui;

-- Set default search path
ALTER USER moqui SET search_path TO moqui, public;
```

---

## Environment Variable Reference

| Variable | Old Default | New Default | Description |
|----------|-------------|-------------|-------------|
| `DB_NAME` | `moqui` | `fivex` | PostgreSQL database name |
| `DB_SCHEMA` | `public` | `moqui` | PostgreSQL schema name |
| `entity_ds_database` | `moqui` | `fivex` | Moqui property |
| `entity_ds_schema` | `""` (empty/public) | `moqui` | Moqui property |

---

## Implementation Checklist

- [ ] **Phase 1: Database Preparation**
  - [ ] Create `moqui` schema in `fivex` database
  - [ ] Grant permissions to `moqui` user
  - [ ] Verify schema creation

- [ ] **Phase 2: Configuration Updates**
  - [ ] Update `MoquiDefaultConf.xml`
  - [ ] Update `MoquiDevConf.xml`
  - [ ] Update `MoquiProductionConf.xml`
  - [ ] Update `MoquiDockerConf.xml`
  - [ ] Update `docker/.env.example`
  - [ ] Update `docker-compose.yml`
  - [ ] Create PostgreSQL init script

- [ ] **Phase 3: Data Migration** (if applicable)
  - [ ] Backup existing data
  - [ ] Transform and import to new schema
  - [ ] Verify data integrity

- [ ] **Phase 4: Testing**
  - [ ] Run unit tests
  - [ ] Run integration tests
  - [ ] Test Docker deployment
  - [ ] Verify application functionality

- [ ] **Phase 5: Documentation**
  - [ ] Update CLAUDE.md
  - [ ] Update README if needed
  - [ ] Create PR with change summary

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Data loss during migration | High | Backup before migration, keep old database |
| Application fails to start | High | Test in dev environment first |
| Docker deployment broken | Medium | Test docker-compose separately |
| Third-party tools break | Low | Document new connection strings |

---

## Timeline Estimate

| Phase | Estimated Time |
|-------|----------------|
| Phase 1: Database Prep | 15 minutes |
| Phase 2: Config Updates | 30 minutes |
| Phase 3: Data Migration | 30 minutes (if needed) |
| Phase 4: Testing | 1 hour |
| Phase 5: Documentation | 15 minutes |
| **Total** | **~2.5 hours** |

---

## Approval

- [ ] Technical Review
- [ ] Database Admin Review (if applicable)
- [ ] Ready for Implementation
