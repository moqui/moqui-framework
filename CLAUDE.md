# CLAUDE.md — Moqui Framework Project Instructions

## Project Overview

This is a **Moqui Framework** ERP project with a **Flutter view layer** (moqui-flutter) and a **PostgreSQL-only search backend**. Moqui is a Java-based enterprise application framework using XML-defined entities, services, screens, and forms with Groovy scripting. The business logic layer is **Mantle** (mantle-udm for data model, mantle-usl for services), the admin UI library is **SimpleScreens**, and the full ERP application is **MarbleERP**.

The **moqui-flutter** component adds a Flutter web/mobile client that consumes a custom JSON render mode (`fjson`) produced by `ScreenWidgetRenderJson.groovy`. The **PostgreSQL search backend** replaces ElasticSearch/OpenSearch with native PostgreSQL JSONB + tsvector search.

## Required Reading Before Any Code Changes

**Always read the relevant skill files before writing or modifying any Moqui artifact:**

| Task | Read First |
|------|-----------|
| Entity work (create, extend, view-entity, ECA) | `runtime/component/moqui-ai-skill/SKILL.md` → `runtime/component/moqui-ai-skill/references/ENTITIES.md` |
| Service work (create, XML Actions, SECA, REST) | `runtime/component/moqui-ai-skill/SKILL.md` → `runtime/component/moqui-ai-skill/references/SERVICES.md` |
| Screen/form work (XML Screens, transitions, widgets) | `runtime/component/moqui-ai-skill/SKILL.md` → `runtime/component/moqui-ai-skill/references/SCREENS.md` |
| Business logic (orders, shipments, invoices, parties) | `runtime/component/moqui-ai-skill/references/MANTLE.md` |
| MarbleERP customization (modules, extending, dashboards) | `runtime/component/moqui-ai-skill/references/MARBLE_ERP.md` |
| Spock tests (entity, service, screen, REST, flows) | `runtime/component/moqui-ai-skill/references/TESTING.md` |
| Flutter view layer (Dart, widget rendering, API) | `runtime/component/moqui-flutter/IMPLEMENTATION_STATUS.md` |
| PostgreSQL search backend | `POSTGRES_SEARCH_PLAN.md` |
| Any Moqui task (start here) | `runtime/component/moqui-ai-skill/SKILL.md` |

## Project Structure

```
moqui-postgreonly/                # Framework root (moqui-framework fork)
├── runtime/
│   ├── conf/                     # Environment-specific MoquiConf.xml files
│   │   ├── MoquiDevConf.xml      # Dev config (postgres, elastic-facade)
│   │   └── MoquiProductionConf.xml
│   ├── component/                # All components live here
│   │   ├── mantle-udm/           # Data model entities (DO NOT MODIFY)
│   │   ├── mantle-usl/           # Business logic services (DO NOT MODIFY)
│   │   ├── SimpleScreens/        # Admin screen library (DO NOT MODIFY)
│   │   ├── MarbleERP/            # ERP application (DO NOT MODIFY)
│   │   ├── moqui-fop/            # PDF/FOP support (DO NOT MODIFY)
│   │   └── moqui-flutter/        # ← CUSTOM: Flutter view layer component
│   │       ├── component.xml
│   │       ├── MoquiConf.xml     # Screen mounting (fapps, fjson render mode)
│   │       ├── data/             # Seed data
│   │       ├── screen/           # fapps.xml root screen
│   │       ├── src/              # ScreenWidgetRenderJson.groovy
│   │       └── flutter/          # Dart/Flutter app
│   │           ├── lib/          # Flutter source code
│   │           ├── test/         # Flutter tests + e2e tests
│   │           └── pubspec.yaml
│   └── log/
├── framework/                    # Moqui framework source (MODIFIED for postgres search)
│   ├── entity/SearchEntities.xml
│   ├── src/main/groovy/org/moqui/impl/context/
│   │   ├── ElasticFacadeImpl.groovy  # Modified to support postgres type
│   │   ├── PostgresElasticClient.groovy  # NEW: postgres search client
│   │   └── ElasticQueryTranslator.groovy # NEW: ES query → SQL translator
│   ├── src/main/groovy/org/moqui/impl/util/
│   │   └── PostgresSearchLogger.groovy   # NEW: postgres-based logging
│   └── src/test/groovy/
│       ├── PostgresElasticClientTests.groovy
│       └── PostgresSearchTranslatorTests.groovy
├── CLAUDE.md                     # This file
├── AGENTS.md                     # OpenAI Codex instructions
├── GEMINI.md                     # Google Gemini instructions
└── POSTGRES_SEARCH_PLAN.md       # PostgreSQL search design doc
```

## Critical Rules

