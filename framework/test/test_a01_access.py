"""A01 Broken Access Control — HTTP proofs."""


def test_tools_without_session_is_not_the_tool(http, base_url, require_server):
    r = http.get(base_url + "/apps/tools/dashboard", timeout=10, allow_redirects=False)
    # login redirect, 401, or Login HTML — not the Tools dashboard
    if r.status_code in (301, 302, 303, 307, 308):
        loc = (r.headers.get("Location") or "").lower()
        assert "login" in loc or loc.endswith("/login")
        return
    body = r.text.lower()
    assert r.status_code in (200, 401) or "login" in body
    assert "auto screens" not in body


def test_rest_e1_without_credentials_is_401(http, base_url, require_server):
    r = http.get(base_url + "/rest/e1/moqui.basic.Enumeration", timeout=10)
    assert r.status_code == 401


def test_removed_rest_api_key_is_not_found(http, base_url, require_server):
    r = http.get(base_url + "/rest/api_key", timeout=10)
    assert r.status_code in (404, 401, 403)


def test_removed_rest_moqui_session_token_is_not_found(http, base_url, require_server):
    r = http.get(base_url + "/rest/moquiSessionToken", timeout=10)
    assert r.status_code in (404, 401, 403)
