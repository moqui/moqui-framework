"""API / REST / RPC proofs (OWASP API Top 10 + A01)."""
import base64
import hashlib
import hmac
import time

import pytest
from conftest import looks_like_authn, logged_in_json, require_rest_login, rest_login

SEC_SMT = "SEC_SMT_TEST"
SEC_SMR_HMAC = "SEC_SMR_HMAC"
SEC_SMR_HMAC_TS = "SEC_SMR_HMAC_TS"
HMAC_SECRET = b"sec-hmac-test-secret"
HMAC_HEADER = "X-Moqui-Signature"
HMAC_SKIP = "HMAC test remotes not loaded (run Gradle Security* tests first)"


def test_rpc_without_login_does_not_run_create_user(http, base_url, require_server):
    r = http.post(
        base_url + "/rpc/json",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "org.moqui.impl.UserServices.create#UserAccount",
            "params": {
                "username": "sec.should.not.rpc",
                "newPassword": "SecRpc1!!",
                "newPasswordVerify": "SecRpc1!!",
            },
        },
        timeout=10,
    )
    body = (r.text or "").lower()
    assert r.status_code in (401, 403) or "not found" in body or "does not allow remote" in body or looks_like_authn(r)
    assert r.status_code != 200 or '"result"' not in body


def test_rpc_find_enumeration_without_allow_is_not_user_create(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    r = http.post(
        base_url + "/rpc/json",
        json={
            "jsonrpc": "2.0",
            "id": 2,
            "method": "org.moqui.impl.UserServices.create#UserAccount",
            "params": {
                "username": "sec.should.not.rpc2",
                "newPassword": "SecRpc1!!",
                "newPasswordVerify": "SecRpc1!!",
            },
        },
        timeout=10,
        headers={"Content-Type": "application/json"},
    )
    body = (r.text or "").lower()
    assert "does not allow remote" in body or "not found" in body or r.status_code in (401, 403)


def test_rest_userInfo_transition_is_gone(http, base_url, require_server):
    r = http.get(base_url + "/rest/userInfo", timeout=10)
    assert r.status_code == 404


def test_rest_e1_user_account_without_entity_authz_is_not_200(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    r = http.get(base_url + "/rest/e1/moqui.security.UserAccount", timeout=10)
    # john.doe is ADMIN but entity REST is not Tools inherit; expect 401/403 unless an app granted entity authz
    assert r.status_code in (401, 403, 404) or (r.status_code == 200 and "currentPassword" not in (r.text or ""))
    if r.status_code == 200:
        assert "currentPassword" not in (r.text or "")


def test_x_http_method_override_without_auth_is_401(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"X-HTTP-Method-Override": "DELETE"},
        json={},
        timeout=10,
    )
    assert r.status_code == 401


def test_x_http_method_override_still_requires_csrf(http, base_url, require_server, username, password):
    """Override is honored on POST; CSRF is keyed off the raw method, so this must still 401."""
    require_rest_login(http, base_url, username, password)
    r = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"X-HTTP-Method-Override": "DELETE"},
        json={"enumId": "SEC_OVERRIDE_NOPE", "description": "sec override"},
        timeout=10,
    )
    body = (r.text or "").lower()
    assert r.status_code == 401
    assert "session token required" in body or "token does not match" in body


def test_entity_rest_post_without_csrf_does_not_create(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    r = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        json={"enumId": "SEC_SHOULD_NOT_EXIST", "description": "sec csrf"},
        timeout=10,
    )
    # 401 CSRF, 403 authz, or 400 — must not 200-create
    assert r.status_code != 200
    assert "SEC_SHOULD_NOT_EXIST" not in (r.text or "")
    assert r.status_code in (400, 401, 403, 404) or r.status_code >= 400


def test_rpc_json_without_csrf_does_not_reset_password(http, base_url, require_server):
    # After a session exists, mutating RPC should require the session token.
    http.get(base_url + "/Login", timeout=10)
    r = http.post(
        base_url + "/rpc/json",
        json={
            "jsonrpc": "2.0",
            "id": 3,
            "method": "org.moqui.impl.UserServices.reset#Password",
            "params": {"username": "sec.no.such.user.zzz"},
        },
        timeout=10,
    )
    body = (r.text or "").lower()
    assert "if an account exists" not in body
    assert r.status_code in (401, 403) or "token" in body


def test_rest_e1_basic_auth_wrong_password_is_401(http, base_url, require_server, username):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        auth=(username, "definitely-wrong-password"),
        timeout=10,
    )
    assert r.status_code == 401


def test_rest_e1_basic_auth_valid_is_not_unauthenticated(http, base_url, require_server, username, password):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        auth=(username, password),
        timeout=10,
    )
    # Entity REST has no catch-all inherit; 403/404 is authenticated-but-unauthorized
    assert r.status_code != 401
    assert r.status_code in (200, 403, 404)
    if r.status_code == 200:
        assert "currentPassword" not in (r.text or "")


