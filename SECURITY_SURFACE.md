# Attack surface of moqui-framework and moqui-runtime

This is a map of network and application entry points in **moqui-framework** and **moqui-runtime** (the `webroot` and `tools` base components). It is for operators, security researchers, and AI-assisted review of these two repositories. It is not a vulnerability report, not a WAF, and not a substitute for the [Security](https://www.moqui.org/m/docs/framework/Security) and [Run and Deploy](https://www.moqui.org/m/docs/framework/Run+and+Deploy) docs.

Application and tool components (Mantle, HiveMind, PopCommerce, `moqui-sso`, Camel, FOP engine, CUPS, and so on) add their own screens, services, and REST roots. Those are out of scope here except where the framework exposes a servlet or endpoint they plug into. SSO `returnTo` / RelayState must use the same `WebUtilities.isSameOriginRedirect` helper as Login.

Reporting undisclosed issues: see [SECURITY.md](SECURITY.md) (`moqui-board@googlegroups.com`).

## How to read this

A typical production request path:

**Client → WAF / reverse proxy / TLS → Jetty (or other servlet container) → filters / servlets / XML screens → artifact authz → database, OpenSearch, mail, jobs**

Moqui Framework is **application** security: authentication, optional MFA, optional SSO (separate component), artifact-aware authorization, CSRF session tokens, HTML allow-lists, and per-artifact tarpit. It is designed to run **behind** a WAF and reverse proxy. Overlapping knobs inside the app are not a WAF. `webapp_https_enabled`, `webapp_https_port`, and `webapp_http_host` are for **URL generation** behind that edge; they do not terminate TLS.

The operator checklist (secrets, no demo data, private DB/OpenSearch, MFA/SSO, don’t publish the servlet port) lives in [Run and Deploy — Production security](https://www.moqui.org/m/docs/framework/Run+and+Deploy). This file inventories **what can be reached** when those two repos are deployed with everything they can enable.

**Defaults:** rows say **on** or **off** for `MoquiDefaultConf.xml` / seed data. “Everything enabled” means those defaults **plus** turning on optional tool-factories (SubEtha SMTP, Jackrabbit), using H2 (so the H2 TCP server starts), and using the OpenSearch/Kibana proxies.

Each surface: path or bind, default, authn, authz, untrusted input, review considerations. Designed power features (Groovy Shell, SQL Runner, entity REST, Data Import) are **intentionally privileged**, not listed as bugs.

### HTTP authentication

`UserFacadeImpl.initFromHttpRequest()` after any existing session user:

1. HTTP Basic `Authorization` header
2. `api_key` or `login_key` request header
3. `api_key` or `login_key` in the request body (not the query string)
4. `authUsername` and `authPassword` in the request body

The WebSocket handshake (`initFromHandshakeRequest`) uses the existing HTTP session, then the same Basic and `api_key` / `login_key` **headers**. It does not read credentials from the upgrade query string.

There is no `/rest/api_key` minting transition and no `/rest/moquiSessionToken` fetch (both removed). Login keys are hashed `UserLoginKey` values from `ec.user.getLoginKey()` (current user only). Generic entity REST, Auto Screen, Data Edit, Data Import, and `put#EntitySyncData` do not create or list `UserLoginKey`.

### Authorization in one paragraph

Screens use `require-authentication`: `true` (default), `false`, `anonymous-view`, or `anonymous-all`. Artifact authz (`ArtifactAuthz` / `ArtifactGroup`) is checked as each screen, transition, service, REST path, and entity is pushed on the execution stack. **Inheritable** allow/always records authorize children on that stack (sub-screens, transitions, services, entities) unless a more specific DENY wins. Screen transitions use `authz-action` (`view` / `create` / `update` / `delete` / `all`): explicit, else from a transition-level `service-call`, else `view` if `read-only`, else `update` if the transition has actions, else `view`. A VIEW-only inheritable authz does **not** run mutating Tools/System transitions (cache clear, Service Run, instance start, and similar). A few tools also check `UserPermission` (`GROOVY_SHELL_WEB`, `SQL_RUNNER_WEB`, `SERVICE_LOAD_RUNNER`, `ADMIN_LOGIN_AS`, `ADMIN_PASSWORD`, `ElasticRemote`, `KibanaRemote`). Details: [Security — Artifact-Aware Authorization](https://www.moqui.org/m/docs/framework/Security).

Seed (runtime `ToolsSecurityData.xml`, framework `SecurityTypeData.xml`): `ADMIN` has `AUTHZT_ALWAYS` + `AUTHZA_ALL` + `inheritAuthz=Y` on the Tools app root, System app root, and `/moqui` Service REST root (`MOQUI_API`). `ADMIN_ADV` has the tool-gate permissions above (`GROOVY_SHELL_WEB`, `SQL_RUNNER_WEB`, `SERVICE_LOAD_RUNNER`, `ADMIN_LOGIN_AS`); `ADMIN` also has `ADMIN_PASSWORD`, `ElasticRemote`, and `KibanaRemote`. `ALL_USERS` can view Screen Tree / App List.

System/Security screens still administer users and groups, but membership in `user_privileged_groups` (default `ADMIN`, `ADMIN_ADV`) can be changed only by a current member of that group. `UserGroupPermission` rows for `user_sealed_permissions` (default the tool-gate list plus `ADMIN_PASSWORD`, `ElasticRemote`, `KibanaRemote`, `REST_SCHEMA`) can be granted only by a caller who already has that permission. Both lists are `default-property` values in `MoquiDefaultConf.xml`. Seed/install (`disableAuthz`) is unchanged.

### Cross-cutting controls (assume unless a row says otherwise)

- CSRF: non-GET screen transitions require the session token (`moquiSessionToken` / `X-CSRF-Token`) unless `require-session-token="false"`. `webapp_require_session_token` defaults true.
- Login throttle: `user-facade.login` `max-failures="3"` disables the account for `disable-minutes="5"` after consecutive failures (re-enabled after that window); not a substitute for WAF-level throttling of the login endpoint.
- Tarpit: screens, transitions, and services on; entities off. Per-user velocity, not a flood WAF. Demo data adds an example ALL_SCREENS tarpit (120 hits / 60s).
- HTML: `parameter.@allow-html` is `none` by default; `safe` cleans with Jsoup (`Safelist.relaxed()`); `any` is unconstrained.
- Uploads: Commons FileUpload to `runtime/tmp`; `upload-executable-allow` defaults false (`WebUtilities.isExecutable`).
- CORS: `handle-cors` defaults true; `Access-Control-Allow-Credentials` is true; `Access-Control-Allow-Origin` is set if the request `Origin` is in `webapp.@allow-origins` (empty by default) or `allow-origins` contains `*`, and always for same-origin requests; other origins get 401.
- Default response headers: CSP `frame-ancestors 'none'; form-action 'self';`, `X-Frame-Options` sameorigin, `X-Content-Type-Options` nosniff, `X-XSS-Protection`, HSTS on `screen-secure`. See `MoquiDefaultConf.xml`.
- Client IP: set `webapp_client_ip_header` for the outer proxy (`X-Real-IP`, `CF-Connecting-IP`, …). Embedded Jetty applies `ForwardedRequestCustomizer` for `X-Forwarded-Proto` / `X-Proxied-Https` so it knows a proxied request was HTTPS; it does not take the client address from `X-Forwarded-For` or `Forwarded`. Used by `/status`, visit tracking, `ipAllowed`, and related logic.
- ResourceFacade `http`/`https` (and other) schemes: outbound fetch from *authorized* server-side code. SSRF-class if a screen or service takes a location URL from the client (Data Import `location=`, ElFinder, `sendResourceResponse`).
- Screen JSON (`Accept: application/json` or `.json`): `currentParameters` / `screenParameters` omit password and other credential field names (`password`, `oldPassword`, `newPassword`, `authPassword`, `api_key`, `login_key`, …).

## HTTP container

- **Embedded Jetty** via `MoquiStart`: default listen **8080**, max threads 100 (`port=`, `threads=`). Session store under `runtime/sessions`.
- **External servlet container**: `framework/src/main/webapp/WEB-INF/web.xml`, context-param `moqui-name=webroot`.
- Session cookie: http-only, SameSite Lax (`web.xml` `cookie-config`), timeout 60 minutes (`session-config` / `webapp.session-config.@timeout`). `Secure` is not set by default; set it on the container or rely on TLS at the edge (same as everything else here).
- Catch-all: any path not claimed by a more specific servlet is a **screen path** under the root screen (`MoquiServlet` `/*`).

## Servlets, filters, WebSocket endpoints

Configured on `webapp-list.webapp` in `framework/src/main/resources/MoquiDefaultConf.xml`. Filters and servlets are registered from that conf (not from a long `web.xml`).

| Surface | Path | Default | Authn | Authz | Considerations |
| --- | --- | --- | --- | --- | --- |
| `MoquiServlet` | `/*` | on | per screen | per screen / transition, inheritable | Main renderer. Hierarchical XML screens + transitions. Also serves **file resources** under a screen directory (js/css/images). Bulk of the HTTP surface. Note: any screen the user can view can usually also be requested with a render-mode extension (`.csv`, `.text`, `.xml`, `.xsl-fo`, and the Vue/Quasar template modes) — the same authz applies, but data dumps of authorized screens are easy to forget in a review. |
| `MoquiFopServlet` | `/fop/*` | on | same as target screen | same screen authz | Renders the remaining path as XSL-FO then PDF (`ResourceFacade.xslFoTransform`). FO transform runs with authz disabled after the screen render. FOP *engine* component is out of scope. |
| ElasticSearch / OpenSearch proxy | `/elastic/*` | on (proxy) | logged in | `UserPermission` `ElasticRemote` (`ADMIN` in seed) | Jetty `ProxyServlet$Transparent` to `elasticsearch_url` (default `http://127.0.0.1:9200`). This is the **full cluster HTTP API** if the permission is granted. Keep OS/ES on a private network regardless. |
| Kibana proxy | `/kibana/*` | on (proxy) | logged in | `KibanaRemote` (`ADMIN` in seed) | Same pattern to `kibana_host:kibana_port`. Backing Kibana process is out of scope. |
| `MoquiAuthFilter` | `/elastic/*`, `/kibana/*` | on | session / Basic / api_key / body | named permission init-param | Used for non-`MoquiServlet` servlets. |
| `ElasticRequestLogFilter` | `/*` | on | — | — | Writes request logs to OpenSearch. Not an entry point. |
| Notification WebSocket | `/notws` | on (`endpoint enabled="true"`) | HTTP session / handshake | none beyond being logged in to receive | `NotificationEndpoint`. Subscribe/unsubscribe topic names. Server pushes only to `NotificationMessage.notifyUserIds` for that user; an unauthenticated upgrade is not registered (`userId == null` is ignored). Topics are not a separate ACL: a logged-in user can subscribe to any topic name and will receive messages **already targeted at them**. |
| Groovy Shell WebSocket | `/groovysh` | on | HTTP session / handshake | `GROOVY_SHELL_WEB` (`ADMIN_ADV`) | `GroovyShellEndpoint`. **RCE by design** in the running JVM (`GroovyShell` with the EC binding). Idle 300s, eval timeout 900s, endpoint timeout 900000ms. Disable or keep off the public internet. |

Override an endpoint in runtime/component conf by merging `webapp.endpoint` on `path`, e.g. `<endpoint path="/groovysh" enabled="false"/>`.

## Screen tree (`MoquiServlet`)

Root screen: `component://webroot/screen/webroot.xml`, `require-authentication="false"` so Login and public transitions can exist; **children decide**. Default subscreen is `qapps`.

`MoquiConf.xml` in the tools component mounts on the `apps` screen:

- **system** → `component://tools/screen/System.xml`
- **tools** → `component://tools/screen/Tools.xml`

`qapps` and `vapps` are thin SPA shell screens with no subscreens of their own; the Quasar/Vuet shells build menu and link paths in the screen-tree context of the `apps` tree.

### Public and weakly authenticated root paths

| Path | Auth | Considerations |
| --- | --- | --- |
| `/` → `/qapps` | `qapps` / `vapps` pre-actions redirect to `/Login` if no user | Three shells: `/qapps` (Quasar, default), `/vapps`, `/apps` (server-rendered). `/apps` does **not** redirect in pre-actions; sub-screens default to requiring auth. |
| `/Login` | none | login (`require-session-token="false"`), logout, reset/change password, MFA `sendOtp`, `createInitialAdminAccount` (only if there is no real `UserAccount` besides `_NA_`). Session pre-auth: `moquiPreAuthcUsername`, `moquiAuthcFactorRequired`. JSON responses omit credential fields from `currentParameters`. |
| `/ChangePassword`, `/SecondFactor` | none / `anonymous-all` | Password-change and MFA; pre-auth session state. |
| `/status` | client IP in `webapp_status_ips` (plus `127.0.0.1` and IPv6 loopback) | JSON process stats from `getStatusMap()`. Sensitive fields (version, OS, datasources) are always omitted here (`includeSensitive` is not exposed via this transition). |
| `/menuData` | login required (401 if no user) | Menu JSON for the SPA shells; follows the target screen path. |
| `/email/{emailMessageId}` | **none** | 1×1 PNG tracking pixel; `disableAuthz` update of `EmailMessage` to `ES_VIEWED`. Unauthenticated state change given a message id (dot suffix stripped). |
| `/robots.txt`, `/favicon.ico` | none | robots.txt disallows `/apps`, `/vapps`, `/qapps`, `/rest`, `/rpc`, `/status`, `/menuData`. |
| `/error/*` | none | Unauthorized, Forbidden, NotFound, TooMany, InternalError. |
| `/echopath` | none | Echoes extra path; standalone, `track-artifact-hit="false"`. Dev-oriented. |
| `/toolstatic` | `anonymous-view` | Swagger UI static files. Schema *data* is under `/rest/*.swagger` (authenticated). |

**Static files under the root screen** (`/js/*`, `/libs/*`, `/css/*`, images) are screen file resources. Root `webroot` is unauthenticated, so those libs are public. That is intended.

`/apps` also exposes authenticated JSON helpers once a user is in that shell: `setPreference`, `getPreferences`, `qzSign` (QZ Tray print signing). CSRF applies to non-GET unless marked otherwise.

### Tools and System (intentionally privileged)

Default seed: `ADMIN` inherit-all on the app root. Extra `UserPermission` gates on the sharpest tools. These are supposed to be powerful; the issue is who is in `ADMIN` / `ADMIN_ADV` and whether the hostname is public.

**Tools** (`/qapps/tools`, also `/apps/tools`, `/vapps/tools`):

| Screen | Extra gate | Why it matters |
| --- | --- | --- |
| Groovy Shell | `GROOVY_SHELL_WEB` + AUTHZA_ALL on the screen; also `/groovysh` WS | RCE in the JVM |
| SQL Runner, SQL Script Runner | `SQL_RUNNER_WEB` + AUTHZA_ALL; SQL from secure (body) parameters | Arbitrary SQL on a datasource |
| Entity Data Import | inherit-all | XML/JSON/CSV/location load — bulk entity writes. Identity-admin and secret-config entities (UserLoginKey, UserAuthcFactor, UserPasswordHistory, UserGroupMember, EmailServer, …) are refused; use System/Security screens or seed data. UserAccount is still allowed here. |
| Entity Data Export, Data Snapshot | inherit-all | Bulk read / dump of entity data. Snapshot download/delete/import/upload take a single path segment under `db/snapshot/`. Import may disable EECAs (form default) so a Tools update user can restore security rows; that is a restore path, not a SYSTEM_APP hole. |
| Auto Screen, Data Edit, Data View | inherit-all | Generic entity CRUD UI. Identity-admin and secret-config entities are refused (VIEW-only inherit is not a dump of HMAC secrets, login keys, or TOTP factors). UserAccount remains available; identity-admin changes go through System/Security. |
| Service Run | inherit-all | Call any authorized service by name |
| Service Load Runner | `SERVICE_LOAD_RUNNER` + AUTHZA_ALL | Load generator against services |
| Artifact Stats, Speed Test | inherit-all | Stats / load; Speed Test is disabled on demo.moqui.org |

**System** (`/qapps/system`, …):

| Screen | Why it matters |
| --- | --- |
| Security (users, groups, artifact groups/authz, MFA factors) | Identity and authz administration |
| Resource / ElFinder | ResourceFacade file manager (browse/upload under a resource root; default `dbresource://mantle/content` preference). `component://` and `file:` roots are browsable; write commands on those roots are refused when `instance_purpose` is production. |
| Service Jobs | Enable, run, and inspect scheduled jobs |
| System Messages | Queue/send/receive/consume; paired with `/rest/sm` |
| Entity Sync | Replication; paired with allow-remote put/get |
| Instances | Multi-instance (Docker) control |
| Data Documents / feeds / index | Document defs and OpenSearch indexing |
| Cache, Log Viewer, Visit, Audit Log, Thread list, Active users | Operational data; Log Viewer queries the OpenSearch log index |
| Print | Network printers and jobs (CUPS component out of scope; screens/services are in these repos) |
| Localization | Message and entity-field l10n |

`ADMIN_PASSWORD` is required to change another user’s password. `ADMIN_LOGIN_AS` (`ADMIN_ADV`) is login-as.

## REST, RPC, and related HTTP APIs

`rest.xml` and `rpc.xml` sit directly under the root screen with `require-authentication="false"` so **transitions** decide. They do **not** grant inheritable ADMIN authz (unlike Tools/System screens). Login for these paths is the UserFacade request init above.

| Path | What | Authn | Authz / other gates | Considerations |
| --- | --- | --- | --- | --- |
| `POST /rest/login` | Session login | username/password (`code` if MFA); **no CSRF token** | — | If MFA is required and no code, JSON factor info; complete with `POST /rest/sendOtp` and `/rest/verifyOtp`. `POST /rest/logout` ends the session. |
| `/rest/e1`, `/rest/m1`, deprecated `/rest/v1` | **Generic entity CRUD** (any entity or master) | required | **entity** artifact authz (`AT_ENTITY`). Tarpit off for entities by default. `rest.xml` does not inherit ADMIN-all | Wide engine; width is the caller’s entity (or inheritable) authz, not “ADMIN can hit Tools.” Bulk JSON list bodies. `X-HTTP-Method-Override` on POST. `dependents` / `master` documents. UserAccount JSON omits password hash fields (same list as Service REST). Identity-admin and secret-config entities are not available here. Do not grant catch-all entity authz to internet users. |
| `/rest/s1/{root}/...` | Declared Service REST (`*.rest.xml`) | per resource (`authenticate` on the resource/method) | `AT_REST_PATH`; seed `MOQUI_API` `/moqui` inherit-all for `ADMIN` | Runtime root **moqui**: artifacts, dataDocuments, basic geo/enum/status/uom, email, print, entity sync, systemMessages, users, wiki. Other components add roots. UserAccount JSON (list/one and nested maps) omits `currentPassword`, `resetPassword`, salt, and hash type. `PATCH` of users identity fields (`disabled`, `username`, `emailAddress`, …) requires the ADMIN group; EmailServer host/port/password writes do too. |
| `/rest/sm/{type}/{remote}/{id?}` | Inbound SystemMessage | `require-session-token="false"`; see auth enum | login + service authz, **or** HMAC then `loginAnonymousIfNoUser`, **or** `SmatNone` (no auth) | Path: `systemMessageTypeId` / `systemMessageRemoteId` / optional `remoteMessageId`. Body is the message. `SmatHmacSha256`: header HMAC-SHA256 of body, Base64. `SmatHmacSha256Timestamp`: Stripe-style `t=` / `v1=`, hex HMAC of `timestamp.body`, **5 minute** window + 10s skew; a repeated `(remote, t, v1)` inside the window is rejected. `SmatNone` is an explicit no-auth remote. Only configured remotes. |
| `/rest/entity.json` `.raml` `.swagger`, `master.*`, `service.swagger` `.raml` | Schema dumps | required | login + `REST_SCHEMA` permission (ADMIN seed) | Model and API shape. |
| `/rpc/json` | JSON-RPC 2.0 | via service parameters / request init | **only services with `allow-remote="true"`**, then service authz | Named params; JSON-RPC batches (array body) run each call through the same allow-remote + authz checks. XML-RPC is gone. Adding `allow-remote` is an exposure decision. |

Framework services with `allow-remote="true"` (not a complete ecosystem list): `org.moqui.impl.BasicServices` find/get helpers (geo, status, enumeration), `UserServices.set#Preference`, `reset#Password`, `EntitySyncServices.put#EntitySyncData` and `get#EntitySyncData`, `SystemMessageServices.receive#IncomingSystemMessage`.

`update#Password` and `reset#Password` are `authenticate="anonymous-all"` (needed for the Login screen). `reset#Password` is also `allow-remote="true"` (untrusted input: username → email). `update#Password` is not `allow-remote`.

## Non-HTTP and optional listeners

| Surface | Default | Bind / trigger | Auth | Considerations |
| --- | --- | --- | --- | --- |
| **SubEtha SMTP** (`SubEthaSmtpToolFactory`) | **disabled** | `MOQUI_LOCAL` EmailServer seed: `localhost:2525`, user `email.root`, password `EMAIL_CHANGEME` | SMTP AUTH: that mail user **or any valid UserAccount** password (`MoquiShiroRealm.checkCredentials`) | Received `MimeMessage` runs **EMECA** rules. Untrusted email is the payload. Change the seed password; do not publish 2525; TLS only if `smtpStartTls=Y`. |
| **H2 TCP server** (`H2ServerToolFactory`) | **on if any H2 datasource** | `-tcpPort 9092 -ifExists -baseDir ${moqui_runtime}/db/h2` (no `-tcpAllowOthers` in the default args) | H2 user/password (`entity_ds_user` / `entity_ds_password`, defaults `sa`/`sa`) | Comment in conf: `jdbc:h2:tcp://localhost:9092/moqui`. Production should not use H2. If it does, do not add `-tcpAllowOthers`; firewall 9092. Disable with `tool-factory.@disabled` or empty `start-server-args`. |
| **Jackrabbit** (`JackrabbitRunToolFactory`) | **disabled** | spawned process; `jackrabbit_moqui.properties` default port **8081** | process-local / Jackrabbit config | JCR repo. Keep off unless needed; do not expose 8081. |
| **IMAP/POP3 poll** (`poll#EmailServer`) | Service job **paused** in seed | outbound client, default every 15 min if unpaused | mailbox credentials on `EmailServer` | Same untrusted email → EMECA path as SubEtha. Input channel, not a listen port. Connect host must be in `email_allowed_hosts` when that list is non-empty. |
| **Outbound SMTP** | if `EmailServer` configured | client | — | `allowedToDomains` on `EmailServer` (recipients). Connect host: `email_allowed_hosts` in `MoquiDefaultConf.xml` (empty = any host). Body from email screens/templates. |
| **OpenSearch / Elasticsearch** | client `http://127.0.0.1:9200`; `MoquiStart` may launch `runtime/opensearch` | cluster HTTP | keep private; `/elastic` is the Moqui-side HTTP exposure |
| **JDBC** | configured datasource | — | never on a public interface |
| **Scheduled jobs** | check every `scheduled_job_check_time` seconds (default 60; `0` disables) | internal | service authz as the job user | Seed jobs: cleanup and `render#ScheduledScreens` **running**; EntitySync all, SystemMessage send/consume, poll email, search delete **paused**. Jobs can call powerful services. |
| **EntitySync** put/get | `allow-remote="true"` | JSON-RPC and `/rest/s1/moqui/entity/syncs/data/*` | `EntitySyncServices` group: `ADMIN` in seed | Replication ingest is a write surface. Identity-admin and secret-config entities are not stored (this is not the identity-admin UI). |
| **Worker thread pool** | on | internal | — | Not a network listener. |

## Heavy lock-down (demo.moqui.org as reference)

A **public demo with known passwords** is a different goal from production (production: no demo data, don’t put Tools on the public hostname). The useful part is the **techniques** for removing write/RCE surface while leaving apps browse-able.

Live example: the [`moqui-demo`](https://github.com/moqui/moqui-demo) component on [demo.moqui.org](https://demo.moqui.org) (`MoquiConf.xml`, `data/MoquiDemoServerData.xml`, `screen/Disabled.xml`). That component is not part of framework/runtime; copy the *ideas*, not the demo users.

### What demo actually does

Framework/runtime already treat mutating transitions as `update`/`all` (not VIEW), so VIEW-only Tools/System **blocks** cache clear, Service Run, instance start/stop, ElFinder commands, and similar even when those screens are still mounted.

The `moqui-demo` component then:

1. **Disables** `/groovysh` (`webapp.endpoint` `enabled="false"`). `/notws` stays on.
2. **Replaces sharp screens** with `Disabled.xml` (`menu-include="false"`): GroovyShell, DataImport, DataExport, DataSnapshot, SpeedTest, SqlRunner, SqlScriptRunner, ServiceRun, ServiceLoadRunner, ElFinder.
3. **Narrows inherit-all admin authz** in demo-type data (same `artifactAuthzId` as seed):
   - `TOOLS_APP_ADMIN`, `SYSTEM_APP_ADMIN`, `MOQUI_API_ADMIN`: `AUTHZA_ALL` → `AUTHZA_VIEW`
   - `EntitySyncServicesADMIN`, `SystemMessageServicesADMIN`: `AUTHZA_ALL` → `AUTHZA_VIEW` (JSON-RPC put/receive)
   - Thru-date `john.doe` out of `ADMIN_ADV`
   - Thru-date `ADMIN` `ElasticRemote` / `KibanaRemote` permissions

Screen override example:

```xml
<screen-facade>
    <screen location="component://tools/screen/Tools.xml">
        <subscreens-item name="GroovyShell" menu-include="false"
                location="component://moqui-demo/screen/Disabled.xml"/>
    </screen>
</screen-facade>
```

Authz overwrite example (same primary key as seed):

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="TOOLS_APP_ADMIN" artifactGroupId="TOOLS_APP"
        userGroupId="ADMIN" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_VIEW"/>
```

### Reusable options (demo uses some)

| Technique | How | What it removes |
| --- | --- | --- |
| Screen override | Component `screen-facade` / `subscreens-item` → stub screen | URL + UI for that tool; WebSocket/API siblings may remain |
| VIEW-only app authz | Overwrite seed `ArtifactAuthz` `AUTHZA_ALL` → `AUTHZA_VIEW` (or add `AUTHZT_DENY`) | Mutations under Tools / System / `MOQUI_API` inherit |
| Strip `ADMIN_ADV` | No `UserGroupMember`, or thru-date it | Permission-gated tools even if the screen is still mounted |
| Disable WS endpoint | Merge `webapp.endpoint` `path="/groovysh"` `enabled="false"` (and `/notws` if unused) | Demo **does** disable `/groovysh`. The endpoint also requires `GROOVY_SHELL_WEB` and AUTHZA_ALL on the Tools app |
| Proxy permissions | Do not grant `ElasticRemote` / `KibanaRemote`; keep OS/Kibana private | Full cluster HTTP API through Moqui |
| Disable tool factories | `tool-factory.@disabled="true"` (SubEtha, H2 server, Jackrabbit) | Extra listen ports |
| Don’t use H2 in production | Postgres/MySQL; no `start-server-args` / disable H2 factory | H2 TCP 9092 |
| Pause / zero jobs | `scheduled_job_check_time=0`; keep poll-email / sync / message jobs paused | Untrusted email, sync, consume |
| Don’t mount Tools | Omit the tools component or its `subscreens-item` | Whole admin UI (usually too coarse except hardened public sites) |
| Stub REST/RPC | Same Disabled.xml idea, or omit transitions you don’t want | Generic entity REST / JSON-RPC (demo leaves these up; VIEW-only `MOQUI_API` still allows GET-ish `/rest/s1/moqui`) |
| Network | WAF/proxy; don’t publish 8080, 9092, 2525, 8081, 9200 | Everything in this file |

### Gaps in the demo recipe (review notes, not a dunk)

- Entity REST `/rest/e1` is **not** the `MOQUI_API` group; it is generic entity authz. VIEW-only Tools/System does not by itself turn off entity CRUD over REST (there is no seed catch-all entity allow, so this is 403 unless an app grants entity inherit). Auto Screen / Data Edit no longer treat inherit as access to identity-admin and secret-config entities (UserLoginKey, UserGroupMember, EmailServer, …); use System/Security screens. UserAccount is still available through Data Edit.
- `/fop/*` and `/notws` remain (needed for PDF and SPA notifications)
- `/elastic/*` servlet remains; demo thru-dates `ElasticRemote` so ADMIN cannot use the proxy
- Demo still loads demo users and passwords; that is the opposite of production data policy

**Production lock-down** is usually: edge WAF + no demo data + no `ADMIN` on internet-facing users + disable unused factories/endpoints + keep Tools off the public hostname.

**Demo lock-down** is: keep the apps usable with a well-known login, but take away RCE, SQL, import, ElFinder, and write-admin.

## How to use this for review

Walk each surface: is it enabled in *this* deployment, reachable from the networks you care about, authenticated, authorized (including inherit), and given untrusted input? Application components add more screens/services/REST roots and need their own pass.

Related:

- [SECURITY.md](SECURITY.md) — policy and reporting
- [SECURITY_TESTS.md](SECURITY_TESTS.md) — proof-test catalog (Spock + Python HTTP)
- [Security](https://www.moqui.org/m/docs/framework/Security) — authn, MFA, artifact authz, CSRF, headers, tarpit
- [Run and Deploy](https://www.moqui.org/m/docs/framework/Run+and+Deploy) — production checklist
- [Web Service](https://www.moqui.org/m/docs/framework/System+Interfaces/Web+Service) — REST and JSON-RPC
- [Sending and Receiving Email](https://www.moqui.org/m/docs/framework/User+Interface/Sending+and+Receiving+Email) — SMTP/IMAP and EMECA
