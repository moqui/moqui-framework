# Deterministic transaction mode (protocol v1)

Opt in before factory initialization:

```sh
export MOQUI_DETERMINISTIC=true
export MOQUI_DETERMINISTIC_HOOK=http://127.0.0.1:9000/transaction
export MOQUI_DETERMINISTIC_ENGINE=java
export MOQUI_DETERMINISTIC_RUN=my-run
export MOQUI_DETERMINISTIC_LOG=/absolute/path/deterministic.log
java -jar moqui.war --deterministic no-run-es
```

`--deterministic` works before/after `load` too. It sets the flag; the other four settings remain required. JVM properties with the same names override environment variables for embedding/tests. Do not change settings during execution. URLs must have `/transaction`, no credentials/query/fragment. No redirects/retries. Connect timeout is five real seconds; the entire HTTP exchange, **including response body**, has a real deadline of **120 seconds**, configurable with `MOQUI_DETERMINISTIC_TIMEOUT_SECONDS` (1–3600). Only HTTP 200 and strictly shaped JSON responses are accepted. Duplicate JSON fields and nonintegral/out-of-range times fail. A hook/log failure poisons the process clock: stop the run, do not retry or continue with wall time. Completion acknowledgement can fail **after the database committed**.

## Explicit feature exclusions and limits

The default server configuration can start in this mode. **A startup WARN banner explicitly announces these mode-level exclusions**, and effective configuration is saved to `runtime/log/MoquiActualConf.xml`:

* Autonomous service-job polling is disabled (`scheduled-job-check-time=0`). Explicit service jobs, periodic scheduling, distributed async, load runner, dynamic threaded restart, and cross-thread EC sharing **throw**, rather than dropping requested work. Ordinary async services/EC workers are retained and dispatched inline.
* Elastic clients/logging/index startup are disabled; explicit operations requiring unavailable Elastic clients are unsupported, not faked. **Artifact-hit/bin statistics are collected under the normal persistence configuration, not disabled.** Individual hits and completed bins queue in memory and persist synchronously on the serial lane at factory shutdown, before datasource teardown. Current bins persist there too. No statistics background writer runs. Profiling, slow-hit classification, and bin boundary timing remain real. Local normal logging remains enabled.
* JNDI/external transaction management and threaded JDBC statement timeouts are rejected. Use internal Bitronix and transaction timeouts. Raw SQL connections **require an explicit facade transaction**. Streaming `iterator()` also requires an explicit transaction spanning iteration and close. External configuration SQL connections are rejected. Low-level datasource/JTA handles, custom threads/executors, database-generated time defaults/triggers, custom ToolFactory background work, and arbitrary component wall-clock calls are outside this implementation. Configure such integrations off. There is no JVM/bytecode-wide clock interception.
* LLM streaming lease renewal is rejected; nonstreaming code uses the assigned clock. A2A/provider I/O is not replayed. External writers are unsupported. Embedded H2 servers/consoles must not be used to introduce concurrent external writers.
* Always destroy an EC on its owning thread. EC lifetime is the application-lane admission boundary; another request waits until destruction. Do not wait for another application thread while retaining an EC. Independent arrival order is not made deterministic: the caller/coordinator must order inputs.
* PostgreSQL supports transactional schema setup and is the intended strict integration target. H2's DDL has database-internal implicit commits; H2 tests establish facade/DML behavior, **not** a one-hook-per-H2-DDL-commit guarantee. Other databases with implicit DDL commits are likewise not strict schema-replay targets.

`framework/src/test/resources/DeterministicConf.xml` is a minimal isolated test configuration, not a production configuration. The WAR smoke test uses the actual private runtime's **unchanged** `MoquiDevConf.xml`.

## Clocks and database lifecycle

`org.moqui.util.DeterministicClock.nowMillis()` is the shared application clock. Disabled mode uses wall time; enabled mode without an assigned operation throws. User timestamp/calendar APIs (including effective-time override), generated entity stamps, date filters, notifications, auth dates, feeds/documents, built-in sync/message services, and LLM persistence/date defaults use assigned time. Explicit caller-supplied business dates remain caller data. Cache expiry, file/resource freshness, network heartbeats and timeout/profiling durations remain real.

Every new facade transaction synchronously requests time **before** JTA begin, then reports the actual JTA synchronization outcome. Failed initialization reports `begin_failed`. Joined transactions request nothing. Require-new runs inline using suspend/resume, gets new time, and restores parent time. Suspended parents prevent async draining. Public raw JTA handles are not supported entry points.

Normally autocommit entity operations are **not** represented by one synthetic EC operation. In deterministic mode direct create/update/delete/refresh, fast find, materialized one/list/count, bulk create/update/delete are promoted to real facade transactions. Their generated defaults/stamps run **inside that transaction's assigned clock**. Read operations may begin an empty JTA transaction even when the result is cached. JDBC/schema metadata operations use facade transactions too; shared-connection DDL stays in its owning transaction. The central `getConnectionInternal()` rejects remaining uncovered autocommit access. This deliberately changes transaction topology in enabled mode, not normal mode. Use explicit transaction brackets for streaming/raw JDBC or unsupported custom paths.

