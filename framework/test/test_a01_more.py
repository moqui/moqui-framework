"""A01 additional HTTP proofs: public paths, Host, FOP, email pixel, path traversal."""
import pytest
from conftest import csrf_token, require_rest_login, require_screen_login, require_sec_user


def test_echopath_extra_path_is_html_escaped(http, base_url, require_server):
    r = http.get(base_url + "/echopath/<script>alert(1)</script>", timeout=10)
    body = r.text or ""
    assert "<script>alert(1)</script>" not in body


def test_fop_without_login_is_not_a_pdf(http, base_url, require_server):
    r = http.get(base_url + "/fop/apps/tools/dashboard", timeout=10, allow_redirects=False)
    ctype = (r.headers.get("Content-Type") or "").lower()
    assert r.status_code in (401, 403, 302, 404) or "pdf" not in ctype
    if r.status_code == 200:
        assert not (r.content[:4] == b"%PDF")


def test_email_tracking_unknown_id_returns_png(http, base_url, require_server):
    r = http.get(base_url + "/email/NOTAREALID.png", timeout=10)
    ctype = (r.headers.get("Content-Type") or "").lower()
    assert r.status_code == 200
    assert "png" in ctype
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"


def test_status_from_loopback_is_json(http, base_url, require_server):
    r = http.get(base_url + "/status", timeout=10)
    # ProductionConf allows 127.0.0.1; empty body if IP rejected
    if r.content:
        assert r.status_code == 200
        assert "utilization" in r.text.lower() or "heap" in r.text.lower() or r.text.strip().startswith("{")


def test_status_x_forwarded_for_does_not_grant_access_from_spoofed_loopback(http, base_url, require_server):
    # Connecting from loopback is already allowed; spoofing a public IP should not be required.
    # If Jetty trusts X-Forwarded-For as the client, a non-allow-list IP would hide /status.
    r = http.get(
        base_url + "/status",
        headers={"X-Forwarded-For": "8.8.8.8"},
        timeout=10,
    )
    # Either still loopback (header ignored) or 200 empty / not JSON. Must not dump extra sensitive keys.
    body = (r.text or "").lower()
    assert "datasources" not in body
    assert "vmvendor" not in body


def test_login_form_action_is_login_path(http, base_url, require_server):
    # Absolute URLs follow request Host when webapp_http_host is empty (operator/proxy).
    r = http.get(base_url + "/Login", timeout=10)
    body = r.text or ""
    assert "Login/login" in body
    assert "javascript:" not in body.lower()


def test_static_path_dotdot_does_not_escape(http, base_url, require_server):
    r = http.get(base_url + "/js/../../Login.xml", timeout=10, allow_redirects=False)
    body = (r.text or "").lower()
    assert "<transition name=\"login\"" not in body
    assert r.status_code != 200 or "require-session-token" not in body


def test_view_only_cannot_run_service_run_over_http(http, base_url, require_server):
    require_sec_user(base_url, "sec.view.only", "SecView1!!")
    r = require_screen_login(http, base_url, "sec.view.only", "SecView1!!")
    tok = csrf_token(r)
    if not tok:
        dash = http.get(base_url + "/apps/tools/dashboard", timeout=10, allow_redirects=False)
        tok = csrf_token(dash)
    if not tok:
        pytest.skip("no CSRF token after VIEW-only login")
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
    assert r2.status_code in (401, 403, 302)
    assert r2.status_code != 200


def test_none_user_cannot_open_tools(http, base_url, require_server):
    require_sec_user(base_url, "sec.none.only", "SecNone1!!")
    require_screen_login(http, base_url, "sec.none.only", "SecNone1!!")
    r2 = http.get(base_url + "/apps/tools/dashboard", timeout=10, allow_redirects=False)
    body = (r2.text or "").lower()
    assert r2.status_code in (401, 403, 302) or "auto screens" not in body


def test_view_only_cannot_open_sql_runner(http, base_url, require_server):
    require_sec_user(base_url, "sec.view.only", "SecView1!!")
    require_screen_login(http, base_url, "sec.view.only", "SecView1!!")
    r2 = http.get(
        base_url + "/apps/tools/Entity/SqlRunner",
        params={"groupName": "transactional", "sql": "SELECT 1"},
        timeout=10,
        allow_redirects=False,
    )
    body = (r2.text or "").lower()
    assert r2.status_code in (401, 403, 302) or "permission" in body or "not authorized" in body


def test_elastic_proxy_without_login_is_401(http, base_url, require_server):
    r = http.get(base_url + "/elastic/", timeout=10, allow_redirects=False)
    assert r.status_code in (401, 403, 302)


def test_kibana_proxy_without_login_is_401(http, base_url, require_server):
    r = http.get(base_url + "/kibana/", timeout=10, allow_redirects=False)
    assert r.status_code in (401, 403, 302)


def test_unauthenticated_data_import_does_not_fetch_location(http, base_url, require_server):
    r = http.post(
        base_url + "/apps/tools/Entity/DataImport/load",
        data={"location": "http://127.0.0.1:9/sec-ssrf"},
        timeout=10,
        allow_redirects=False,
    )
    assert r.status_code in (401, 403, 302)
    assert r.status_code != 200