### NEVER Modify These (Use extend-entity, SECA/EECA, MoquiConf.xml instead):
- `mantle-udm/` entity definitions
- `mantle-usl/` service definitions
- `SimpleScreens/` screen files
- `MarbleERP/` screen files
- `moqui-fop/` FOP library

### Framework Modifications (postgres search only — minimize changes):
- Changes to `framework/` are limited to the PostgreSQL search backend feature
- Any new framework changes must maintain backward compatibility with ElasticSearch

### ALWAYS:
- Create changes in **moqui-flutter component** for all Flutter/view layer work
- Use **extend-entity** to add fields/relationships to Mantle entities
- Use **SECA rules** (`.secas.xml`) to hook into existing business logic
- Use **EECA rules** (`.eecas.xml`) for entity-level triggers
- Use **MoquiConf.xml** in your component to mount screens under MarbleERP or webroot
- Use **service-call** for all business logic — never put logic directly in screens
- Use **`component://`** URLs for all screen/service/entity references
- Follow **Mantle naming**: `verb#Noun` for services, `package.Entity` for entities

## Moqui-Specific Conventions

### Entity Definitions
- File location: `YourComponent/entity/*.xml`
- Extend existing: `<extend-entity entity-name="OrderHeader" package="mantle.order">`
- New entities: Use your own package namespace
- Field types: `id`, `id-long`, `text-short`, `text-medium`, `text-long`, `text-very-long`, `number-integer`, `number-decimal`, `number-float`, `currency-amount`, `currency-precise`, `date`, `time`, `date-time`
- Primary keys: Always define `<field name="..." type="id" is-pk="true"/>`

### Service Definitions
- File location: `YourComponent/service/yourpackage/YourServices.xml`
- Naming: `yourpackage.YourServices.verb#Noun` (e.g., `mycomp.OrderServices.validate#CustomOrder`)
- Entity-auto CRUD: `<service verb="create" noun="MyEntity" type="entity-auto"/>`
- Use `<auto-parameters entity-name="..." include="nonpk"/>` to inherit entity fields
- Transaction default: `use-or-begin` (joins existing or starts new)

### Screen Development
- File location: `YourComponent/screen/...`
- Subscreens: Directory-based (create folder named same as parent screen filename)
- Transitions: For form submission/data processing, always redirect after
- Form patterns: `form-single` for edit, `form-list` for search/list
- Dynamic options: `<dynamic-options transition-name="..." server-search="true"/>`

### Data Files
- File location: `YourComponent/data/*.xml`
- Root element: `<entity-facade-xml type="seed">` (or `demo`, `install`)
- Seed = required config data, Demo = sample data, Install = one-time setup

### Flutter/Dart Conventions (moqui-flutter)
- The JSON render mode is `fjson` — requested via `?renderMode=fjson`
- `ScreenWidgetRenderJson.groovy` converts XML screen widgets to JSON
- `widget_factory.dart` converts JSON to Flutter widgets
- `moqui_api_client.dart` handles REST API calls to Moqui
- Tests: Dart unit tests in `flutter/test/`, Python e2e tests in `flutter/test/e2e/`

## Build & Run Commands

```bash
# Initial setup
git clone <repo-url> moqui-postgreonly
cd moqui-postgreonly
./gradlew load
java -jar moqui-plus-runtime.war

# Development cycle (clean rebuild)
./gradlew cleanAll
./gradlew load
java -jar moqui-plus-runtime.war

# Run with specific conf
java -jar moqui-plus-runtime.war conf=conf/MoquiDevConf.xml

# Flutter development (separate terminal)
cd runtime/component/moqui-flutter/flutter
flutter run -d web-server --web-port=8181

# Run Flutter tests
cd runtime/component/moqui-flutter/flutter
flutter test
```

## Access URLs

| URL | Description |
|-----|-------------|
| `http://localhost:8080/fapps/marble` | MarbleERP (Flutter UI) |
| `http://localhost:8080/fapps/tools` | Developer tools (Flutter UI) |
| `http://localhost:8080/qapps/marble` | MarbleERP (Quasar UI — default) |
| `http://localhost:8080/qapps/system` | System admin |
| `http://localhost:8080/qapps/tools` | Developer tools |
| `http://localhost:8080/rest/s1/mantle/` | REST API (Mantle services) |
| `http://localhost:8181` | Flutter dev server (when running separately) |

## Debugging Tips
- **Logs**: `runtime/log/moqui.log`
- **Screen path issues**: Check `qapps/tools` → Screen Info tool
- **Service errors**: Check `ec.message.errors` and `ec.message.validationErrors`
- **Entity not found**: Verify component.xml dependencies load order
- **Groovy shell**: Available at `qapps/tools` → Groovy Shell (test expressions live)
- **Flutter JSON output**: Request any screen with `?renderMode=fjson` to see raw JSON
- **Flutter cache issues**: JSON responses include no-cache headers; client adds `_t` timestamp param
