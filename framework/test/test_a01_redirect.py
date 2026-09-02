"""A01 — Login returnTo / Referer must not redirect off-site after login."""
from conftest import require_screen_login


def test_login_returnTo_does_not_redirect_offsite(http, base_url, require_server, username, password):
    evil = "https://evil.example/phish"
    http.get(base_url + "/Login", params={"returnTo": evil}, timeout=10)
    r = require_screen_login(http, base_url, username, password, fetch_login=False)
    loc = (r.headers.get("Location") or "").lower()
    assert r.status_code in (200, 301, 302, 303, 307, 308)
    assert not loc.startswith("https://evil.example")
    assert "evil.example" not in loc


def test_login_referer_does_not_redirect_offsite(http, base_url, require_server, username, password):
    evil = "https://evil.example/phish"
    http.get(base_url + "/Login", headers={"Referer": evil}, timeout=10)
    r = require_screen_login(http, base_url, username, password, fetch_login=False)
    loc = (r.headers.get("Location") or "").lower()
    assert r.status_code in (200, 301, 302, 303, 307, 308)
    assert not loc.startswith("https://evil.example")
    assert "evil.example" not in loc


def test_login_returnTo_does_not_follow_host_header(http, base_url, require_server, username, password):
    evil = "https://evil.example/phish"
    http.get(
        base_url + "/Login",
        params={"returnTo": evil},
        headers={"Host": "evil.example"},
        timeout=10,
    )
    r = http.post(
        base_url + "/Login/login",
        data={"username": username, "password": password},
        headers={"Host": "evil.example"},
        timeout=10,
        allow_redirects=False,
    )
    loc = (r.headers.get("Location") or "").lower()
    assert r.status_code in (301, 302, 303, 307, 308)
    assert "evil.example" not in loc
    assert "/phish" not in loc


def test_login_returnTo_same_origin_path_is_allowed(http, base_url, require_server, username, password):
    http.get(base_url + "/Login", params={"returnTo": "/apps"}, timeout=10)
    r = require_screen_login(http, base_url, username, password, fetch_login=False)
    loc = r.headers.get("Location") or ""
    assert r.status_code in (301, 302, 303, 307, 308)
    assert "evil.example" not in loc.lower()
    assert loc.startswith("/") or "://localhost" in loc or "://127.0.0.1" in loc
    assert "apps" in loc.lower() or loc in ("/", "")
