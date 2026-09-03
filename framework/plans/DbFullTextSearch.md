# In-database full-text search (`text-fts`)

Add full-text search as an **EntityFacade** feature: a dictionary field type, a per-database strategy attribute, DDL in `EntityDbMeta`, and an automatic rewrite of `LIKE` / `NOT LIKE` on those fields. Do not send these columns to OpenSearch. DataDocument / ElasticFacade stays the product for nested documents, aggregations, and logging.

This is an alternative to feeding entity text into OpenSearch through the Elastic interface. First intended caller after the facade work: `LlmSkill` body (and maybe description) for `find_skill`.

## Why this is not OpenSearch

OpenSearch is already wired (`elastic-facade`, `org.moqui.search.SearchServices`, logging datasource). It is the right tool for DataDocument trees, ArtifactHit, and anything that is already a search product.

It is the wrong tool for “this VARCHAR/CLOB on this table, ranked keyword search, no extra cluster”:

- Skills, wiki pages, product names/descriptions, and similar are **one table, a few text columns**.
- Dev default is H2; production is often Postgres. Both already have FTS of some kind.
- `SkillIndex.retrieve` today loads every active/proposed row plus every shipped `component://*/skill/*.md` and scores in Java (`contains` / token `contains`). That does not scale and does not stem.

In-DB FTS is “search these columns without a cluster.” It is not Elastic-compatible (different relevance, no nested docs).

## Current Moqui surface

- Dictionary types: `MoquiDefaultConf.xml` `<dictionary-type>` (`text-medium` … `text-very-long` / `CLOB`) and XSD `field-type-options` in `entity-definition-3.xsd`. Per-db storage override: `<database-type type="…" sql-type="…"/>`.
- `FieldInfo.type` is the dictionary name. Java type for all `text-*` is `String`.
- `FieldValueCondition.makeSqlWhere` always emits `LIKE` / `NOT LIKE` via `EntityConditionFactoryImpl.getComparisonOperatorString`. Bind is the raw pattern (`%foo%`). `ignoreCase` wraps `UPPER()`.
- `EntityDbMeta` creates B-tree indexes from entity `<index>` only. No FTS DDL. Unknown extra columns on a table are a fight with add-missing if we are not careful.
- Database dialect knobs already look like `offset-style`, `join-style`, `from-lateral-style` on `<database>`.
- `LlmSkill.body` is `text-very-long`. `SkillIndex` does not use entity-find for the query string.
- `TransactionCacheDb` HOLD uses a short-lived H2 mem DB. FTS artifacts would not exist there unless initialized per overlay.

There is no `FT_SEARCH`, `tsvector`, `MATCH AGAINST`, or `CONTAINS` anywhere in the framework today.

## Design

### Field type `text-fts`

New dictionary type, **behavior flag**, not a new Java type:

```
<dictionary-type type="text-fts" java-type="java.lang.String" default-sql-type="CLOB"/>
```

Storage matches `text-very-long` unless a `<database-type>` says otherwise (Postgres `TEXT`, MySQL `LONGTEXT`, MSSQL `NVARCHAR(max)`, Oracle `CLOB`, H2 CLOB/VARCHAR). `EQUALS` / `IN` / PK-like use stay ordinary string compares. Only `LIKE` / `NOT LIKE` change.

Add `text-fts` to `entity-definition-3.xsd` `field-type-options`.

Use it on columns that are **documents** (playbook body, wiki, description blobs). Short codes (`name`, ids) stay `text-medium` so exact lookup and btree `LIKE 'foo%'` stay cheap.

### Per-database `fts-style`

On `<database>`, same pattern as `offset-style`:

```
<xs:attribute name="fts-style" default="none">
  none | h2-native | postgres-tsvector | mysql-fulltext | mssql-contains | oracle-text
</xs:attribute>
```

Default **`none`**: emit SQL `LIKE` as today. Derby, HSQL, DB2 stay `none` unless someone later adds a style.

Proposed defaults in `MoquiDefaultConf.xml`:

| `database name` | `fts-style` |
|-----------------|-------------|
| h2 | `h2-native` |
| postgres | `postgres-tsvector` |
| mysql, mysql8 | `mysql-fulltext` |
| mssql | `mssql-contains` (document instance prerequisites) |
| oracle | `oracle-text` |
| derby, hsql, db2, db2i | `none` |

Operators can set `fts-style="none"` on Postgres to opt out without a code change.

Optional sibling later: `fts-trgm="true"` on postgres only (`pg_trgm`). See open questions.

### Finds: LIKE rewrite

No new comparison operator. Screens and services keep:

```
ec.entity.find("moqui.llm.LlmSkill")
    .condition("body", LIKE, "%inventory%")
```

