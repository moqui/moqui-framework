"""A05 Injection — HTTP proofs for allow-html and SQL-looking stored values."""
from conftest import rest_login, csrf_token


def test_rpc_set_preference_rejects_html_by_default(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    if r.status_code != 200:
        return
    tok = csrf_token(r)
    r2 = http.post(
        base_url + "/rpc/json",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "org.moqui.impl.UserServices.set#Preference",
            "params": {
                "preferenceKey": "secHtmlHttp",
                "preferenceValue": "<script>alert(1)</script>",
            },
        },
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    body = (r2.text or "").lower()
    assert "html not allowed" in body or r2.status_code in (400, 401, 403, 500)
    assert "<script>alert(1)</script>" not in (r2.text or "")


def test_sql_looking_preference_is_stored_as_data(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    if r.status_code != 200:
        return
    tok = csrf_token(r)
    sqli = "' OR '1'='1"
    r2 = http.post(
        base_url + "/apps/setPreference",
        data={"preferenceKey": "secSqliHttp", "preferenceValue": sqli},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
        allow_redirects=False,
    )
    if r2.status_code in (401, 403):
        return
    r3 = http.get(base_url + "/apps/getPreferences", params={"keyRegexp": "secSqliHttp"}, timeout=10)
    body = r3.text or ""
    assert r3.status_code == 200
    assert sqli in body
    assert "syntax error" not in body.lower()
    assert "sql exception" not in body.lower()
