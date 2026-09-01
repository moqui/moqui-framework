# Security proof tests

Named tests for **moqui-framework** and **moqui-runtime**. They record what is tested, not a pentest report and not a WAF claim.

- **Where:** [SECURITY_SURFACE.md](SECURITY_SURFACE.md)
- **What:** [OWASP Top 10:2025](https://owasp.org/Top10/) (plus WSTG / ASVS L1 ideas, API Top 10 for `/rest` and `/rpc`)
- **Public tests** pass on master and prove intended controls
- **Findings** (failing tests / PoCs) stay in the private **moqui-private** repo until a fix and notification are planned; then a passing regression lands here. That repo is visible only to GitHub *moqui* organization owners (the same people as the moqui board). See its README.

| Runner | Path | How to run |
| --- | --- | --- |
| Spock (in-process) | `framework/src/test/groovy/Security*.groovy` | `./gradlew :framework:test` |
| Python (HTTP) | `framework/test/` | Start Moqui, then `pytest` (see `framework/test/README.md`) |

N/A: volumetric WAF, TLS at the proxy, `webapp_https_*` as URL generation.

## Catalog (first wave)

| Control | OWASP | Spock | Python HTTP |
| --- | --- | --- | --- |
| Unauthenticated Tools is not the tool | A01 | `SecurityAccessControlTests` | `test_a01_access.py` |
| VIEW-only cannot run Service Run / cache clear / reloadEcfi | A01 | `SecurityAccessControlTests` | not yet |
| `/rest/s1` without credentials is unauthorized | A01 | `SecurityAccessControlTests` (s1 via ScreenTest) | `test_a01_access.py` (e1 over HTTP) |
| `/rest/e1` without credentials is 401 | A01 | (ScreenTest stub has no entity REST) | `test_a01_access.py` |
| Removed `/rest/api_key` and `/rest/moquiSessionToken` | A01 | `SecurityAccessControlTests` | `test_a01_access.py` |
| Default CSP / X-Frame-Options / nosniff | A02 | `SecurityMisconfigTests` (conf) | `test_a02_headers.py` |
| CORS: no Origin → no ACAO; unknown Origin is 401 unless `allow-origins` is `*` (MoquiDevConf) | A02 | not yet | `test_a02_headers.py` |
| Session cookie HttpOnly | A02 | not yet | `test_a02_headers.py` |
| SubEtha SMTP factory default disabled | A02 | `SecurityMisconfigTests` | N/A |
| `upload-executable-allow` default false | A02 | `SecurityMisconfigTests` | N/A |
| Supply chain / CVE scan | A03 | not yet | not yet |
| Password hashing / crypt-pass | A04 | not yet | not yet |
| Default `allow-html` none | A05 | `SecurityInjectionTests` | not yet |
| SQL Runner requires `SQL_RUNNER_WEB` + AUTHZA_ALL | A05 | `SecurityAccessControlTests` | not yet |
| Insecure design (inherit model) | A06 | covered by A01 VIEW-only cases | — |
| Failed-login lockout | A07 | `SecurityAuthnTests` | not yet (avoid locking john.doe) |
| `create#InitialAdminAccount` after users exist | A07 | `SecurityAuthnTests` | not yet |
| `POST /rest/login` without CSRF | A07 | not yet | `test_a07_authn.py` |
| Other POST without CSRF token rejected | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| `api_key` in query string does not log in | A07 | not yet | `test_a07_authn.py` |
| Integrity (plugins, war) | A08 | not yet | not yet |
| Login history | A09 | not yet | not yet |
| Anonymous error pages | A10 | not yet | not yet |
| Tarpit | A01 | N/A (ScreenTest disables tarpit) | deferred |
| SSRF via ResourceFacade | A01 | not yet | not yet |