Hook: `FieldValueCondition.makeSqlWhere` when `operator` is `LIKE` or `NOT_LIKE`, `fi.type == "text-fts"`, and the datasource’s database `fts-style` is not `none`.

Rewrite rules (v1):

1. If the pattern is **prefix-only** (`foo%`, no leading `%`) and not a pure FTS query, **keep SQL `LIKE`**. That stays btree-friendly on `text-medium` and is still valid on a `text-fts` column.
2. Otherwise strip `%` and `_` from the bind value. Remaining text is the FTS query. Empty after strip → `1=2` for `LIKE`, `1=1` for `NOT LIKE` (do not match all rows).
3. Split on whitespace; drop tokens shorter than 3 characters (same floor as today’s `SkillIndex.score` and MySQL’s common `ft_min_word_len`). Remaining tokens are **AND**.
4. Do not wrap `UPPER()`; FTS is case-insensitive in the styles below.
5. `NOT LIKE` is the negation of the same predicate (`NOT (tsv @@ q)`, `NOT MATCH…`, etc.).
6. View-entity: rewrite uses the **member actual column**. Generated tsvector / FULLTEXT must exist on the actual table, not the view.
7. If FTS artifacts are missing (HOLD overlay, catalog not installed), **fall back to SQL `LIKE`** rather than fail the find.

v1 is **boolean match only**. `LIKE` has no rank; do not add `ORDER BY ts_rank` unless the caller opts in later (open question).

### Metadata (`EntityDbMeta`)

After table create / check, for entities that have one or more `text-fts` fields, create dialect artifacts. Idempotent. No Liquibase. Do **not** reuse entity `<index>` (that remains B-tree).

**Do not drop** extra columns/indexes we created (`*_tsv`, `FT` schema, `FULLTEXT`). Treat them as owned by FTS, not as “unknown columns to remove.”

Grouping: H2 and MySQL often want **one index per table** covering all `text-fts` columns. Postgres can do **one generated tsvector per column** (simple, maps 1:1 to LIKE on that field) or one concatenated tsvector for the table (better rank, worse per-field LIKE). v1: **per column** so a `LIKE` on `description` does not search `body`. Document combining as a later style.

## Dialect implementations

### `none`

DDL: nothing. LIKE: `col LIKE ?` with the original pattern.

### `h2-native` (H2 2.4.x, already on the classpath)

H2 ships `org.h2.fulltext.FullText` (native). Lucene variant (`FullTextLucene`) is out of v1 (score is real but XA/classpath cost; native score is always `1.0`).

DDL:

```sql
CREATE ALIAS IF NOT EXISTS FT_INIT FOR "org.h2.fulltext.FullText.init";
CALL FT_INIT();
CALL FT_CREATE_INDEX('PUBLIC', 'LLM_SKILL', 'BODY,DESCRIPTION');  -- all text-fts cols
```

Init **once per database** (including each file/mem DB). `FT_CREATE_INDEX` is table-scoped.

Find: join `FT_SEARCH_DATA(?, 0, 0)` on PK arrays, filtered to this table. Token AND is native. No stemming. Rank is 1.0; Java/entity order unchanged.

HOLD overlay: do not `FT_INIT` the mem DB. LIKE fallback.

### `postgres-tsvector`

```sql
ALTER TABLE llm_skill ADD COLUMN body_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(body, ''))) STORED;
CREATE INDEX llm_skill_body_tsv_idx ON llm_skill USING GIN (body_tsv);
```

Column name: `{sql_column}_tsv`. Not an entity field. `startup-add-missing` must not try to drop it.

LIKE rewrite:

```sql
body_tsv @@ plainto_tsquery('english', ?)
```

Bind is the stripped query (`inventory warehouse`). `plainto_tsquery` ANDs terms and stems. `websearch_to_tsquery` is reserved for a later query syntax (OR, quotes, `-`).

No typo tolerance without `pg_trgm`.

Language: start with `'english'`. A later `fts-config` attribute can override (`simple` for codes, other dictionaries).

### `mysql-fulltext`

InnoDB `FULLTEXT` on the column (or combined columns).

```sql
ALTER TABLE LLM_SKILL ADD FULLTEXT INDEX LLM_SKILL_BODY_FTS (BODY);
```

```sql
MATCH(BODY) AGAINST (? IN BOOLEAN MODE)
```

BOOLEAN mode: `+inventory +warehouse`. v1 builds `+token` for each stripped token. Minimum token length is a server setting (often 3).

### `mssql-contains`

Requires a full-text catalog on the instance (ops prerequisite; if missing, LIKE fallback + warn once).

```sql
CONTAINS(BODY, ?)
```

