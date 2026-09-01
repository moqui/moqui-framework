# HTTP security proof tests

These hit a **running** Moqui server. They are not part of `./gradlew test`.

They require **`MoquiProductionConf.xml`**. `MoquiDevConf.xml` (CORS `*`, tarpit off, H2 console at `/h2`) is expected to fail. If nothing is listening, tests **skip**.

They use whatever data is loaded on that instance. Defaults are the demo user `john.doe` / `moqui`. ProductionConf does not load demo data by itself; use a local DB that already has those users. Do not point this at a real production system.

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
| `test_a01_access.py` | A01 unauthenticated Tools/REST, removed transitions |
| `test_a01_redirect.py` | A01 Login `returnTo` / Referer open redirect |
| `test_a02_headers.py` | A02 CSP, cookies, CORS (unknown Origin is 401), no H2 console |
| `test_a07_authn.py` | A07 REST login without CSRF, POST CSRF, api_key query string |
| `test_a07_more.py` | A07 OTP without pre-auth, initial admin HTTP |
| `test_a10_errors.py` | A10 anonymous error JSON/HTML |
| `test_api_rest.py` | REST/RPC allow-remote, removed userInfo, entity REST |

See [SECURITY_TESTS.md](../../SECURITY_TESTS.md) for the catalog. In-process proofs are Spock under `framework/src/test/groovy/Security*.groovy`.
