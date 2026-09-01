"""A01 — Login returnTo / Referer must not redirect off-site after login."""
from conftest import screen_login


def test_login_returnTo_does_not_redirect_offsite(http, base_url, require_server, username, password):
    evil = "https://evil.example/phish"
    http.get(base_url + "/Login", params={"returnTo": evil}, timeout=10)
    r = screen_login(http, base_url, username, password)
    loc = r.headers.get("Location") or ""
    if r.status_code in (301, 302, 303, 307, 308):
        assert not loc.lower().startswith("https://evil.example")
        assert "evil.example" not in loc.lower()


def test_login_referer_does_not_redirect_offsite(http, base_url, require_server, username, password):
    evil = "https://evil.example/phish"
    http.get(base_url + "/Login", headers={"Referer": evil}, timeout=10)
    r = screen_login(http, base_url, username, password)
    loc = r.headers.get("Location") or ""
    if r.status_code in (301, 302, 303, 307, 308):
        assert not loc.lower().startswith("https://evil.example")
        assert "evil.example" not in loc.lower()


def test_login_returnTo_same_origin_path_is_allowed(http, base_url, require_server, username, password):
    http.get(base_url + "/Login", params={"returnTo": "/apps"}, timeout=10)
    r = screen_login(http, base_url, username, password)
    loc = r.headers.get("Location") or ""
    if r.status_code in (301, 302, 303, 307, 308):
        assert "evil.example" not in loc.lower()
        # relative or same-host; not an off-site URL
        assert loc.startswith("/") or "://localhost" in loc or "://127.0.0.1" in loc