v1 query: `"inventory AND warehouse"`. `CONTAINSTABLE` rank is follow-on.

### `oracle-text`

Oracle Text `CONTEXT` index. Prerequisite: Text option installed.

```sql
CONTAINS(BODY, ?, 1) > 0
```

v1: `inventory AND warehouse`. `SCORE(1)` follow-on.

## First consumer (after the facade)

Not in the first FTS PR unless it is cheap:

- `LlmSkill.body` (and optionally `description`) → `text-fts`.
- Persist shipped `component://*/skill/*.md` as `LlmSkill` rows (`provenance=human`) so they sit in the same index, **or** keep the file scan and merge with DB hits.
- `SkillIndex.retrieve` becomes entity-find `LIKE` on those fields instead of loading the corpus into Java. `find_skill.select` stays exact `name`.

`query="Show available inventory"` still misses `create-user-account`; FTS helps once a skill body talks about inventory.

## Tests

- H2 (default suite): test entity with a `text-fts` field; `LIKE '%hello%'` hits a row whose body contains `hello`; a non-word miss; `EQUALS` unchanged; prefix `hello%` still SQL LIKE if we implement that split; `fts-style=none` override still `LIKE '%x%'`.
- Wildcard strip: `%foo bar%` matches a body with both tokens (AND).
- HOLD overlay: find still works (LIKE fallback).
- Postgres: tagged or optional; stemming (`inventories` vs `inventory`) if `english`.
- No live OpenSearch assertions.

## Key decisions

1. **EntityFacade, not a SkillIndex helper and not Elastic.**
2. **`text-fts` field type** as the opt-in; storage remains string/CLOB.
3. **`fts-style` on `<database>`**, default `none`.
4. **LIKE / NOT LIKE rewrite** — no new operator in v1.
5. **Boolean match in v1**; ranking / OR / exclude are a later syntax (open question below).
6. **Per-column FTS artifacts** so a LIKE on one field does not search another.
7. **Fall back to LIKE** if artifacts or catalogs are missing (overlay, MSSQL without FT).
8. **Leave DataDocument on OpenSearch.**
9. **H2 native FT, not Lucene-on-H2.**
10. **Do not `FT_INIT` HOLD mem DBs.**

## Alternatives considered

- **Dedicated `FtsSupport.search()` API.** Rejected for v1: screens/services already write LIKE; a second API would rot.
- **New comparison operator `MATCH`.** Rejected: authors already use LIKE; rewrite is enough for boolean search. A richer query string can still ride in the LIKE value later.
- **OpenSearch for skills / this table.** Rejected: extra cluster for one table.
- **Generic `condition="FTS"` on EntityFind.** Rejected: dialects disagree; the field type is the right opt-in.
- **Concatenated table-level tsvector only.** Rejected for v1: per-field LIKE would be a lie. Can add a combined style later.
- **Lucene-on-H2.** Rejected for v1.

## Open questions

### 1. Query syntax beyond AND-of-tokens (leave open; analysis + initial proposal)

v1 LIKE rewrite is: strip `%`/`_`, AND tokens of length ≥ 3. That uses only the intersection of dialect features. Several engines can do more. The question is how callers **opt in** without a new EntityFind operator and without leaking Postgres query language into H2.

**What each style can actually do**

| Feature | postgres-tsvector | h2-native | mysql-fulltext | mssql-contains | oracle-text | LIKE fallback |
|---------|-------------------|-----------|----------------|----------------|-------------|---------------|
| AND of terms | default (`plainto_tsquery`) | default | `+a +b` | `a AND b` | `a AND b` | AND of `%tok%` |
| OR | `websearch` `OR` / `\|` | no | `a b` (no +) or `a or b` | `OR` | `OR` | not indexed |
| Exclude | `-term` / `!term` | no | `-term` | `AND NOT` | `~` | `NOT LIKE` extra cond |
| Phrase | quoted / `<->` | no | `"a b"` | `"a b"` | `{}` | `%a b%` |
| Prefix token | `lex:*` | no | `lex*` | `"lex*"` | `%` | `lex%` (already kept as LIKE) |
| Stemming | yes (`english`) | no | limited | inflectional | yes | no |
| Rank / sort | `ts_rank` | always 1.0 | `MATCH()` as number | `CONTAINSTABLE.RANK` | `SCORE()` | none |
| Field weights | `setweight` A–D | no | no per query | weights | none easy | no |
| Fuzzy / typo | `pg_trgm` `%` | no | no | inflection ≠ typo | fuzzy ops | no |

