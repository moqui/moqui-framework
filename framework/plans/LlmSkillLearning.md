# Skill-first memory for Moqui (Unforgettable-shaped, ERP-native)

Make the Assist/LLM agent remember *how* to do work as skills, look those up before inventing a path, and when none exists spawn a sub-agent in a sim that can read production data and write only to an overlay. Do not import the Python Unforgettable package. Reimplement the architecture in the framework, in Moqui terms.

## Why this is not Unforgettable's default act path

Unforgettable (`~/work/unforgettable/unforgettable/`) is progressive memory for a coding face:

| Layer | Holds |
|-------|--------|
| **A** working | This episode (dropped at end) |
| **B** notebook | Claims, procedures, error→fix, twin notes (SQLite + FTS, admission gates) |
| **C** sidecar | Optional LoRA from admitted B (out of scope here) |

Default throne policy (`plans/MemoryWheels.md` §6, `throne/policy.py`): **act in the world → on recognized failure enter sim → retry world**. Sim is a filesystem clone (`fs.copy`). World remains the judge of record. Sim-only procedures stay **proposed** until admitted.

That default is wrong for Moqui. ERP writes are often irreversible in practice (orders, invoices, shipments, payments, email). The framework already refuses to sit in a JTA transaction across an LLM HTTP call (`allow-tx-over-http` defaults false; TX timeout 60s vs LLM timeout 120s). Assist already gates world writes on a **user click** (`write_ui`, server never submits).

So Moqui inverts the contact policy:

```
[1] RETRIEVE skills for the user task
       shipped component://…/skill/*.md  +  admitted LlmSkill rows
        |
        v
[2] Skill hit? ──yes──> follow it
        |                 Assist: write_ui from the skill, user click is world confirm
        |                 Headless: honor skill.risk (reversible | confirm | irreversible)
        no
        v
[3] ENTER SIM (sub-agent, overlay EC, same authz, no email/http/async)
        explore with browse / run_service / request
        test until success_criteria or budget
        extract a proposed skill (provenance=sim)
        |
        v
[4] Parent follows the new skill (still proposed) — world judge of record
        Assist: user still clicks
        Headless: confirm unless risk=reversible
        |
        v
[5] World success → promote skill provenance sim → mixed/world
        World fail  → error→fix on the skill, more sim, or escalate
```

Unforgettable's "throne may tighten: sim-first for irreversible" is the **default** here. After a known skill is followed in the world and fails, the Unforgettable recovery path still applies: enter sim, repair the skill, retry world.

C (adapters) stays parked. Nothing here trains weights.

## Current Moqui surface this builds on

Already on `llm-client`:

- `LlmClient` / `LlmAgentLoop` sequential tool loop
- Tools: `request` (ScreenRender, authz on), `run_service`, `browse`, `write_ui`
- Conversations: `LlmConversation` / `LlmMessage` / `LlmCallLog`
- Assist profile (`component://tools/template/AssistSystem.md`) currently **inlines** the playbooks (create user, sales order). Those *are* skills stuck in the system prompt.
- `TransactionCache` — per-JTA-TX map overlay (`Synchronization.flush` on commit). Documented holes: view-entities, counts, DB limit, some iterators. This is the shape to extend, not the sim itself.
- `ArtifactExecutionFacade` already has `disableEntityEca`, `disableEntityDataFeed`, `disableAuthz`, audit-log disable.
- `sequencedIdPrimary` banks IDs in `runRequireNew` against `SequenceValueItem` — a sim that calls this **burns production sequences**.
- Entity create/update/delete always `EntityCache.clearCacheForValue` even when `TransactionCache` swallowed the SQL. Overlay writes must not touch the shared cache.
- `transactional#clone1` exists as a replica datasource hook (disabled by default).

## Skills (Moqui's Layer B, procedure-first)

A skill is a playbook, not a service signature. `browse` remains the raw catalog. Skills say *which* services/screens, in what order, with which checks.

