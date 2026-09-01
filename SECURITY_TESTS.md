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

Python `sec.*` users (`sec.view.only`, `sec.all.only`, `sec.none.only`, `sec.lock.test`, `sec.ent.view`, `sec.ent.all`, `sec.api.view`, `sec.api.all`, `sec.es.view`, `sec.es.all`, `sec.ip.v4`, `sec.ip.loop`) are inserted by Gradle `SecurityTestSupport.ensureUsers`. They are not demo seed and are not in a `loadSave` snapshot. HTTP tests skip if those users are missing rather than pass. IP-restricted and already-locked accounts use the same public login text as a missing user; those proofs probe `sec.none.only` (created in the same `ensureUsers` call) instead of treating a failed login as “not loaded”.

The two runners cannot share the database at the same time (Moqui locks `btm2.tlog`): stop the server before `./gradlew :framework:test`, then start it again before `./pytest.sh`.

## Catalog (first wave)

| Control | OWASP | Spock | Python HTTP |
| --- | --- | --- | --- |
| Unauthenticated Tools is not the tool | A01 | `SecurityAccessControlTests` | `test_a01_access.py` |
| VIEW-only cannot run Service Run / cache clear / reloadEcfi / Data Import / Data Export / ElFinder / GroovyShell | A01 | `SecurityAccessControlTests` | `test_a01_more.py` (Service Run) |
| Logged-in user with no artifact authz cannot open Tools | A01 | `SecurityAccessControlTests` | `test_a01_more.py` |
| `/rest/s1` without credentials is unauthorized | A01 | `SecurityAccessControlTests` (s1 via ScreenTest) | `test_a01_access.py` |
| `/rest/e1` without credentials is 401 | A01 | (ScreenTest stub has no entity REST) | `test_a01_access.py` |
| Removed `/rest/api_key`, `/rest/moquiSessionToken`, `/rest/userInfo` (404, not merely unauthenticated) | A01 | `SecurityAccessControlTests` | `test_a01_access.py`, `test_api_rest.py` |
| Login `returnTo` / Referer is not an open redirect | A01 | `SecurityMisconfigTests` (`isSameOriginRedirect`) | `test_a01_redirect.py` |
| Login unknown vs wrong password public text | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| `/apps` getPreferences / qzSign without login | A01 | N/A (HTTP) | `test_a01_more.py` |
| Unknown path `/htmlr` does not leak a Java stack (not a servlet) | A10 | N/A (HTTP) | `test_a01_more.py` |
| JSON-RPC does not run `allow-remote=false` services | A01 | `SecurityIntegrityTests` (ServiceDefinition `allowRemote` flag) | `test_api_rest.py` |
| Default CSP / X-Frame-Options / nosniff | A02 | `SecurityMisconfigTests` (conf) | `test_a02_headers.py` |
| CORS: no Origin → no ACAO; unknown Origin is 401 (including OPTIONS preflight) | A02 | `SecurityMisconfigTests` (DefaultConf empty `allow-origins`) | `test_a02_headers.py` (ProductionConf required) |
| Session cookie HttpOnly | A02 | `SecurityMisconfigTests` (web.xml) | `test_a02_headers.py` |
| Session cookie SameSite Lax | A02 | N/A (`__SAME_SITE_LAX__` in `web.xml` is an inert comment Jetty 12 ignores; the control is `MoquiContextListener` `SessionCookieConfig.setAttribute`) | `test_a02_headers.py` |
| SubEtha SMTP / Jackrabbit factories disabled | A02 | `SecurityMisconfigTests` | N/A |
| `upload-executable-allow` default false; MZ/ELF/class/Mach-O magic | A02 | `SecurityMisconfigTests` | N/A |
| H2 console not mounted | A02 | N/A (DevConf-only servlet) | `test_a02_headers.py` |
| Log4j 2.17+ (not 2.14–2.16); Shiro 2.x | A03 | `SecurityMisconfigTests` | N/A |
| Password hashing SHA-256; stored hash; crypt-algo; login key hashed | A04 | `SecurityCryptoTests` | N/A |
| Shipped `entity_ds_crypt_pass` default is CHANGEME (operators must override) | A04 | `SecurityCryptoTests` | N/A |
| Default `allow-html` none (service parameter validation) | A05 | `SecurityInjectionTests` | `test_a05_injection.py` |
| SQL-looking values stored as data | A05 | `SecurityInjectionTests` | `test_a05_injection.py` |
| VIEW-only cannot open SQL Runner | A05 | `SecurityAccessControlTests` | `test_a01_more.py` |
| AUTHZA_ALL without `SQL_RUNNER_WEB` / `GROOVY_SHELL_WEB` cannot use those tools | A01 | `SecurityAccessControlTests` | N/A |
| Insecure design (inherit model) | A06 | covered by A01 VIEW-only cases | — |
| Failed-login lockout | A07 | `SecurityAuthnTests` | `test_a07_more.py` (`sec.lock.test`) |
| `create#InitialAdminAccount` after users exist | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Short password rejected | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| `POST /rest/login` without CSRF | A07 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| Other POST without CSRF token rejected; with token is not a CSRF error | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| HTTP `api_key` in query string does not log in (header/body is the intended API; minting transition removed) | A07 | `SecurityHandshakeTests` (WS query-string api_key) | `test_a07_authn.py` |
| WebSocket handshake: `api_key` header logs in; query `api_key` / `authUsername` do not | A07 | `SecurityHandshakeTests` | N/A |
| Anonymous has no `GROOVY_SHELL_WEB`; `/notws` ignores `userId == null` | A01 | `SecurityHandshakeTests` | `test_a01_more.py` (HTTP GET); `test_a01_ws.py` (live upgrade) |
| `loginUserKey` round-trip (hashed at rest) | A07 | `SecurityAuthnTests`, `SecurityCryptoTests` | N/A |
| Login issues a new session id (no fixation) | A07 | N/A | `test_a07_authn.py` |
| `send#ExternalAuthcCode` without pre-auth rejected | A07 | `SecurityAuthnTests` | `test_a07_more.py` (`sendOtp`; the `verifyOtp` case is stopped by CSRF before the pre-auth gate, see Proof strength notes) |
| Password reset unknown user returns the generic shared message (no account enumeration) | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Password reset unknown vs existing messages are indistinguishable | A07 | `SecurityAuthnTests` | `test_a07_more.py` |
| Component zip-slip rejected | A08 | `SecurityIntegrityTests` | N/A |
| XML external entity expansion in `MNode.parse` | A05 | `SecurityIntegrityTests` | N/A |
| Login history stored; failed-login does not store password | A09 | `SecurityLoggingTests` | N/A |
| Unauthorized error page HTML-escapes the message | A10 | `SecurityErrorTests` (injected payload) | `test_a10_errors.py` (HTML 401 has no script/stack) |
| InternalError stack only when `showErrorDetail` (servlet: non-production and logged-in) | A10 | `SecurityErrorTests` | `test_a10_errors.py` (direct hit / HTML 401) |
| Tarpit enabled in DefaultConf | A01 | `SecurityMisconfigTests` (flags + demo ALL_SCREENS XML) | `test_z_tarpit.py` (429 if demo tarpit loaded; otherwise skip) |
| Unauthenticated / VIEW-only cannot invoke Data Import `location=` (authorized remote fetch is a designed power feature) | A01 | `SecurityAccessControlTests` | `test_a01_more.py` |
| ElFinder hashed `..` stays under resourceRoot | A01 | `SecurityIntegrityTests` | N/A |
| create#UserAccount extra password fields ignored | A01 | `SecurityIntegrityTests` | N/A |
| Login HTML Cache-Control not public | A02 | N/A | `test_a02_headers.py` |
| `/echopath` extra path HTML-escaped | A05 | N/A | `test_a01_more.py` |
| `/fop` without login is not a PDF | A01 | N/A | `test_a01_more.py` |
| `/email` unknown id returns PNG; known id is an unauthenticated `ES_VIEWED` update (designed) | A01 | `SecurityIntegrityTests` (`markEmailMessageViewed`) | `test_a01_more.py` (unknown id PNG) |
| SqlRunner does not execute `sql` from the query string (`secureRequestParameters` / body only) | A05 | `SecurityMisconfigTests` (`simplifyRequestParameters`) | `test_a01_more.py` (ADMIN_ADV / demo user) |
| `getClientIp` ignores `X-Forwarded-For` when `webapp_client_ip_header` is empty | A01 | `SecurityMisconfigTests` | `/status` with `X-Forwarded-For` / `Forwarded` still returns JSON from loopback in `test_a01_more.py` |
| `/rest/sm` HMAC: missing/bad signature and stale timestamp are 403; valid HMAC needs no session | A01 | fixtures in `SecurityTestSupport` | `test_api_rest.py` |
| `/status` JSON omits sensitive keys (`datasources`, `VmVendor`); allow-list is loopback | A01 | N/A | `test_a01_more.py` |
| Login form posts to Login/login (Host URL gen is operator `webapp_http_host`) | A01 | N/A | `test_a01_more.py` |
| Session cookie SameSite Lax actually sent | A02 | N/A (see the row above; only the HTTP proof is meaningful) | `test_a02_headers.py` |
| Static `..` does not escape screen files | A01 | N/A | `test_a01_more.py` |
| Entity REST POST without CSRF does not create | A01 | N/A | `test_api_rest.py` |
| `/elastic` and `/kibana` without login | A01 | N/A | `test_a01_more.py` |
| `/groovysh` HTTP GET is not a shell (not a WebSocket handshake) | A01 | N/A | `test_a01_more.py` |
| Unknown path `/htmlr/...` is not a PDF | A01 | N/A | `test_a01_more.py` |
| `/rest/sm` unknown type/remote rejected | A01 | N/A | `test_a01_more.py` |
| Login transition via GET with password rejected | A07 | N/A | `test_a01_more.py` |
| `/WEB-INF/web.xml` is not served | A01 | N/A | `test_a01_more.py` |
| `/menuData` Tools without login is not the menu | A01 | N/A | `test_a01_more.py` |
| HTTP Basic auth: wrong password 401; valid is not unauthenticated | A07 | N/A | `test_api_rest.py` |
| CSRF positive control: POST with a valid token actually runs the transition (setPreference round-trip) | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| POST without a token does not store the preference (negative companion) | A01 | `SecurityAccessControlTests` | `test_a07_authn.py` |
| CORS same-origin request gets `Access-Control-Allow-Origin` + credentials; preflight is allowed | A02 | N/A | `test_a02_headers.py` |
| `/elastic` logged in without `ElasticRemote` is denied (not just anonymous 401) | A01 | N/A | `test_a01_more.py` |
| Authenticated POST with `X-HTTP-Method-Override` still requires CSRF (override does not skip the token) | A01 | N/A | `test_api_rest.py` |
| Failed-login lockout re-enables after `disable-minutes`; stays locked inside the window | A07 | `SecurityAuthnTests` | N/A |
| H2 TCP server default args: `-ifExists`, no `-tcpAllowOthers` | A02 | `SecurityMisconfigTests` | N/A |
| `screen-secure` HSTS header in DefaultConf (applied only when the request is HTTPS) | A02 | `SecurityMisconfigTests` (conf) | N/A |
| `/rest/e1` narrow `AT_ENTITY` VIEW reads its own entity, is 403 on another, and cannot create | A01 | N/A | `test_api_rest.py` |
| `/rest/e1` catch-all `AT_ENTITY` ALL reads other entities and creates (positive companion) | A01 | N/A | `test_api_rest.py` |
| `EntitySyncServices` `AUTHZA_VIEW` blocks `put#EntitySyncData`; `AUTHZA_ALL` stores | A01 | N/A | `test_api_rest.py` |
| `/rest/sm` timestamped HMAC inside the window is accepted (positive companion to the stale row) | A01 | fixtures in `SecurityTestSupport` | `test_api_rest.py` |
| `SmatNone` remote does not accept an anonymous POST | A01 | N/A | `test_api_rest.py` |
| `/rest/s1/moqui/users` `MOQUI_API` VIEW reads, cannot PATCH; no REST-path authz is 403; ALL reads (positive companion) | A01 | N/A | `test_api_rest.py` |
| `/elastic` Basic auth: no permission 403, bad password 401, garbage `api_key` 401/403, permission reaches the cluster | A01 | N/A | `test_a01_more.py` |
| `allow-html="safe"` keeps benign markup, strips `script` / `onerror` / `javascript:` | A05 | `SecurityInjectionTests` | N/A |
| `allow-html="any"` skips HTML validation; `none` rejects and `safe` accepts the same value (positive companions) | A05 | `SecurityInjectionTests` | N/A |
| ElFinder `joinUnderRoot` keeps traversal, backslash, and scheme-like segments under the root | A01 | `SecurityIntegrityTests` | N/A |
| ElFinder `joinUnderRoot` allows a normal nested path; hash round-trips (positive companions) | A01 | `SecurityIntegrityTests` | N/A |
| `getClientIp` address shapes (IPv4, IPv4:port, IPv6 loopback, IPv6 full, `[IPv6]:port`) | A01 | `SecurityMisconfigTests` | N/A |
| Handshake with no credentials / wrong Basic / garbage `api_key` does not keep a previous user | A01 | `SecurityHandshakeTests` | `test_a01_ws.py` anonymous `/groovysh` after an admin session |
| `ipAllowed` matches IPv4 and IPv6; a non-matching client is rejected | A07 | `SecurityAuthnTests` | `test_a07_more.py` (`sec.ip.v4`, `sec.ip.loop`) |
| ElFinder write commands on `component://` / `file:` roots in production; entry names; directory `rm` | A01 | `SecurityIntegrityTests` | `test_a01_more.py` (component webroot `put`) |
| `upload-executable-allow` default read from the default-property, not the unexpanded conf attribute | A02 | `SecurityMisconfigTests` | N/A |
| Component zip with contained entries expands (positive control for the zip-slip rows) | A08 | `SecurityIntegrityTests` | N/A |
| `MNode` parse of a stream / file and external DTD subset does not expand external entities | A05 | `SecurityIntegrityTests` | N/A |
| Notification listener registers an endpoint that has a userId (positive control) | A01 | `SecurityHandshakeTests` | N/A |
| `ScreenTest` `WebFacadeStub` session token matches the CSRF fixture token (positive-control guard) | A01 | `SecurityMisconfigTests` | N/A |
| DataSnapshot download rejects `..` / `/` in `filename`; VIEW-only cannot download (`authz-action="update"`) | A01 | N/A | `test_a01_more.py` |
| `/fop` `filename` with a quote does not split `Content-Disposition`; `contentType` is PDF/PS only | A03 | N/A | `test_a01_more.py` |
| `hasLoggedOut` is set on logout | A07 | `SecurityAuthnTests` | N/A |
| `POST /rest/login` failed credentials are 401 with the generic public text | A10 | N/A | `test_a07_more.py` |
| Login `returnTo` with a mismatched `Host` header does not redirect off-site | A01 | `SecurityMisconfigTests` | `test_a01_redirect.py` |
| `POST` + `X-HTTP-Method-Override` is authorized as the override action | A01 | N/A | `test_api_rest.py` |
| `PATCH /rest/s1/moqui/users` does not store `currentPassword` | A01 | N/A | `test_api_rest.py` |
| `GET /rest/s1/moqui/users` and entity REST UserAccount omit password hash fields | A01 | `SecurityMisconfigTests` | `test_api_rest.py` |
| Screen JSON `currentParameters` omits password / credential fields | A02 | `SecurityMisconfigTests` | `test_a07_more.py` |
| REST schema dumps: anonymous 401, no `REST_SCHEMA` 403, no `ACAO: *` | A01 | N/A | `test_api_rest.py` |
| `/rest/sm` timestamped HMAC rejects a repeated signature inside the window | A02 | fixtures in `SecurityTestSupport` | `test_api_rest.py` |

