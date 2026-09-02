"""A07 Authentication — HTTP proofs. Uses local demo users; do not point at a real production system."""
import uuid
from conftest import csrf_token, require_rest_login, require_screen_login, rest_login


def _blank_preference(http, base_url, tok, key):
    if not tok:
        return
    http.post(
        base_url + "/apps/setPreference",
        data={"preferenceKey": key, "preferenceValue": "", "moquiSessionToken": tok},
        headers={"X-CSRF-Token": tok},
        timeout=10,
        allow_redirects=False,
    )


def test_rest_login_succeeds_without_csrf_token(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    assert r.status_code == 200
    body = r.text.lower()
    # loggedIn true, or MFA factor payload — not a CSRF error
    assert "token required" not in body
    assert "csrf" not in body


def test_post_without_csrf_token_rejected_after_login(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    # Service Run is a mutating transition; missing CSRF must not run it
    r2 = http.post(
        base_url + "/apps/tools/Service/ServiceRun/run",
        data={"serviceName": "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"},
        timeout=10,
        allow_redirects=False,
    )
    assert r2.status_code in (401, 403, 302)
    assert r2.status_code != 200


def test_post_with_csrf_token_is_not_a_csrf_error(http, base_url, require_server, username, password):
    r = require_rest_login(http, base_url, username, password)
    tok = csrf_token(r)
    assert tok, "login response did not include a session token header"
    r2 = http.post(
        base_url + "/apps/tools/Service/ServiceRun/run",
        data={
            "serviceName": "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown",
            "moquiSessionToken": tok,
        },
        headers={"X-CSRF-Token": tok},
        timeout=10,
        allow_redirects=False,
    )
    body = (r2.text or "").lower()
    assert "session token required" not in body
    assert "token does not match" not in body


def test_api_key_in_query_string_does_not_authenticate(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        params={"api_key": "not-a-real-key"},
        timeout=10,
    )
    assert r.status_code == 401


def test_post_with_csrf_token_executes_preference_round_trip(http, base_url, require_server, username, password):
    """Positive control: the same token that passes CSRF must also run the transition."""
    r = require_rest_login(http, base_url, username, password)
    tok = csrf_token(r)
    assert tok, "login response did not include a session token header"
    key, value = "secCsrfPosHttp-" + uuid.uuid4().hex, "csrf-positive-ran"
    try:
        r2 = http.post(
            base_url + "/apps/setPreference",
            data={"preferenceKey": key, "preferenceValue": value, "moquiSessionToken": tok},
            headers={"X-CSRF-Token": tok},
            timeout=10,
            allow_redirects=False,
        )
        body = (r2.text or "").lower()
        assert r2.status_code == 200
        assert "session token required" not in body
        assert "token does not match" not in body
        r3 = http.get(base_url + "/apps/getPreferences", params={"keyRegexp": key}, timeout=10)
        assert r3.status_code == 200
        assert value in (r3.text or ""), "preference round-trip failed after CSRF-positive POST"
    finally:
        _blank_preference(http, base_url, tok, key)


def test_post_without_csrf_does_not_store_preference(http, base_url, require_server, username, password):
    r = require_rest_login(http, base_url, username, password)
    tok = csrf_token(r)
    key, value = "secCsrfNegHttp-" + uuid.uuid4().hex, "must-not-store"
    try:
        r2 = http.post(
            base_url + "/apps/setPreference",
            data={"preferenceKey": key, "preferenceValue": value},
            timeout=10,
            allow_redirects=False,
        )
        body = (r2.text or "").lower()
        assert r2.status_code in (401, 403)
        assert "session token required" in body or "token does not match" in body
        r3 = http.get(base_url + "/apps/getPreferences", params={"keyRegexp": key}, timeout=10)
        assert r3.status_code == 200
        assert value not in (r3.text or "")
    finally:
        _blank_preference(http, base_url, tok, key)


def test_api_key_header_with_garbage_does_not_authenticate(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"api_key": "not-a-real-key"},
        timeout=10,
    )
    assert r.status_code == 401


SEC_KEY_PLAINTEXT = "sec-test-login-key-fixed-40-chars-value"


def test_api_key_header_from_getLoginKey_authenticates(http, base_url, require_server):
    from conftest import require_sec_user
    require_sec_user(base_url, "sec.key.http", "SecKeyHttp1!!")
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"api_key": SEC_KEY_PLAINTEXT},
        timeout=10,
    )
    assert r.status_code == 200


def test_login_key_header_authenticates(http, base_url, require_server):
    from conftest import require_sec_user
    require_sec_user(base_url, "sec.key.http", "SecKeyHttp1!!")
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"login_key": SEC_KEY_PLAINTEXT},
        timeout=10,
    )
    assert r.status_code == 200


def test_api_key_in_json_body_authenticates(http, base_url, require_server):
    from conftest import require_sec_user
    require_sec_user(base_url, "sec.key.http", "SecKeyHttp1!!")
    r = http.post(
        base_url + "/rpc/json",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "org.moqui.impl.BasicServices.find#Enumeration",
            "params": {"enumTypeId": "moqui.basic.EnumerationType"},
            "api_key": SEC_KEY_PLAINTEXT,
        },
        timeout=10,
    )
    # api_key is a top-level JSON field so initFromHttpRequest sees it (not nested under params).
    assert r.status_code != 401
    body = (r.text or "").lower()
    assert "login key not valid" not in body


def test_http_basic_on_screen_is_not_unauthenticated(http, base_url, require_server, username, password):
    r = http.get(
        base_url + "/menuData/apps",
        auth=(username, password),
        timeout=10,
        allow_redirects=False,
    )
    assert r.status_code != 401
    assert not (r.status_code in (302, 303) and "login" in (r.headers.get("Location") or "").lower())


def test_login_issues_a_new_session_id(http, base_url, require_server, username, password):
    http.get(base_url + "/Login", timeout=10)
    before = http.cookies.get("JSESSIONID")
    r = require_screen_login(http, base_url, username, password, fetch_login=False)
    after = http.cookies.get("JSESSIONID")
    assert r.status_code in (200, 302, 303)
    assert after
    if before:
        assert after != before
