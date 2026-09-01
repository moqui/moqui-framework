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
| VIEW-only cannot run Service Run / cache clear / reloadEcfi / Data Import / Data Export / ElFinder / GroovyShell | A01 | `SecurityAccessControlTests` | `test_a01_more.py` (Service Run) |
| Logged-in user with no artifact authz cannot open Tools | A01 | `SecurityAccessControlTests` | `test_a01_more.py` |
| `/rest/s1` without credentials is unauthorized | A01 | `SecurityAccessControlTests` (s1 via ScreenTest) | `test_a01_access.py` (e1 over HTTP) |
| `/rest/e1` without credentials is 401 | A01 | (ScreenTest stub has no entity REST) | `test_a01_access.py` |
| Removed `/rest/api_key`, `/rest/moquiSessionToken`, `/rest/userInfo` | A01 | `SecurityAccessControlTests` | `test_a01_access.py`, `test_api_rest.py` |
| Login `returnTo` / Referer is not an open redirect | A01 | N/A (HTTP) | `test_a01_redirect.py` |
| Login unknown vs wrong password public text | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| `/apps` getPreferences / qzSign without login | A01 | N/A (HTTP) | `test_a01_more.py` |
| `/htmlr` anonymous error has no Java stack | A10 | N/A (HTTP) | `test_a01_more.py` |
| JSON-RPC does not run `allow-remote=false` services | A01 | `SecurityIntegrityTests` (`create#UserAccount`) | `test_api_rest.py` |
| Default CSP / X-Frame-Options / nosniff | A02 | `SecurityMisconfigTests` (conf) | `test_a02_headers.py` |
| CORS: no Origin → no ACAO; unknown Origin is 401 | A02 | `SecurityMisconfigTests` (DefaultConf empty `allow-origins`) | `test_a02_headers.py` (ProductionConf required) |
| Session cookie HttpOnly / SameSite Lax | A02 | `SecurityMisconfigTests` (web.xml) | `test_a02_headers.py` |
| SubEtha SMTP / Jackrabbit factories disabled | A02 | `SecurityMisconfigTests` | N/A |
| `upload-executable-allow` default false; MZ/ELF/class/Mach-O magic | A02 | `SecurityMisconfigTests` | N/A |
| H2 console not mounted | A02 | N/A (DevConf-only servlet) | `test_a02_headers.py` |
| Log4j not 2.14; Shiro 2.x | A03 | `SecurityMisconfigTests` | N/A |
| Password hashing SHA-256; stored hash; crypt-algo; login key hashed | A04 | `SecurityCryptoTests` | N/A |
| Shipped `entity_ds_crypt_pass` default is CHANGEME (operators must override) | A04 | `SecurityCryptoTests` | N/A |
| Default `allow-html` none | A05 | `SecurityInjectionTests` | `test_a05_injection.py` |
| SQL-looking values stored as data | A05 | `SecurityInjectionTests` | `test_a05_injection.py` |
| SQL Runner requires `SQL_RUNNER_WEB` + AUTHZA_ALL | A05 | `SecurityAccessControlTests` | `test_a01_more.py` |
| Insecure design (inherit model) | A06 | covered by A01 VIEW-only cases | — |
| Failed-login lockout | A07 | `SecurityAuthnTests` | `test_a07_more.py` (`sec.lock.test`) |
| `create#InitialAdminAccount` after users exist | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Short password rejected | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| `POST /rest/login` without CSRF | A07 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| Other POST without CSRF token rejected | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| `api_key` in query string does not log in | A07 | N/A (HTTP) | `test_a07_authn.py` |
| `loginUserKey` round-trip (hashed at rest) | A07 | `SecurityAuthnTests`, `SecurityCryptoTests` | N/A |
| Login issues a new session id (no fixation) | A07 | N/A | `test_a07_authn.py` |
| `sendOtp` / `verifyOtp` without pre-auth rejected | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Password reset unknown user does not succeed | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Password reset unknown vs existing messages | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Component zip-slip rejected | A08 | `SecurityIntegrityTests` | N/A |
| XML external entity expansion in `MNode.parse` | A05 | `SecurityIntegrityTests` | N/A |
| Login history stored; failed-login does not store password | A09 | `SecurityLoggingTests` | N/A |
| Unauthorized error page HTML-escapes the message | A10 | `SecurityErrorTests` | `test_a10_errors.py` |
| InternalError stack to anonymous users | A10 | `SecurityErrorTests` | `test_a10_errors.py` |
| Tarpit enabled in DefaultConf | A01 | `SecurityMisconfigTests` (flags + demo ALL_SCREENS XML) | `test_z_tarpit.py` (429 if demo tarpit loaded; otherwise skip) |
| SSRF via ResourceFacade | A01 | VIEW-only Data Import `location=` covered | `test_a01_more.py` (unauthenticated Data Import) |
| ElFinder hashed `..` stays under resourceRoot | A01 | `SecurityIntegrityTests` | N/A |
| create#UserAccount extra password fields ignored | A01 | `SecurityIntegrityTests` | N/A |
| Login HTML Cache-Control not public | A02 | N/A | `test_a02_headers.py` |
| `/echopath` extra path HTML-escaped | A05 | N/A | `test_a01_more.py` |
| `/fop` without login is not a PDF | A01 | N/A | `test_a01_more.py` |
| `/email` unknown id returns PNG | A01 | N/A | `test_a01_more.py` |
| `/status` no sensitive keys via X-Forwarded-For | A01 | N/A | `test_a01_more.py` |
| Login form posts to Login/login (Host URL gen is operator `webapp_http_host`) | A01 | N/A | `test_a01_more.py` |
| Session cookie SameSite Lax actually sent | A02 | `SecurityMisconfigTests` (web.xml) | `test_a02_headers.py` |
| Static `..` does not escape screen files | A01 | N/A | `test_a01_more.py` |
| Entity REST POST without CSRF does not create | A01 | N/A | `test_api_rest.py` |
| `/elastic` and `/kibana` without login | A01 | N/A | `test_a01_more.py` |
| `/groovysh` HTTP GET is not a shell | A01 | N/A | `test_a01_more.py` |
| `/htmlr` Tools without login is not a PDF | A01 | N/A | `test_a01_more.py` |
| `/rest/sm` unknown type/remote rejected | A01 | N/A | `test_a01_more.py` |
| Login transition via GET with password rejected | A07 | N/A | `test_a01_more.py` |
| `/WEB-INF/web.xml` is not served | A01 | N/A | `test_a01_more.py` |
