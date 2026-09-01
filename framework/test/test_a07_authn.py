"""A07 Authentication — HTTP proofs. Uses dev/demo users; do not run against production."""
from conftest import rest_login


def test_rest_login_succeeds_without_csrf_token(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    assert r.status_code == 200
    body = r.text.lower()
    # loggedIn true, or MFA factor payload — not a CSRF error
    assert "token required" not in body
    assert "csrf" not in body


def test_post_without_csrf_token_rejected_after_login(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    if r.status_code != 200:
        return
    # Service Run is a mutating transition; missing CSRF must not run it
    r2 = http.post(
        base_url + "/apps/tools/Service/ServiceRun/run",
        data={"serviceName": "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"},
        timeout=10,
        allow_redirects=False,
    )
    assert r2.status_code in (401, 403, 302)
    assert r2.status_code != 200


def test_api_key_in_query_string_does_not_authenticate(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        params={"api_key": "not-a-real-key"},
        timeout=10,
    )
    assert r.status_code == 401