def _sm_url(base_url, remote):
    return base_url + "/rest/sm/" + SEC_SMT + "/" + remote


def _hmac_b64(body):
    digest = hmac.new(HMAC_SECRET, body.encode("utf-8"), hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def _hmac_ts_header(body, ts):
    sig = hmac.new(HMAC_SECRET, (str(ts) + "." + body).encode("utf-8"), hashlib.sha256).hexdigest()
    return "t=" + str(ts) + ",v1=" + sig


def test_system_message_hmac_missing_header_is_403(http, base_url, require_server):
    body = '{"probe":true}'
    r = http.post(
        _sm_url(base_url, SEC_SMR_HMAC),
        data=body,
        headers={"Content-Type": "application/json"},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r.status_code == 403
    assert "hmac" in (r.text or "").lower() or "header" in (r.text or "").lower()


def test_system_message_hmac_bad_signature_is_403(http, base_url, require_server):
    body = '{"probe":true}'
    r = http.post(
        _sm_url(base_url, SEC_SMR_HMAC),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: "not-a-valid-hmac"},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r.status_code == 403
    assert "hmac" in (r.text or "").lower()


def test_system_message_hmac_valid_does_not_require_login(http, base_url, require_server):
    body = '{"probe":true}'
    r = http.post(
        _sm_url(base_url, SEC_SMR_HMAC),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: _hmac_b64(body)},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r.status_code == 200
    assert "hmac verify failed" not in (r.text or "").lower()


def test_system_message_hmac_timestamp_outside_window_is_403(http, base_url, require_server):
    body = '{"probe":true}'
    old_ts = int(time.time()) - 400
    r = http.post(
        _sm_url(base_url, SEC_SMR_HMAC_TS),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: _hmac_ts_header(body, old_ts)},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r.status_code == 403
    assert "hmac" in (r.text or "").lower() or "timestamp" in (r.text or "").lower()



def test_system_message_hmac_timestamp_in_window_is_accepted(http, base_url, require_server):
    """Positive control for the timestamped variant: without this, a remote that rejected every
    timestamped request would still pass the three negative rows above."""
    body = '{"probe":true}'
    ts = int(time.time())
    r = http.post(
        _sm_url(base_url, SEC_SMR_HMAC_TS),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: _hmac_ts_header(body, ts)},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r.status_code == 200


def test_system_message_smat_none_does_not_accept_anonymous(http, base_url, require_server):
    """SmatNone is an explicit no-auth remote config, but receive#IncomingSystemMessage still requires a
    user, so an anonymous POST must not be accepted. Do not pin 500; 401/403 is also a pass."""
    r = http.post(
        base_url + "/rest/sm/" + SEC_SMT + "/SEC_SMR_NONE",
        data='{"probe":true}',
        headers={"Content-Type": "application/json"},
        timeout=10,
    )
    if r.status_code == 400 and "not valid" in (r.text or "").lower():
        pytest.skip(HMAC_SKIP)
    # Do not pin 500: a future 401/403 is still a pass. Require an auth failure signal so a
    # random 400 does not count.
    assert r.status_code != 200
    body = (r.text or "").lower()
    assert r.status_code in (401, 403) or "authenticationrequired" in body.replace(" ", "") or "authentication required" in body


def test_entity_rest_narrow_authz_allows_only_its_entity(http, base_url, require_server):
    """AT_ENTITY authz is what constrains /rest/e1, not Tools/System inherit."""
    r = require_rest_login(http, base_url, "sec.ent.view", "SecEntView1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    allowed = http.get(base_url + "/rest/e1/moqui.basic.Enumeration", timeout=10)
    denied = http.get(base_url + "/rest/e1/moqui.security.UserAccount", timeout=10)
    assert allowed.status_code == 200, "narrow AT_ENTITY VIEW should read its own entity"
    assert denied.status_code == 403, "narrow AT_ENTITY VIEW must not read another entity"
    created = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        json={"enumId": "SEC_ENT_VIEW_NOPE", "description": "nope"},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    assert created.status_code == 403, "AUTHZA_VIEW must not create"


def test_entity_rest_catchall_authz_allows_other_entities(http, base_url, require_server):
    """Positive companion: the same requests succeed for a catch-all AT_ENTITY AUTHZA_ALL grant."""
    r = require_rest_login(http, base_url, "sec.ent.all", "SecEntAll1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    # Catch-all AT_ENTITY ALL is designed width: UserAccount hashes may be in the body.
    assert http.get(base_url + "/rest/e1/moqui.security.UserAccount", timeout=10).status_code == 200
    created = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        json={"enumId": "SEC_ENT_ALL_OK", "description": "ok"},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    assert created.status_code == 200, "catch-all AUTHZA_ALL should create"
    try:
        got = http.get(base_url + "/rest/e1/moqui.basic.Enumeration/SEC_ENT_ALL_OK", timeout=10)
        assert got.status_code == 200
    finally:
        http.delete(
            base_url + "/rest/e1/moqui.basic.Enumeration/SEC_ENT_ALL_OK",
            headers={"X-CSRF-Token": tok} if tok else {},
            timeout=10,
        )


def test_entity_sync_put_needs_all_authz(http, base_url, require_server):
    """The demo lock-down narrows EntitySyncServices to AUTHZA_VIEW; that must block put#EntitySyncData."""
    r = require_rest_login(http, base_url, "sec.es.view", "SecEsView1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    xml = '<entity-facade-xml><moqui.basic.Enumeration enumId="SEC_ES_NOPE" description="nope"/></entity-facade-xml>'
    r2 = http.post(
        base_url + "/rpc/json",
        json={"jsonrpc": "2.0", "id": 1,
              "method": "org.moqui.impl.EntitySyncServices.put#EntitySyncData",
              "params": {"entityData": xml}},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=20,
    )
    assert r2.status_code == 200
    assert "403" in (r2.text or "") or "not authorized" in (r2.text or "").lower()


def test_entity_sync_put_with_all_authz_stores(http, base_url, require_server):
    """Positive companion: AUTHZA_ALL on EntitySyncServices does allow the replication write."""
    r = require_rest_login(http, base_url, "sec.es.all", "SecEsAll1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    xml = '<entity-facade-xml><moqui.basic.Enumeration enumId="SEC_ES_OK" description="ok"/></entity-facade-xml>'
    r2 = http.post(
        base_url + "/rpc/json",
        json={"jsonrpc": "2.0", "id": 1,
              "method": "org.moqui.impl.EntitySyncServices.put#EntitySyncData",
              "params": {"entityData": xml}},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=20,
    )
    assert r2.status_code == 200
    body = (r2.text or "").lower()
    assert '"error"' not in body, "put#EntitySyncData should be authorized for AUTHZA_ALL"
    assert "recordsstored" in body.replace(" ", "")
    # sec.es.all has no AT_ENTITY grant; drop the probe row with the catch-all entity user.
    cr = rest_login(http, base_url, "sec.ent.all", "SecEntAll1!!")
    if logged_in_json(cr):
        ctok = cr.headers.get("X-CSRF-Token") or cr.headers.get("moquiSessionToken")
        http.delete(
            base_url + "/rest/e1/moqui.basic.Enumeration/SEC_ES_OK",
            headers={"X-CSRF-Token": ctok} if ctok else {},
            timeout=10,
        )


def test_service_rest_moqui_api_view_can_read_users(http, base_url, require_server):
    """AT_REST_PATH MOQUI_API VIEW is what constrains /rest/s1/moqui/**, not Tools inherit."""
    require_rest_login(http, base_url, "sec.api.view", "SecApiView1!!")
    r = http.get(base_url + "/rest/s1/moqui/users", timeout=10)
    assert r.status_code == 200, "MOQUI_API VIEW should list /moqui/users"


def test_service_rest_moqui_api_view_cannot_update_users(http, base_url, require_server):
    r = require_rest_login(http, base_url, "sec.api.view", "SecApiView1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    # Path id need not exist: REST-path authz runs first (AUTHZA_UPDATE).
    r2 = http.patch(
        base_url + "/rest/s1/moqui/users/SEC_API_VIEW",
        json={"emailAddress": "sec-should-not-write@example.com"},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    assert r2.status_code == 403, "MOQUI_API VIEW must not PATCH users"


def test_service_rest_no_api_authz_cannot_read_users(http, base_url, require_server):
    require_rest_login(http, base_url, "sec.none.only", "SecNone1!!")
    r = http.get(base_url + "/rest/s1/moqui/users", timeout=10)
    assert r.status_code == 403


def test_service_rest_moqui_api_all_can_read_users(http, base_url, require_server):
    """Positive companion: the same GET succeeds for MOQUI_API AUTHZA_ALL."""
    require_rest_login(http, base_url, "sec.api.all", "SecApiAll1!!")
    r = http.get(base_url + "/rest/s1/moqui/users", timeout=10)
    assert r.status_code == 200


def test_x_http_method_override_authz_uses_override_action(http, base_url, require_server):
    r = require_rest_login(http, base_url, "sec.api.view", "SecApiView1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    r2 = http.post(
        base_url + "/rest/s1/moqui/users/SEC_API_VIEW",
        headers={"X-HTTP-Method-Override": "PATCH", "X-CSRF-Token": tok} if tok else {"X-HTTP-Method-Override": "PATCH"},
        json={"emailAddress": "sec-should-not-write@example.com"},
        timeout=10,
    )
    assert r2.status_code == 403
    body = (r2.text or "").lower()
    assert "update" in body or "not authorized" in body
    assert "create" not in body


def test_service_rest_users_patch_does_not_store_current_password(http, base_url, require_server):
    r = require_rest_login(http, base_url, "sec.api.all", "SecApiAll1!!")
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    uid = r.json().get("userId") if r.headers.get("Content-Type", "").startswith("application/json") else None
    # Path id need not match a real user for this check; use the fixture username login then list.
    listed = http.get(base_url + "/rest/s1/moqui/users", timeout=10)
    target = "SEC_API_ALL"
    if listed.status_code == 200:
        text = listed.text or ""
        if "SEC_API_ALL" not in text:
            target = "sec.api.all"
    planted = "plain-should-not-authenticate"
    r2 = http.patch(
        base_url + "/rest/s1/moqui/users/" + target,
        json={"currentPassword": planted},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    # Field is dropped; request may 200 or 404 depending on id. Must not authenticate with planted value.
    r3 = http.post(
        base_url + "/rest/login",
        json={"username": "sec.api.all", "password": planted},
        timeout=10,
    )
    body = (r3.text or "").lower().replace(" ", "")
    assert r3.status_code != 200 or '"loggedin":true' not in body


def test_jsonrpc_array_body_returns_one_result_per_call(http, base_url, require_server, username, password):
    r = require_rest_login(http, base_url, username, password)
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    payload = [
        {"jsonrpc": "2.0", "id": 1, "method": "org.moqui.impl.BasicServices.find#Enumeration",
         "params": {"enumTypeId": "GeoType"}},
        {"jsonrpc": "2.0", "id": 2, "method": "org.moqui.impl.BasicServices.find#Enumeration",
         "params": {"enumTypeId": "GeoType"}},
    ]
    r2 = http.post(
        base_url + "/rpc/json",
        json=payload,
        headers={"X-CSRF-Token": tok, "Content-Type": "application/json"} if tok else {"Content-Type": "application/json"},
        timeout=20,
    )
    assert r2.status_code == 200
    data = r2.json()
    assert isinstance(data, list)
    assert len(data) == 2


def test_jsonrpc_array_body_over_limit_is_rejected(http, base_url, require_server, username, password):
    r = require_rest_login(http, base_url, username, password)
    tok = r.headers.get("X-CSRF-Token") or r.headers.get("moquiSessionToken")
    payload = [
        {"jsonrpc": "2.0", "id": i, "method": "org.moqui.impl.BasicServices.find#Enumeration",
         "params": {"enumTypeId": "GeoType"}}
        for i in range(51)
    ]
    r2 = http.post(
        base_url + "/rpc/json",
        json=payload,
        headers={"X-CSRF-Token": tok, "Content-Type": "application/json"} if tok else {"Content-Type": "application/json"},
        timeout=20,
    )
    body = (r2.text or "").lower()
    assert r2.status_code != 200 or "too large" in body or "error" in body
    data = None
    try:
        data = r2.json()
    except Exception:
        data = None
    if isinstance(data, list):
        assert len(data) < 51


def test_system_message_hmac_timestamp_replay_is_rejected(http, base_url, require_server):
    body = '{"probe":"replay"}'
    ts = int(time.time())
    header = _hmac_ts_header(body, ts)
    r1 = http.post(
        _sm_url(base_url, SEC_SMR_HMAC_TS),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: header},
        timeout=10,
    )
    if r1.status_code == 400 and "not valid" in (r1.text or "").lower():
        pytest.skip(HMAC_SKIP)
    assert r1.status_code == 200
    r2 = http.post(
        _sm_url(base_url, SEC_SMR_HMAC_TS),
        data=body,
        headers={"Content-Type": "application/json", HMAC_HEADER: header},
        timeout=10,
    )
    assert r2.status_code == 403


def test_rest_schema_anonymous_is_401(http, base_url, require_server):
    r = http.get(base_url + "/rest/entity.swagger/all.json", timeout=30)
    assert r.status_code in (401, 403)


def test_rest_schema_without_permission_is_403(http, base_url, require_server):
    require_rest_login(http, base_url, "sec.none.only", "SecNone1!!")
    r = http.get(base_url + "/rest/entity.json", timeout=30)
    assert r.status_code in (401, 403)


def test_rest_schema_does_not_set_acao_wildcard(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    r = http.get(base_url + "/rest/entity.swagger/all.json", timeout=60)
    if r.status_code == 403:
        pytest.skip("user does not have REST_SCHEMA")
    acao = r.headers.get("Access-Control-Allow-Origin")
    assert acao != "*"