def test_apps_get_preferences_without_login_is_401(http, base_url, require_server):
    r = http.get(base_url + "/apps/getPreferences", params={"keyRegexp": ".*"}, timeout=10, allow_redirects=False)
    assert r.status_code in (401, 403)
    body = (r.text or "").lower()
    assert "mantle.content.root" not in body
    assert "authorize" not in body or "authentication" in body


def test_apps_qzsign_without_login_is_401(http, base_url, require_server):
    r = http.get(base_url + "/apps/qzSign", params={"message": "hello"}, timeout=10, allow_redirects=False)
    assert r.status_code in (401, 403)
    body = (r.text or "").lower()
    assert "qz-private-key" not in body
    assert r.content[:4] != b"%PDF"


def test_htmlr_anonymous_error_does_not_include_stack(http, base_url, require_server):
    r = http.get(base_url + "/htmlr", timeout=15, allow_redirects=False)
    body = r.text or ""
    assert "at org.moqui" not in body
    assert "Caused by:" not in body
    assert "MalformedURLException" not in body
    assert "NoClassDefFoundError" not in body
    r2 = http.get(base_url + "/htmlr/Login", timeout=15, allow_redirects=False)
    body2 = r2.text or ""
    assert "at org.moqui" not in body2
    assert "Caused by:" not in body2
    assert "NoClassDefFoundError" not in body2


def test_htmlr_tools_without_login_is_not_a_pdf(http, base_url, require_server):
    r = http.get(base_url + "/htmlr/apps/tools/dashboard", timeout=15, allow_redirects=False)
    ctype = (r.headers.get("Content-Type") or "").lower()
    assert r.status_code in (401, 403, 302, 404)
    assert "pdf" not in ctype
    assert r.content[:4] != b"%PDF"


def test_system_message_unknown_remote_is_rejected(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/sm/NotAType/NotARemote",
        data='{"probe":true}',
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        timeout=10,
    )
    assert r.status_code in (400, 401, 403)
    body = (r.text or "").lower()
    assert "not valid" in body or "unauthorized" in body or "authentication required" in body


def test_login_get_with_password_is_rejected(http, base_url, require_server):
    r = http.get(
        base_url + "/Login/login",
        params={"username": "john.doe", "password": "moqui"},
        timeout=10,
        allow_redirects=False,
    )
    body = (r.text or "").lower()
    assert r.status_code != 200 or "non-secure" in body or "url parameters" in body
    assert '"loggedin":true' not in body.replace(" ", "")


def test_web_inf_is_not_served(http, base_url, require_server):
    r = http.get(base_url + "/WEB-INF/web.xml", timeout=10, allow_redirects=False)
    body = (r.text or "").lower()
    assert r.status_code in (404, 403)
    assert "<web-app" not in body


def test_groovysh_http_get_is_not_a_shell(http, base_url, require_server):
    r = http.get(base_url + "/groovysh", timeout=10, allow_redirects=False)
    body = (r.text or "").lower()
    assert "groovyshell" not in body or r.status_code in (400, 401, 403, 404, 405)
    assert r.status_code != 200 or "eval" not in body


def test_sql_runner_query_string_sql_is_not_executed(http, base_url, require_server, username, password):
    # john.doe in demo data is ADMIN_ADV (SQL_RUNNER_WEB). URL sql must not run.
    r = require_rest_login(http, base_url, username, password)
    tok = csrf_token(r)
    probe = "sec_sql_probe_col"
    r_get = http.get(
        base_url + "/apps/tools/Entity/SqlRunner",
        params={"groupName": "transactional", "sql": "SELECT 1 AS " + probe},
        timeout=10,
        allow_redirects=False,
    )
    if r_get.status_code in (401, 403):
        pytest.skip("logged-in user lacks SQL_RUNNER_WEB (need ADMIN_ADV / demo john.doe)")
    get_body = (r_get.text or "").lower()
    # Query sql may appear in the textarea; it must not have been executed as a result set
    assert "result set" not in get_body
    assert "showing all" not in get_body
    if not tok:
        pytest.skip("no CSRF token after login")
    r_post = http.post(
        base_url + "/apps/tools/Entity/SqlRunner",
        data={"groupName": "transactional", "sql": "SELECT 1 AS " + probe, "moquiSessionToken": tok},
        headers={"X-CSRF-Token": tok},
        timeout=10,
        allow_redirects=True,
    )
    post_body = (r_post.text or "").lower()
    assert "session token required" not in post_body
    assert "result set" in post_body or probe.lower() in post_body or "showing all" in post_body


def test_menu_data_tools_without_login_is_not_the_menu(http, base_url, require_server):
    r = http.get(base_url + "/menuData/apps/tools", timeout=10, allow_redirects=False)
    body = (r.text or "").lower()
    assert r.status_code in (401, 403, 302) or "auto screens" not in body
    assert "groovyshell" not in body or r.status_code != 200