### Designed exposure (documented, not a control)

These rows record a default that is deliberately permissive. They are not proofs of a control; they exist so a future change to the default is noticed.

| Documented default | OWASP | Spock |
| --- | --- | --- |
| Groovy Shell WebSocket endpoint `/groovysh` is enabled by default (RCE by design for `ADMIN_ADV` + `GROOVY_SHELL_WEB`) | A02 | `SecurityMisconfigTests` |
| `reset#Password` is `authenticate="anonymous-all"` and `allow-remote="true"` (remote-callable password reset is untrusted input) | A07 | `SecurityIntegrityTests` |

### Proof strength notes

- Rows with `SecurityMisconfigTests` / `SecurityCryptoTests` are **config-flag or unit proofs**: they parse `MoquiDefaultConf.xml`, `web.xml`, or call a utility. They prove the default is what is claimed, not that runtime enforcement can't be overridden. Where a behavior proof exists it is in the Python column.
- Spock screen proofs use in-process `ScreenTest`/`WebFacadeStub`, not the servlet filter chain. Entity REST (`/rest/e1`), servlet filters (`MoquiAuthFilter`), and WebSocket upgrades are HTTP-only in the Python column.
- `SecurityErrorTests` pass `showErrorDetail` as a **screen parameter**; the servlet-level gate (non-production and logged-in) is not exercised end-to-end.
- The `/status` tests connect from loopback, so they prove forwarded headers do not hide the JSON (and sensitive keys stay omitted), not that a non-allow-listed TCP source is rejected.
- The `/notws` `userId == null` Spock test asserts the endpoint is not stored and has a positive control, but topic/ACL semantics are still untested: any connected user can `subscribe:` to any topic name (delivery is still gated by `NotificationMessage.getNotifyUserIds()`), and the set is unbounded.
- Positive controls (token actually runs the transition, same-origin CORS allowed, re-enable window, zip expands, notification endpoint registered, XXE document still parses) are the companion assertions to the negative rows above. A negative row without one would still pass if the feature simply stopped working.
- HSTS is a `screen-secure` conf header; `ScreenRenderImpl` adds it only when `request.isSecure()`. There is no HTTP proof on the ProductionConf HTTP listener.
- `SecurityLoggingTests` uses dedicated users (`sec.hist.only`, `sec.hist.fail`) and a `fromDate` watermark. `loginSaveHistory` writes at most one `UserLoginHistory` row per user per 60 seconds, so a history test that only reads the newest row can pass on a row written by an earlier spec in the same run.
- `test_a07_more.py` `test_verifyOtp_without_preauth_does_not_log_in` does `GET /Login` first, so the tokenless `POST /rest/verifyOtp` is rejected by the CSRF check (`ScreenRenderImpl`) rather than by `validate#ExternalUserAuthcCode`. The pre-auth gate itself is only proven by `SecurityAuthnTests` (and only for `send`, not `verify`).
- `test_a07_more.py` `test_create_initial_admin_http_fails_when_users_exist` accepts `"error" in body`, which matches most rendered pages. `SecurityAuthnTests` (`ec.message.hasError()`) is the real proof.
- `SecurityMisconfigTests` `simplifyRequestParameters` fabricates the query string and the parameter map independently, so it cannot see that the util compares a **raw** query-string name against a **decoded** container parameter name. See Future work.