### Shipped (files)

```
component://tools/skill/create-user-account.md
component://PopCommerce/skill/place-sales-order.md
```

Markdown + YAML front matter (same idea as Grok `SKILL.md` and the current Assist system file):

```markdown
---
name: place-sales-order
description: Create a sales order with line items for an existing customer
risk: confirm
services: [mantle.order.OrderServices.create#Order]
screens: [/rest/s1/mantle/orders]
---
# Place a sales order
...steps the agent must follow...
## Verify
- OrderHeader.orderId exists
- items DEMO_1_1 qty 2, DEMO_1_2 qty 1
```

Scan at startup / on first retrieve via `ResourceFacade` (locations `component://*/skill/*.md`). Files are always `provenance=human`, `status=active`.

### Learned (entities)

New package `moqui.llm` entities (extend `LlmEntities.xml`):

- **`LlmSkill`** — `skillId`, `name` (unique), `title`, `description`, `body`, `risk` (`LskReversible` / `LskConfirm` / `LskIrreversible`), `status` (`active` / `proposed` / `superseded` / `deprecated` / `rejected`), `provenance` (`world` / `mixed` / `human` / `sim` / `infer`), `speaker`, `warrant`, `sourceLocation` (file path or null), `version`, `supersededSkillId`, `worldSuccessCount`, `simSuccessCount`, `lastUsedDate`
- **`LlmSkillUse`** — conversation/episode, skillId, contact (`world`|`sim`), outcome (`pass`|`fail`), notes
- **`LlmLesson`** — error→fix rows (`kind` analog), linked to a skill when applicable

Admission (same total order spirit as Unforgettable `admit()`):

1. Extracted from sim → **proposed**, `provenance=sim`
2. Explicit operator/file → **active**
3. Proposed + world pass → promote `provenance` to `mixed`/`world`, status **active**
4. Supersede keeps the old row

Do not auto-promote sim-only dynamics to world truth.

### Retrieve

- Tool `find_skill` (query, limit)
- Gateway inject: top-k skills into the prompt window as untrusted CONTEXT (same rule as `injectContext` — never SYSTEM), except a short standing header of compiled active world/human skills may live next to the Assist system text
- v1 matching: name/title/description `LIKE` + shipped-file index in memory. Elastic/OpenSearch FTS later if the corpus needs it
- Drop a WHO/infer hit that shares a normalized name with a retrieved world/human skill

### Agent policy (not just a prompt hope)

`LlmAgentLoop` / `LlmGateway` grows a small throne:

1. On each user turn, retrieve skills.
2. If hits: inject them; system text says follow a skill before `browse`.
3. If no hits and the turn is a task (not a pure question): inject "no skill — call `enter_sim` before `run_service`/`request` writes". Assist may still `write_ui` a clarification form without sim.
4. After world pass following a proposed skill: admit.

Move the create-user and sales-order playbooks out of `AssistSystem.md` into shipped skills. The system file shrinks to: build a canvas, follow skills, else sim.

## Sim overlay: `TransactionCacheDb` (H2 working set)

The desired object: **the sub-agent sees live production facts and can create/update/delete as if they were real, without committing any of it, without locking production rows across an LLM call, and without sending email or charging a card.** View-entities (Mantle lives on them) must see the agent's own writes, or the sim will fail *correct* approaches.

Today's `TransactionCache` already pretends to be the database for one JTA TX — but it is maps/lists, it is a `Synchronization` that dies at commit, and it does not do view-entities, counts, or DB limits. The sim needs the same *idea* with a **real SQL database**, and it must **outlive many short transactions** (service begin/commit, `persistIsolated` for LLM rows, LLM HTTP in between with no TX at all).

### H2 is adequate

Moqui already ships `com.h2database:h2:2.4.240`, the full H2 `database` node (types, `name-replace` for `VALUE`, EntityDbMeta DDL, tests). Named in-memory DBs work:

