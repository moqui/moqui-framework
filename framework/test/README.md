# HTTP security proof tests

These hit a **running** Moqui server. They are not part of `./gradlew test`.

They use whatever data is loaded on that instance. Defaults are the demo user `john.doe` / `moqui`. Do not point this at production.

## Run

```
# from the moqui-framework root (or any dir), with Moqui already listening:
cd framework/test
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pytest
```

If nothing is listening, tests **skip** (they do not fail the run).

```
MOQUI_BASE_URL=http://127.0.0.1:8080 pytest
MOQUI_TEST_USERNAME=john.doe MOQUI_TEST_PASSWORD=moqui pytest
```

Start Moqui yourself, for example:

```
java -jar moqui.war
```

## Layout

| File | OWASP |
| --- | --- |
| `test_a01_access.py` | A01 unauthenticated Tools/REST, removed transitions |
| `test_a02_headers.py` | A02 CSP, cookies, CORS (`*` in MoquiDevConf is designed) |
| `test_a07_authn.py` | A07 REST login without CSRF, POST CSRF, api_key query string |

See [SECURITY_TESTS.md](../../SECURITY_TESTS.md) for the catalog. In-process proofs are Spock under `framework/src/test/groovy/Security*.groovy`.