## Future work

Still open; not public failing PoCs:

- **Send-side HMAC** (`send#SystemMessageRest` still TODOs `SmatHmacSha256`). Receive HMAC is covered.
- **`SmatNone` remotes** are explicit no-auth in `SystemMessageRemote.messageAuthEnumId`, but
  `receive#IncomingSystemMessage` still requires a user, so an anonymous POST is not accepted. `test_api_rest.py`
  pins not-200 plus an auth-failure signal (today the status is 500). `SECURITY_SURFACE.md:151` overstates the exposure.
- **Authorized Data Import `location=`:** AUTHZA_ALL can pass a remote URL into `EntityDataLoader` (SSRF-class by design).
- **Positive HTTP `api_key` header** with a minted key. There is no minting transition; Spock covers `getLoginKey` / handshake header login.
- **MFA positive flow** (sendOtp → verifyOtp → login with code); only the no-pre-auth rejection is covered.
- **Tarpit velocity for services and transitions**; only conf flags plus the demo ALL_SCREENS 429 are covered.
  Needs `ArtifactTarpit` rows for `AT_SERVICE` / `AT_XML_SCREEN_TRANS`, which `SecurityTestSupport` does not create.
- **Screen render-mode data dumps** (`.csv` / `.xml` extensions on authorized screens).
- **Upload end-to-end** (multipart to `runtime/tmp`, `upload-executable-allow`); only magic-byte detection is unit-tested. Note `WebUtilities.isExecutable` only inspects bytes 0-3, so a text-prefixed payload, a `#!` script, and a JAR/ZIP are not detected.
- **WebSocket Origin / cross-site WebSocket hijacking** (no Origin allow-list test exists). `MoquiContextListener.checkOrigin` delegates to the container default and returns true.
- **`api_key` / `login_key` in the request body** login path, and HTTP Basic on screens (REST-only today).
- **Password reset token lifecycle**: no public test yet. `reset#Password` needs a user with an `emailAddress`
  (the `sec.*` fixtures have none), which is why only the public-message rows are covered.
- **`ArtifactAuthzFailure` rows for denied REST calls**: every `/rest/*` transition is `read-only="true"`
  (so `AUTHZA_VIEW`) and `rest.xml` is `require-authentication="false"`, which means denied REST requests may
  write no authz-failure record. Not yet measured.
- **`simplifyRequestParameters` encoded query names**: HTTP probes with `sq%6c` / `%73ql` on SqlRunner did not
  execute SQL (the raw-vs-decoded name comparison is fragile, not currently bypassable). Still worth decoding
  the query-string name before comparing.
