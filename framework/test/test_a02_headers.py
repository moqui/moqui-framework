"""A02 Security Misconfiguration — HTTP headers, cookies, CORS, H2 console.

ProductionConf: unknown Origin is 401; no H2 console. DevConf is expected to fail these.
"""
from conftest import EVIL_ORIGIN


def test_html_response_has_csp_and_frame_options(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    csp = r.headers.get("Content-Security-Policy") or ""
    xfo = r.headers.get("X-Frame-Options") or ""
    xcto = r.headers.get("X-Content-Type-Options") or ""
    assert "frame-ancestors" in csp.lower() or csp
    assert xfo
    assert "nosniff" in xcto.lower()


def test_session_cookie_is_httponly(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    raw = r.headers.get("Set-Cookie") or ""
    if not raw:
        r2 = http.get(base_url + "/", timeout=10)
        raw = r2.headers.get("Set-Cookie") or ""
    assert "httponly" in raw.lower()


def test_cors_no_origin_header_has_no_allow_origin(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    assert not r.headers.get("Access-Control-Allow-Origin")


def test_cors_unknown_origin_is_401(http, base_url, require_server):
    r = http.get(base_url + "/Login", headers={"Origin": EVIL_ORIGIN}, timeout=10)
    assert r.status_code == 401
    assert not r.headers.get("Access-Control-Allow-Origin")


def test_h2_console_is_not_mounted(http, base_url, require_server):
    r = http.get(base_url + "/h2/", timeout=10, allow_redirects=False)
    body = (r.text or "").lower()
    # DevConf mounts org.h2.server.web.JakartaWebServlet at /h2/*
    assert "h2 console" not in body
    assert "login.jsp" not in body
    assert r.status_code != 200 or "h2" not in body