```
jdbc:h2:mem:tcdb_<id>;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
```

`DB_CLOSE_DELAY=-1` keeps the catalog alive across connection close so one instance can serve many sequential JTA transactions. H2 2.x allows multiple connections to the same named mem DB if a worker ever needs it; the sim agent stays on the request thread like `RequestTool`.

Do **not** use H2 `CREATE LINKED TABLE` into production (extra connections, type/XA mess). Do **not** put the overlay schema inside the production Postgres catalog. The overlay is a **working-set replica in process**, copy-on-read from production, writes only in H2.

Caveats (acceptable, documented):

- Native JDBC / DB-specific functions in a view-entity or `efi.sqlFind` may not translate; those finds stay on production and will not see H2-only creates (twin_note).
- Huge unbounded list finds copy into heap; cap copy-on-read (e.g. 10k rows) and fail loud rather than melt the JVM.
- Encrypted fields copy as already-decrypted value maps through `FieldInfo` (same as a normal entity read then create).
- SQLite would be a worse overlay (types, writers, no first-class Moqui dialect). A second Postgres is overkill for a working set.

### `EntityTxCache` + `TransactionCacheDb`

Extract the methods `EntityFindBase` / `EntityValueBase` / `EntityListIteratorImpl` already call into an interface (name bikeshed-able; `EntityTxCache` below):

`create` / `update` / `delete` / `refresh` / `oneGet` / `onePut` / `listGet` / `checkUpdateValue` / `getFindAugmentInfo` / `flushCache` / `isReadOnly` / `makeReadOnly` / `makeWriteThrough` / `close`

- **`TransactionCache`** — existing maps, still a JTA `Synchronization`. Default for `useTransactionCache()`.
- **`TransactionCacheDb`** — H2 working set. **Not** a Synchronization. One instance, many TXs.

#### Gate is on `EntityFacade`, thread-local

`EntityFacade` is a process singleton. `start`/`stop` **must** be per ECI/thread, not global.

```
ec.entity.startTxCache(cache)   // ThreadLocal / eci field
ec.entity.stopTxCache()         // detach; HOLD mode close() drops the mem DB
ec.entity.getActiveTxCache()
```

Convenience: `ec.entity.startTxCacheDb(TxCacheMode mode)` constructs the H2 instance.

Resolution everywhere that currently does `tfi.getTransactionCache()`:

```
efi.getActiveTxCache() ?: tfi.getTransactionCache()
```

Active facade cache **wins** and survives `commit`/`begin`/`requireNew`. Nested LLM `persistIsolated` can still commit `moqui.llm.*` to production because those entities are on the exclude list (below).

This is the difference from today's cache: **the service TX is not the lifetime of the overlay.**

#### Two modes, same class