Portable core (safe in a later syntax): **AND, exclude, phrase (degrade to AND), prefix (degrade to LIKE or token\*)**. OR is portable except H2. Rank is portable except H2/LIKE (those ignore `orderBy ftsRank`). Fuzzy is Postgres-only unless we add `pg_trgm`.

**Initial proposal (not v1):** a small **websearch-shaped** language in the LIKE *value* after wildcard strip, parsed in Java, then compiled per `fts-style`. Do not pass the raw string to `to_tsquery` / `CONTAINS` (injection and dialect drift).

```
inventory warehouse          → AND
inventory OR stock           → OR (H2: AND, or skip OR terms)
-asset                       → exclude (H2: extra NOT LIKE '%asset%' or drop)
"available to promise"       → phrase if supported, else AND of words
invent*                      → prefix token if supported, else LIKE 'invent%'
```

Rules of thumb:

- Whitespace = AND (same as v1).
- Uppercase `OR` is the only infix operator (avoid colliding with the word “or” in English bodies).
- Leading `-token` is exclude. `NOT token` is not supported (too English-ambiguous).
- Double quotes = phrase.
- Trailing `*` on a token = prefix.
- No parentheses in v2; if we need grouping later, add them then.
- Unknown punctuation stripped.

Ranking: **not in the query string**. Add a reserved order-by token, e.g. `orderBy("-ftsRank")`, honored only when the find has exactly one `text-fts` LIKE and the style has a score (`ts_rank`, `MATCH()`, `RANK`, `SCORE`). H2/none: ignore the order-by (stable existing order). Do not invent a hidden column on the entity.

Exclude + rank + OR together are the v2 bar. Fuzzy (`~inventry`) stays Postgres/`fts-trgm` only if we add that attribute.

**Rejected for this syntax:** passing through Postgres `tsquery` or MySQL boolean operators as-is; a new `EntityCondition.MATCH`; encoding rank boosts (`inventory^2`) in v2.

### 2. `pg_trgm`

Optional `fts-trgm="true"` on postgres: GIN trigram on the same storage column for typo/`ILIKE` when the FTS query is a single short token. Default off (extension + index size). Complements tsvector; does not replace it.

### 3. One index vs many `text-fts` columns

v1 is per-column. A table with `title` + `body` both `text-fts` has two GIN/FULLTEXT artifacts. A combined “search all text-fts on this entity” style would help `find_skill` (one LIKE across name/title/description/body) but breaks per-field meaning. Possible later: entity attribute `fts-combine="true"` building one concatenated tsvector / one H2 index / one MySQL FULLTEXT, and LIKE on **any** of those fields uses the combined index. Leave undecided.

### 4. FTS config / language

Hard-code `'english'` in v1 for Postgres? Or `fts-config="english"` on `<database>`? Prefer a database attribute defaulting to `english` so `simple` is possible without a code change.

### 5. MSSQL / Oracle in v1

Implement postgres + h2 (+ mysql if cheap). MSSQL/Oracle styles can be stubs that LIKE-fallback until someone with that catalog writes the DDL. Do not block v1 on instance features.

## PR plan

### PR 1 — Types, conf, XSD, `fts-style=none` behavior unchanged

- **Files:** `entity-definition-3.xsd`, `moqui-conf-3.xsd`, `MoquiDefaultConf.xml` dictionary-type + `fts-style` on each `<database>` + `<database-type type="text-fts">` where sql-type differs
- **Depends on:** nothing
- **What:** type exists; finds still LIKE everywhere because rewrite is not wired yet (or rewrite is wired but `none` is default until PR 2)

### PR 2 — LIKE rewrite + EntityDbMeta for H2 and Postgres

- **Files:** `FieldValueCondition` (or a small `FtsSql` helper it calls), `EntityDbMeta`, H2 `FT_INIT`/`FT_CREATE_INDEX`, Postgres generated column + GIN; Spock on H2; optional Postgres
- **Depends on:** PR 1
- **What:** `text-fts` + `LIKE '%word%'` uses FTS on h2/postgres; overlay and `none` fall back; EQUALS unchanged

### PR 3 — MySQL FULLTEXT (optional, same series)

- **Depends on:** PR 2
- **What:** `mysql-fulltext` DDL + `MATCH … AGAINST` BOOLEAN

### PR 4 — SkillIndex consumer (optional, after FTS works)

- **Files:** `LlmEntities.xml` field types; `SkillIndex.retrieve` via entity-find; persist shipped skills or merge files
- **Depends on:** PR 2
- **What:** `find_skill` query uses in-DB FTS; `select` still exact name

### PR 5 — Query syntax v2 (open question 1)

- **Depends on:** PR 2
- **What:** parse websearch-shaped LIKE values; exclude / OR / phrase / `orderBy("-ftsRank")` where the style allows
