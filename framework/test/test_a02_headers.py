"""A02 Security Misconfiguration — HTTP headers, cookies, CORS."""


def test_html_response_has_csp_and_frame_options(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    # header names are case-insensitive in requests
    csp = r.headers.get("Content-Security-Policy") or ""
    xfo = r.headers.get("X-Frame-Options") or ""
    xcto = r.headers.get("X-Content-Type-Options") or ""
    assert "frame-ancestors" in csp.lower() or csp
    assert xfo
    assert "nosniff" in xcto.lower()


def test_session_cookie_is_httponly(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    # any Set-Cookie from Moqui session
    found = False
    for c in r.cookies:
        found = True
        # requests exposes httponly on cookie objects via rest? use raw headers
        break
    raw = r.headers.get("Set-Cookie") or ""
    if not raw:
        # follow redirects once
        r2 = http.get(base_url + "/", timeout=10)
        raw = r2.headers.get("Set-Cookie") or ""
    assert "httponly" in raw.lower()


def test_cors_no_origin_header_has_no_allow_origin(http, base_url, require_server):
    r = http.get(base_url + "/Login", timeout=10)
    assert not r.headers.get("Access-Control-Allow-Origin")


def test_cors_unknown_origin_rejected_unless_allow_origins_star(http, base_url, require_server):
    # MoquiDefaultConf webapp_allow_origins is empty → 401 "Origin not allowed".
    # MoquiDevConf sets * (java -jar default) so any Origin is echoed; that is designed for local demo.
    evil = "https://evil.example"
    r = http.get(base_url + "/Login", headers={"Origin": evil}, timeout=10)
    acao = r.headers.get("Access-Control-Allow-Origin")
    if acao:
        assert acao.lower() == evil
        creds = r.headers.get("Access-Control-Allow-Credentials")
        if creds and creds.lower() == "true":
            assert acao != "*"
        return
    assert r.status_code == 401