Factory initialization, EC nontransactional work, queued dispatches, and shutdown also have **explicit synthetic coordinator operations** (`AT_OTHER / transaction / bootstrap`) using the same begin/complete protocol. These are clock/lane scopes, **not claims that all enclosed SQL is one DB transaction**; actual facade transactions appear as nested begin/complete events. Embedders doing logical work outside an EC can explicitly bracket it:

```java
DeterministicClock.Operation operation = DeterministicClock.bootstrap();
boolean success = false;
try {
    // nontransactional work; start facade transactions for database work
    success = true;
} finally {
    if (operation != null) operation.complete(success ? "committed" : "rolled_back");
}
```

Metadata comes from the active artifact stack, innermost first: only type, action detail (`sub_type`), and name. No parameters/users/credentials/exceptions/real timestamps. Empty context uses the singleton bootstrap stack. Direct implicit entity operations and implicit entity-auto services contribute their actual operation identities before begin. Applications must not push sensitive/dynamic artifact names. `parent_sequence` is the enclosing transaction or explicit operation, including suspended parents. Local sequences are **not** cross-engine matching keys.

The process-wide fair reentrant lane lock covers these scopes. Async work uses a FIFO inline queue, draining only with no active **or suspended** transaction, after outer completion and at teardown. Each dispatch gets a fresh EC; caller EC is restored. Awaiting pending async work inside a transaction fails instead of deadlocking. Worker exceptions propagate in enabled mode. No requested async work is silently skipped. Statistics persistence failures propagate without deterministic retries or successful-shutdown claims. Shutdown buffering retains all configured statistics but consumes memory proportional to queued hits/completed bins; abrupt process termination can lose unflushed statistics, and persistence is delayed until factory shutdown.

The process coordinator/logger and sequence survive synchronous factory destroy/recreate. JSONL is **append**, flushed per event; factory teardown never truncates/closes the process logger. This is not crash recovery or cross-JVM sequence recovery. Failed/unacknowledged operations are not fabricated as successful. No data copying, extra seed exceptions, or topology normalization is used. Java's per-file loader and Rust's loader need not have identical transaction topology; strict matching may fail legitimately.

## Application entropy

`DeterministicRandom` uses the same coordinator hook for `event="random"` requests:
`version`, `run_id`, `engine`, `event`, `domain` and `length` (1..4096); response
`{"bytes":"<lowercase hex>"}`. The coordinator owns the seed, phase and per-engine
semantic-domain draw counters. Random requests do not open or pair transactions;
HTTP/log/response failures poison execution without a normal-random fallback.
Request metadata is logged, never generated credentials. Domain separation keeps
metadata/ECA/node draws from shifting entity UUIDs. UUID v4 bits, URL-base64 strings,
53-bit unit doubles and rejection-sampled bounded integers have cross-language vectors.

Audited framework UUID/string/salt/code/TOTP/jitter sources use this adapter. The
executable WAR additionally selects `DeterministicSessionIdManager` (session ids
are persisted in Visit); EmailServices selects `DeterministicHtmlEmail` to control
JavaMail's persisted Message-ID, including repeated `saveChanges`. Neither changes
normal-mode generators. Routing a source does not enable otherwise forbidden work.

**Never use deterministic mode in production: credentials/secrets are predictable.**
Transport crypto, third-party MIME boundaries, browser/vendor JavaScript, arbitrary
components and other servlet containers are not globally intercepted. External
integrations require explicit fixtures/adapters; equality is not established by
seeding alone. The cross-engine harness documents the seed algorithm and full audit.

## Verification

With the private runtime available and Java 21 on PATH:

```sh
./gradlew :framework:deterministicTest :framework:war
python framework/test/deterministic-server-smoke.py
python framework/test/deterministic-server-smoke.py --postgres
```

The PostgreSQL smoke test needs Docker/Podman with `postgres:18` and a PostgreSQL JDBC jar in private `runtime/lib`. It creates/removes its own fresh container and verifies actual seeded rows, startup factory restart, HTTP `/status` and `/Login`, SIGTERM shutdown, complete protocol/log streams, and actual persisted artifact-hit/bin rows and hit counts (including Login bins). It does **not** compare against Rust. Results are under `framework/build/reports/deterministic-server/`.

The focused Gradle task tests strict protocol/metadata validation, append/sequence continuity, failed acknowledgements and stalled-body real deadlines; actual Bitronix/H2 seed load, generated stamps, joined/nested/implicit transactions, rollback/rollback-only/begin failure, queued workers/services, lane exclusion and factory restart; exact persisted hit/bin counts, duration totals, parameter redaction and a bin rollover with statistics enabled; plus **disabled-mode** real clocks, effective time and background/require-new threads. Evidence: `framework/build/reports/tests/deterministic-integration.jsonl` and Gradle test reports. Full component coverage and strict cross-engine equivalence are not established by these tests.
