# Security proof tests

Named tests for **moqui-framework** and **moqui-runtime**. They record what is tested, not a pentest report and not a WAF claim.

- **Where:** [SECURITY_SURFACE.md](SECURITY_SURFACE.md)
- **What:** [OWASP Top 10:2025](https://owasp.org/Top10/) (plus WSTG / ASVS L1 ideas, API Top 10 for `/rest` and `/rpc`)
- **Public tests** pass on master and prove intended controls
- **Findings** (failing tests / PoCs) stay in the private **moqui-private** repo until a fix and notification are planned; then a passing regression lands here. That repo is visible only to GitHub *moqui* organization owners (the same people as the moqui board). See its README.

| Runner | Path | How to run |
| --- | --- | --- |
| Spock (in-process) | `framework/src/test/groovy/Security*.groovy` | `./gradlew :framework:test` (uses `MoquiDevConf.xml`; default-conf proofs parse `MoquiDefaultConf.xml`) |
| Python (HTTP) | `framework/test/` | Start Moqui with **`MoquiProductionConf.xml`**, then `./pytest.sh` (see `framework/test/README.md`). DevConf is expected to fail CORS / H2 console / tarpit. |

N/A: volumetric WAF, TLS at the proxy, `webapp_https_*` as URL generation.

## Catalog (first wave)

| Control | OWASP | Spock | Python HTTP |
| --- | --- | --- | --- |
| Unauthenticated Tools is not the tool | A01 | `SecurityAccessControlTests` | `test_a01_access.py` |
| VIEW-only cannot run Service Run / cache clear / reloadEcfi / Data Import / Data Export / ElFinder / GroovyShell | A01 | `SecurityAccessControlTests` | not yet |
| Logged-in user with no artifact authz cannot open Tools | A01 | `SecurityAccessControlTests` | not yet |
| `/rest/s1` without credentials is unauthorized | A01 | `SecurityAccessControlTests` (s1 via ScreenTest) | `test_a01_access.py` (e1 over HTTP) |
| `/rest/e1` without credentials is 401 | A01 | (ScreenTest stub has no entity REST) | `test_a01_access.py` |
| Removed `/rest/api_key`, `/rest/moquiSessionToken`, `/rest/userInfo` | A01 | `SecurityAccessControlTests` | `test_a01_access.py`, `test_api_rest.py` |
| Login `returnTo` / Referer is not an open redirect | A01 | N/A (HTTP) | `test_a01_redirect.py` |
| JSON-RPC does not run `allow-remote=false` services | A01 | `SecurityIntegrityTests` (`create#UserAccount`) | `test_api_rest.py` |
| Default CSP / X-Frame-Options / nosniff | A02 | `SecurityMisconfigTests` (conf) | `test_a02_headers.py` |
| CORS: no Origin → no ACAO; unknown Origin is 401 | A02 | `SecurityMisconfigTests` (DefaultConf empty `allow-origins`) | `test_a02_headers.py` (ProductionConf required) |
| Session cookie HttpOnly | A02 | not yet | `test_a02_headers.py` |
| SubEtha SMTP / Jackrabbit factories disabled | A02 | `SecurityMisconfigTests` | N/A |
| `upload-executable-allow` default false; MZ/ELF/class/Mach-O magic | A02 | `SecurityMisconfigTests` | N/A |
| H2 console not mounted | A02 | N/A (DevConf-only servlet) | `test_a02_headers.py` |
| Log4j not 2.14; Shiro 2.x | A03 | `SecurityMisconfigTests` | N/A |
| Password hashing SHA-256; stored hash; crypt-algo; login key hashed | A04 | `SecurityCryptoTests` | N/A |
| Shipped `entity_ds_crypt_pass` default is CHANGEME (operators must override) | A04 | `SecurityCryptoTests` | N/A |
| Default `allow-html` none | A05 | `SecurityInjectionTests` | not yet |
| SQL-looking values stored as data | A05 | `SecurityInjectionTests` | not yet |
| SQL Runner requires `SQL_RUNNER_WEB` + AUTHZA_ALL | A05 | `SecurityAccessControlTests` | not yet |
| Insecure design (inherit model) | A06 | covered by A01 VIEW-only cases | — |
| Failed-login lockout | A07 | `SecurityAuthnTests` | not yet (avoid locking john.doe) |
| `create#InitialAdminAccount` after users exist | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Short password rejected | A07 | `SecurityAuthnTests` | not yet |
| `POST /rest/login` without CSRF | A07 | not yet | `test_a07_authn.py` |
| Other POST without CSRF token rejected | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| `api_key` in query string does not log in | A07 | not yet | `test_a07_authn.py` |
| Login issues a new session id (no fixation) | A07 | N/A | `test_a07_authn.py` |
| `sendOtp` / `verifyOtp` without pre-auth rejected | A07 | not yet | `test_a07_more.py` |
| Password reset unknown user does not succeed | A07 | `SecurityAuthnTests` | not yet |
| Password reset unknown vs existing messages | A07 | `SecurityAuthnTests` | not yet |
| Component zip-slip rejected | A08 | `SecurityIntegrityTests` | N/A |
| XML external entity expansion in `MNode.parse` | A05 | `SecurityIntegrityTests` | N/A |
| Login history stored; failed-login does not store password | A09 | `SecurityLoggingTests` | N/A |
| Unauthorized error page HTML-escapes the message | A10 | `SecurityErrorTests` | `test_a10_errors.py` |
| InternalError stack to anonymous users | A10 | `SecurityErrorTests` | `test_a10_errors.py` |
| Tarpit enabled in DefaultConf | A01 | `SecurityMisconfigTests` | HTTP velocity not yet |
| SSRF via ResourceFacade | A01 | VIEW-only Data Import `location=` covered | not yet |
