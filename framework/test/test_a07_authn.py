"""A07 Authentication — HTTP proofs. Uses local demo users; do not point at a real production system."""
from conftest import csrf_token, require_rest_login, require_screen_login, rest_login


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


def test_api_key_header_with_garbage_does_not_authenticate(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"api_key": "not-a-real-key"},
        timeout=10,
    )
    assert r.status_code == 401


def test_login_issues_a_new_session_id(http, base_url, require_server, username, password):
    http.get(base_url + "/Login", timeout=10)
    before = http.cookies.get("JSESSIONID")
    r = require_screen_login(http, base_url, username, password, fetch_login=False)
    after = http.cookies.get("JSESSIONID")
    assert r.status_code in (200, 302, 303)
    assert after
    if before:
        assert after != before
