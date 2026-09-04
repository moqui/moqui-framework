# AGENTS.md — Moqui Framework Project Instructions

## Project Overview

This is a **Moqui Framework** ERP project. Moqui is a Java-based enterprise application framework using XML-defined entities, services, screens, and forms with Groovy scripting. The business logic layer is **Mantle** (mantle-udm for data model, mantle-usl for services), the admin UI library is **SimpleScreens**, and the full ERP application is **MarbleERP**.

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
| Any Moqui task (start here) | `runtime/component/moqui-ai-skill/SKILL.md` |

## Project Structure

```
moqui/                                  # Framework root (moqui-framework)
├── runtime/
│   ├── conf/                           # Environment-specific MoquiConf.xml files
│   ├── component/                      # All components live here
│   │   ├── mantle-udm/                 # Data model entities (DO NOT MODIFY)
│   │   ├── mantle-usl/                 # Business logic services (DO NOT MODIFY)
│   │   ├── SimpleScreens/              # Admin screen library (DO NOT MODIFY)
│   │   ├── MarbleERP/                  # ERP application (DO NOT MODIFY)
│   │   ├── moqui-ai-skill/            # AI skill docs for coding assistants
│   │   │   ├── component.xml
│   │   │   ├── SKILL.md               # Master skill reference
│   │   │   └── references/            # Detailed reference docs
│   │   │       ├── ENTITIES.md
│   │   │       ├── SERVICES.md
│   │   │       ├── SCREENS.md
│   │   │       ├── MANTLE.md
│   │   │       ├── MARBLE_ERP.md
│   │   │       └── TESTING.md
│   │   └── YourComponent/              # ← YOUR custom component goes here
│   │       ├── component.xml
│   │       ├── MoquiConf.xml           # Screen mounting, config overrides
│   │       ├── data/                   # Seed/demo data XML files
│   │       ├── entity/                 # Entity definitions & .eecas.xml
│   │       ├── service/                # Service definitions & .secas.xml
│   │       ├── screen/                 # XML Screen files
│   │       ├── script/                 # Groovy scripts
│   │       └── lib/                    # JAR dependencies
│   └── log/
├── CLAUDE.md                           # Claude Code instructions
├── AGENTS.md                           # This file (OpenAI Codex instructions)
└── GEMINI.md                           # Google Gemini instructions
```

## Critical Rules

### NEVER Modify These (Use extend-entity, SECA/EECA, MoquiConf.xml instead):
- `moqui-framework/` source code
- `moqui-runtime/` base files
- `mantle-udm/` entity definitions
- `mantle-usl/` service definitions
- `SimpleScreens/` screen files
- `MarbleERP/` screen files

### ALWAYS:
- Create a **custom component** for all changes (`runtime/component/YourComponent/`)
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

## Build & Run Commands

```bash
# Initial setup
git clone git@github.com:moqui/moqui-framework.git moqui
cd moqui
./gradlew getComponent -Pcomponent=MarbleERP
./gradlew load
java -jar moqui.war

# Development cycle (clean rebuild)
./gradlew cleanAll gitPullAll
./gradlew load
java -jar moqui.war

# Load only your component's data
./gradlew load -Ptypes=seed,seed-initial,install

# Run with specific conf
java -jar moqui.war conf=conf/MoquiDevConf.xml
```

## Access URLs

| URL | Description |
|-----|-------------|
| `http://localhost:8080/qapps/marble` | MarbleERP (Quasar UI — default) |
| `http://localhost:8080/qapps/system` | System admin |
| `http://localhost:8080/qapps/tools` | Developer tools |
| `http://localhost:8080/rest/s1/mantle/` | REST API (Mantle services) |
| `http://localhost:8080/vapps/marble` | MarbleERP (Vue.js legacy) |
| `http://localhost:8080/apps/marble` | MarbleERP (HTML legacy) |

## MarbleERP Module Quick Reference

When extending MarbleERP, target the correct module:

| Business Need | MarbleERP Module | Mount Under |
|--------------|-----------------|-------------|
| Sales orders, customer management | Customer | `marble/Customer.xml` |
| Purchase orders, vendor management | Supplier | `marble/Supplier.xml` |
| Shipments, inventory, warehouse | Internal | `marble/Internal.xml` |
| Invoices, payments, GL, reports | Accounting | `marble/Accounting.xml` |
| Projects, tasks, time tracking | Project | `marble/Project.xml` |
| Admin setup, product catalog | Configure | `marble/Configure.xml` |
| New top-level module | (new) | `marble.xml` |

## Common Patterns

### Adding a screen to MarbleERP Customer module:
```xml
<!-- YourComponent/MoquiConf.xml -->
<moqui-conf>
    <screen-facade>
        <screen location="component://MarbleERP/screen/marble/Customer.xml">
            <subscreens-item name="MyScreen"
                location="component://YourComponent/screen/marble/Customer/MyScreen.xml"
                menu-title="My Screen" menu-index="20"/>
        </screen>
    </screen-facade>
</moqui-conf>
```

### Hooking into order placement:
```xml
<!-- YourComponent/service/mycomp/OrderSeca.secas.xml -->
<secas>
    <seca service="mantle.order.OrderServices.place#Order" when="tx-commit">
        <actions>
            <service-call name="mycomp.MyServices.after#OrderPlaced" in-map="context" async="true"/>
        </actions>
    </seca>
</secas>
```

### Extending an entity:
```xml
<!-- YourComponent/entity/mycomp/ExtendEntities.xml -->
<entities>
    <extend-entity entity-name="OrderHeader" package="mantle.order">
        <field name="customChannel" type="text-short"/>
        <relationship type="one" title="CustomChannel" related="moqui.basic.Enumeration" short-alias="customChannelEnum">
            <key-map field-name="customChannel" related="enumId"/>
        </relationship>
    </extend-entity>
</entities>
```

## Debugging Tips
- **Logs**: `runtime/log/moqui.log`
- **Screen path issues**: Check `qapps/tools` → Screen Info tool
- **Service errors**: Check `ec.message.errors` and `ec.message.validationErrors`
- **Entity not found**: Verify component.xml dependencies load order
- **Groovy shell**: Available at `qapps/tools` → Groovy Shell (test expressions live)
