"""API / REST / RPC proofs (OWASP API Top 10 + A01)."""
from conftest import rest_login, looks_like_authn


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
    rest_login(http, base_url, username, password)
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
    assert r.status_code in (404, 401, 403)


def test_rest_e1_user_account_without_entity_authz_is_not_200(http, base_url, require_server, username, password):
    rest_login(http, base_url, username, password)
    r = http.get(base_url + "/rest/e1/moqui.security.UserAccount", timeout=10)
    # john.doe is ADMIN but entity REST is not Tools inherit; expect 401/403 unless an app granted entity authz
    assert r.status_code in (401, 403, 404) or (r.status_code == 200 and "currentPassword" not in (r.text or ""))


def test_x_http_method_override_without_auth_is_401(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"X-HTTP-Method-Override": "DELETE"},
        json={},
        timeout=10,
    )
    assert r.status_code == 401
