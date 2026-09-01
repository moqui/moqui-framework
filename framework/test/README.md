# HTTP security proof tests

These hit a **running** Moqui server. They are not part of `./gradlew test`.

They require **`MoquiProductionConf.xml`**. `MoquiDevConf.xml` (CORS `*`, tarpit off, H2 console at `/h2`) is expected to fail. If nothing is listening, tests **skip**.

They use whatever data is loaded on that instance. Defaults are the demo user `john.doe` / `moqui`. ProductionConf does not load demo data by itself; use a local DB that already has those users. Do not point this at a real production system.

Users named `sec.*` (`sec.view.only`, `sec.all.only`, `sec.none.only`, `sec.lock.test`, `sec.ent.view`, `sec.ent.all`, `sec.api.view`, `sec.api.all`, `sec.es.view`, `sec.es.all`) and the SystemMessage receive remotes (`SEC_SMT_TEST` / `SEC_SMR_HMAC` / `SEC_SMR_HMAC_TS` / `SEC_SMR_NONE`) are created by the Gradle `Security*` Spock tests (`SecurityTestSupport.ensureUsers`), not by demo seed. Run `./gradlew :framework:test` against the same database first, or those HTTP tests **skip**. They must not pass silently when the user or remote is missing.

The Gradle run and the HTTP run cannot share the database at the same time: Moqui's transaction log (`btm2.tlog`) is locked by whichever instance holds it. Stop the server before `./gradlew :framework:test`, then start it again before `./pytest.sh`.

## Run

```
# from the moqui-framework root, with ProductionConf already listening:
java -jar moqui.war conf=conf/MoquiProductionConf.xml
./pytest.sh
```

```
MOQUI_BASE_URL=http://127.0.0.1:8080 ./pytest.sh
MOQUI_TEST_USERNAME=john.doe MOQUI_TEST_PASSWORD=moqui ./pytest.sh
```

## Layout

| File | OWASP |
| --- | --- |
| `test_a01_access.py` | A01 unauthenticated Tools/REST (`e1` and `s1`), removed transitions (404) |
| `test_a01_more.py` | A01 FOP, /status, /email, echopath, path `..`, VIEW-only, elastic/kibana, groovysh, Data Import, `/htmlr`, `/rest/sm`, WEB-INF, `/apps` helpers |
| `test_a01_redirect.py` | A01 Login `returnTo` / Referer open redirect |
| `test_a02_headers.py` | A02 CSP, cookies, CORS (unknown Origin is 401), no H2 console |
| `test_a05_injection.py` | A05 allow-html none, SQL-looking stored as data |
| `test_a07_authn.py` | A07 REST login without CSRF, POST CSRF, api_key query string |
| `test_a07_more.py` | A07 OTP, initial admin, short password, lockout, password reset, login enumeration |
| `test_a10_errors.py` | A10 anonymous error JSON/HTML |
| `test_api_rest.py` | REST/RPC allow-remote, removed userInfo, entity REST |
| `test_z_tarpit.py` | A01 screen tarpit 429 (runs last; demo ALL_SCREENS) |

See [SECURITY_TESTS.md](../../SECURITY_TESTS.md) for the catalog. In-process proofs are Spock under `framework/src/test/groovy/Security*.groovy`.