| Mode | When | JTA commit | `stopTxCache()` |
|------|------|------------|-----------------|
| **HOLD** | Sim | Ignore (H2 keeps dirty rows) | Drop mem DB, never write production |
| **FLUSH** | Optional `useTransactionCache` replacement | `flushCache` dirty rows to production via `basicCreate`/`Update`/`Delete` on the world connection (same as today's map cache) | Flush if anything left, then drop |

FLUSH is the "expansion to TransactionCache": view-entities and counts work *during* the service because finds run on H2. Default `useTransactionCache()` stays the light maps (copy-on-read of a 50k-row find is not free). Opt in later with `useTransactionCacheDb(true)` or conf. Design the class for both from day one; ship HOLD first if time is tight.

Do not enlist the H2 connection in Bitronix. Overlay ops use the dedicated H2 connection (auto-commit or a short H2-local TX). World JTA is unrelated in HOLD.

### How it pretends to be the database

H2 holds two kinds of rows: **clean copies** (read from production) and **dirty** (created/updated/deleted in the overlay). Only dirty rows ever flush in FLUSH mode.

1. **Lazy DDL.** On first touch of an actual entity, `CREATE TABLE` in the mem DB with **H2 types** (`database name="h2"`), same table name, via `EntityDbMeta` SQL generation pointed at the overlay connection — not `ed.getEntityGroupName()`'s production dialect (Postgres `TEXT` vs H2 `VARCHAR`, etc.).
2. **Writes.** `EntityValueBase` create/update/delete: if the active cache is `TransactionCacheDb` and the entity is not excluded, run SQL on H2, mark PK dirty, **skip** production `basicCreate`/`Update`/`Delete`, **skip** `EntityCache.clearCacheForValue` in HOLD. Deleted production PKs go to a small tombstone table in H2 so copy-on-read will not resurrect them.
3. **Read by PK.** Query H2; if miss and not tombstoned, query production (read committed, **no** `forUpdate` in HOLD), `INSERT` the row into H2 as clean, return it.
4. **List / view-entity / count.** This is why we have a real DB:
   - Run the find on **production** (same conditions; H2 dialect not used here) to pull the current world working set.
   - Copy each result into H2 as clean **unless that PK is already dirty or tombstoned**. For view-entities, copy **member entity rows** (we have view-link metadata), not only the projected view row.
   - Re-run the find on **H2** with **H2 dialect** (`EntityFindBuilder` connection + `database` node override — `EntityQueryBuilder.makeConnection` is the plug point) and return that iterator.
   - H2-only creates now join; H2 updates/deletes are visible; counts and limits are correct **on the working set**.

Limit edge: production `LIMIT 20` then H2 `LIMIT 20` can hide an H2-only create that would sort into the first page. For sim (mostly PK follows after create) this is fine. For FLUSH-as-TX-cache, if the overlay has creates for that entity, drop the production limit or merge/sort in Java under a modest cap.

5. **Exclude list (always production, never H2):** `moqui.llm.*` (skills, conversations, call logs), `moqui.entity.SequenceValueItem`. Sim sequences are a **local bank** on the cache instance — do not `runRequireNew` against production `SequenceValueItem`. Authz artifacts stay on.

### Side-effect fence (separate from the cache, still required)

`TransactionCacheDb` is the data twin. Email, HTTP, and async are not entity rows. `eci.simSession` (or `TxCacheMode.HOLD` implying sim) also:

| Channel | Sim behavior |
|---------|----------------|
| Email | Capture; fake `emailMessageId`; no SMTP |
| `RestClient` / payments | Refuse |
| Async / jobs | Record, do not run |
| Domain `runRequireNew` | Overlay still active; do not flush HOLD |
| DataFeed / Elastic | `disableEntityDataFeed()` |
| Audit log / ArtifactHit | Skip or overlay |
| Notifications | In-memory |
| Content / files | Sim tmp dir |
| Authz / tarpit | **ON**, same user |
| EECA/SECA | Run; their entity writes hit H2; their email/HTTP hit the fence |

### Nested sub-agent

New **server** tool `enter_sim`:

- Args: `goal`, `success_criteria`, `max_iterations` (default 8)
- `ec.entity.startTxCacheDb(HOLD)` + sim fence
- Nested `LlmClient` (same profile/model, **no** `write_ui`, system: "you are in sim; nothing commits; return a skill") with `browse` / `run_service` / `request`
- Inner conversation persisted `contact=sim` (those rows are excluded from H2, so they really persist — that is wanted)
- On finish: extract `LlmSkill` **proposed** + optional `LlmLesson`; tool result `{skillId, name, body, passed, traceSummary}`
- `finally`: `stopTxCache()` (drop mem DB), clear sim fence

Budgets: 1 spawn per user turn, 8 sim turns, profile timeout. Escalate rather than loop. Parent follows the proposed skill on the world rim (Assist click / risk confirm).

### Rejected overlay shapes (kept for the record)

| Approach | Why not |
|----------|---------|
| Long JTA + rollback / savepoint | Locks, TX timeout, `allow-tx-over-http`. Forbidden. |
| Detached map `TransactionCache` as the sim | View-entities, counts, limits. Mantle will false-fail. |
| Union views / `search_path` in the **production** Postgres catalog | DB-specific DDL, pooled-connection `search_path` leaks, ops risk. H2 working set is the same idea, in-process, already in the tree. |
| H2 linked tables to production | Second JDBC hop, types, XA. Copy-on-read is simpler. |
| Full DB clone per spawn | Keep as a later `db.clone` / `transactional#clone1` escape hatch, not the default. |
| Service dry-run flags | Most services ignore them. |

## What this week can honestly ship

1. **Skills as data + retrieve + Assist system shrink** — playbooks become `component://tools/skill/*.md`, `LlmSkill`, `find_skill`, inject.
2. **`EntityTxCache` interface + `TransactionCacheDb` HOLD** — EntityFacade start/stop, copy-on-read, H2 lazy DDL, view-entity find sees a sim-created row, production unchanged, entity cache/sequences unpoisoned. This PR is useful **without** LLM (and is the expansion path for `useTransactionCache`).
3. **Sim fence + `enter_sim` + admission** — miss skill → nested agent on HOLD cache → proposed skill → parent follows; world pass promotes.

FLUSH mode as `useTransactionCacheDb` can land with (2) or immediately after. `db.clone` is optional later.

## Tests (framework Spock; FakeLlmProtocol for agent)

**TransactionCacheDb (no LLM):**

- Create entity in HOLD; production find is null; `stopTxCache` still null
- Update a production row in HOLD; production still old; H2 find sees new
- Delete in HOLD; production still there; H2 find null; copy-on-read does not resurrect
- View-entity: create members in HOLD, view find returns the joined row (this is the test that map-`TransactionCache` cannot pass)
- Two `runRequireNew` (or begin/commit) cycles while HOLD is active: second TX still sees first TX's H2 writes
- `SequenceValueItem` bank on production unchanged; `entity.record.one` for an unrelated cached entity unchanged
- Excluded `moqui.llm.*` create during HOLD actually persists
- FLUSH (if in the same PR): dirty rows appear in production on flush; clean copies do not get written as duplicates

**Skill / sim agent:**

- Retrieve prefers shipped/active world over proposed sim
- No matching skill → `enter_sim`; matching skill → no sim
- Sim then world: proposed skill promoted after a successful world `run_service`
- Authz: sim `run_service` as non-admin still fails forbidden services
- Email not sent in sim

No live provider tests.

## Key decisions

1. **Skill-first, not world-act-first.** ERP irreversibility + existing user-click gate. Unforgettable recovery (fail → sim → retry) remains for *known* skills that fail in world.
2. **Reimplement in Moqui, do not embed Unforgettable.**
3. **C (LoRA) out of scope.**
4. **Never hold JTA across LLM HTTP.** `allow-tx-over-http` stays false. Overlay is H2, not an open world TX.
5. **`TransactionCacheDb` on in-memory H2 is the overlay.** Same pretends-to-be-the-DB job as `TransactionCache`, real SQL so view-entities work, lifetime gated by `EntityFacade.startTxCache` / `stopTxCache` (thread-local), not by JTA.
6. **Copy-on-read working set, not a full clone and not linked tables.** Dirty vs clean rows; only dirty flush in FLUSH mode.
7. **Light map `TransactionCache` remains the default `useTransactionCache()`.** Db cache is opt-in (sim HOLD; later FLUSH for services that need views).
8. **Overlay writes never mutate `EntityCache` or `SequenceValueItem`.** `moqui.llm.*` always hits production.
9. **Side-effect fence is separate from the cache.** Email, RestClient, async, DataFeed.
10. **Same user, authz on** in sim.
11. **Sim-extracted skills stay proposed** until a world pass (Assist click counts).
12. **Assist playbooks leave the system prompt** and become the first shipped skills.

## Alternatives considered

- **World-act-first like Unforgettable.** Rejected for Mantle-shaped writes.
- **Long TX rollback as sim.** Rejected: locks, timeouts, sequence banks, `allow-tx-over-http`.
- **Maps-only detached TransactionCache as sim.** Rejected: view-entities.
- **Union views in the production catalog.** Rejected in favor of in-process H2; same SQL idea, no catalog DDL on prod.
- **H2 linked tables.** Rejected: copy-on-read is enough and safer.
- **Service dry-run only.** Rejected: not a twin.
- **Skills as Wiki or only as SYSTEM text.** Files + entities are inspectable and durable.
- **Force `enter_sim` in code when retrieve is empty.** Prompt+tool first; hard gate later if models skip it.

## Open questions

None blocking:

- Headless `/llm/v1/chat` with `run_service`: follow proposed sim skills only when `risk=reversible`, else wait for confirm. Assist already has the click.
- Whether FLUSH/`useTransactionCacheDb` ships in the same PR as HOLD or the next one. Default: same class, HOLD tests required, FLUSH tests if it fits.

## PR Plan

### PR 1 — Skill store, retrieve, Assist playbooks as skills

- **Files:** `framework/entity/LlmEntities.xml`, `framework/data/LlmTypeData.xml`, skill index + `FindSkillTool`, `LlmGateway` inject, `runtime/base-component/tools/skill/*.md`, shrink `AssistSystem.md`, Spock tests
- **Depends on:** nothing
- **What:** `LlmSkill` / `LlmSkillUse` / `LlmLesson`; scan `component://*/skill/*.md`; `find_skill`; inject top-k; move create-user and sales-order out of the system file

### PR 2 — `EntityTxCache` + `TransactionCacheDb` (H2) + EntityFacade start/stop

- **Files:** `EntityTxCache` interface; `TransactionCache` implements it; new `TransactionCacheDb`; `EntityFacade` / `EntityFacadeImpl` start/stop/getActive; `EntityFindBase`, `EntityValueBase`, `EntityListIteratorImpl`, `EntityQueryBuilder.makeConnection` (H2 dialect + overlay connection when active cache is Db); `EntityDbMeta` table-create on overlay connection with H2 types; `EntityFacadeImpl.sequencedIdPrimary` local bank when HOLD; Spock tests listed above (including a framework test entity + view-entity)
- **Depends on:** nothing (LLM-independent)
- **What:** Real-DB overlay that spans multiple TXs. HOLD never touches production. View-entity find sees overlay creates. This is also the hook for a future `useTransactionCacheDb`.

### PR 3 — Sim fence + `enter_sim` + admission

- **Files:** `eci.simSession` / fence in email, RestClient, async, DataFeed; `EnterSimTool`; nested `LlmClient`; skill extract; FakeLlmProtocol tests
- **Depends on:** PR 1, PR 2
- **What:** Miss skill → `startTxCacheDb(HOLD)` + fence → nested agent → proposed skill → `stopTxCache`. World pass promotes.

### PR 4 — FLUSH mode as optional TX-cache (optional, same series)

- **Files:** `ServiceCallSync.useTransactionCacheDb`, or `initTransactionCache` conf; Synchronization adapter that calls `flushCache` on the **already running** Db instance without `close`; tests that view-entity + count are correct during a service and persist on commit
- **Depends on:** PR 2
- **What:** The expansion of `TransactionCache` for services that need SQL-correct overlay inside one (or more) TX.

### PR 5 — `db.clone` escape hatch (optional)

- **Files:** profile `sim-twin` / `sim-datasource-group`; `transactional#clone1`
- **Depends on:** PR 3
- **What:** Operator snapshot when an in-process working set is not enough (native SQL, extra XA stores).
