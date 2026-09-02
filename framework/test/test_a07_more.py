"""A07 additional authn proofs."""
import pytest
from conftest import csrf_token, logged_in_json, require_sec_fixtures, require_sec_user, rest_login

MFA_USERNAME = "sec.mfa.totp"
MFA_PASSWORD = "SecMfaTotp1!!"
MFA_CODE = "87654321"


def test_rest_login_wrong_password_is_401(http, base_url, require_server, username):
    r = http.post(
        base_url + "/rest/login",
        json={"username": username, "password": "definitely-wrong-password"},
        timeout=10,
    )
    assert r.status_code == 401
    body = (r.text or "").lower()
    assert "username or password is not valid" in body
    assert '"loggedin":true' not in body.replace(" ", "")


def test_rest_login_unknown_user_is_401(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/login",
        json={"username": "sec.no.such.user.zzz", "password": "wrong"},
        timeout=10,
    )
    assert r.status_code == 401
    body = (r.text or "").lower()
    assert "username or password is not valid" in body


def test_sendOtp_without_preauth_is_rejected(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/sendOtp",
        json={"factorId": "not-a-factor"},
        timeout=10,
    )
    body = (r.text or "").lower()
    assert r.status_code in (401, 403) or '"loggedin":true' not in body.replace(" ", "")


def test_verifyOtp_without_preauth_does_not_log_in(http, base_url, require_server):
    r0 = http.get(base_url + "/Login", timeout=10)
    tok = csrf_token(r0)
    r = http.post(
        base_url + "/rest/verifyOtp",
        json={"code": "000000"},
        headers={"X-CSRF-Token": tok} if tok else {},
        timeout=10,
    )
    body = (r.text or "").lower()
    assert not logged_in_json(r)
    assert r.status_code in (401, 403) or "pre-auth" in body or "not valid" in body


def test_mfa_password_only_does_not_fully_log_in(http, base_url, require_server):
    require_sec_user(base_url, MFA_USERNAME, MFA_PASSWORD)
    r = rest_login(http, base_url, MFA_USERNAME, MFA_PASSWORD)
    assert r.status_code == 200
    assert not logged_in_json(r)
    body = (r.text or "").lower()
    assert "factor" in body or "authc" in body


def test_mfa_login_with_wrong_code_does_not_log_in(http, base_url, require_server):
    """Run before the success test: a valid single-use code is consumed on verify."""
    require_sec_user(base_url, MFA_USERNAME, MFA_PASSWORD)
    r = http.post(
        base_url + "/rest/login",
        json={"username": MFA_USERNAME, "password": MFA_PASSWORD, "code": "000000"},
        timeout=10,
    )
    assert not logged_in_json(r)


def test_mfa_login_with_code_succeeds(http, base_url, require_server):
    require_sec_user(base_url, MFA_USERNAME, MFA_PASSWORD)
    r = rest_login(http, base_url, MFA_USERNAME, MFA_PASSWORD)
    if r.status_code != 200:
        pytest.skip("MFA fixture not loaded")
    r2 = http.post(
        base_url + "/rest/login",
        json={"username": MFA_USERNAME, "password": MFA_PASSWORD, "code": MFA_CODE},
        timeout=10,
    )
    assert logged_in_json(r2)


def test_create_initial_admin_http_fails_when_users_exist(http, base_url, require_server):
    r = http.post(
        base_url + "/Login/createInitialAdminAccount",
        data={
            "username": "sec.should.not.exist",
            "newPassword": "SecAdmin1!!",
            "newPasswordVerify": "SecAdmin1!!",
        },
        timeout=10,
        allow_redirects=False,
    )
    body = (r.text or "").lower()
    assert r.status_code != 200 or "can only create" in body or "error" in body or r.status_code in (401, 403, 302)


def test_short_password_is_rejected_over_http(http, base_url, require_server):
    require_sec_user(base_url, "sec.all.only", "SecAll1!!")
    r0 = http.get(base_url + "/Login", timeout=10)
    token = r0.headers.get("X-CSRF-Token") or r0.headers.get("moquiSessionToken")
    data = {
        "username": "sec.all.only",
        "oldPassword": "SecAll1!!",
        "newPassword": "short",
        "newPasswordVerify": "short",
    }
    if token:
        data["moquiSessionToken"] = token
    r = http.post(
        base_url + "/Login/changePassword",
        data=data,
        timeout=10,
        allow_redirects=True,
    )
    body = (r.text or "").lower()
    assert "found issues with password" in body or "shorter" in body or "not updating" in body
    assert r.status_code != 302 or "login" in (r.headers.get("Location") or "").lower()


def test_failed_logins_lock_dedicated_user(http, base_url, require_server):
    # Dedicated lock user from SecurityTestSupport; never john.doe.
    # Disabled accounts use the same public text as unknown users, so existence is
    # the other sec.* fixtures (created in the same ensureUsers call).
    user, pw = "sec.lock.test", "SecLock1!!"
    r = rest_login(http, base_url, user, pw)
    body = r.text or ""
    logged_in = logged_in_json(r)
    if logged_in:
        for _ in range(4):
            rest_login(http, base_url, user, "definitely-wrong-password")
        r2 = rest_login(http, base_url, user, pw)
        assert not logged_in_json(r2)
    else:
        require_sec_fixtures(base_url)
        # already locked from a previous run — still must not create a session
        assert not logged_in_json(r)


def test_login_json_response_omits_password_parameters(http, base_url, require_server):
    """Screen JSON (Accept: application/json) must not echo the submitted password."""
    user, pw = "sec.none.only", "SecNone1!!"
    r0 = http.get(base_url + "/Login", timeout=10)
    token = r0.headers.get("X-CSRF-Token") or r0.headers.get("moquiSessionToken")
    data = {"username": user, "password": pw}
    if token:
        data["moquiSessionToken"] = token
    r = http.post(
        base_url + "/Login/login",
        data=data,
        headers={"Accept": "application/json"},
        timeout=10,
        allow_redirects=False,
    )
    body = r.text or ""
    if r.status_code in (401, 403) or "not valid" in body.lower():
        pytest.skip("sec.none.only not loaded (run Gradle Security* tests first)")
    assert pw not in body
    if "application/json" in (r.headers.get("Content-Type") or ""):
        data = r.json()
        params = data.get("currentParameters") or {}
        assert "password" not in params
        assert "password" not in (data.get("screenParameters") or {})


def test_login_json_failed_omits_password_parameters(http, base_url, require_server):
    r0 = http.get(base_url + "/Login", timeout=10)
    token = r0.headers.get("X-CSRF-Token") or r0.headers.get("moquiSessionToken")
    planted = "SecShouldNotEcho1!!"
    data = {"username": "sec.no.such.user.zzz", "password": planted}
    if token:
        data["moquiSessionToken"] = token
    r = http.post(
        base_url + "/Login/login",
        data=data,
        headers={"Accept": "application/json"},
        timeout=10,
        allow_redirects=False,
    )
    body = r.text or ""
    assert planted not in body
    if "application/json" in (r.headers.get("Content-Type") or ""):
        data = r.json()
        params = data.get("currentParameters") or {}
        assert "password" not in params
        assert planted not in str(data)


def test_login_unknown_and_wrong_password_share_public_text(http, base_url, require_server):
    common = "The username or password is not valid"
    def post_login(name, pw):
        s = http
        return s.post(
            base_url + "/Login/login",
            data={"username": name, "password": pw},
            timeout=10,
            allow_redirects=True,
        )
    u = post_login("sec.no.such.user.zzz", "wrong")
    w = post_login("john.doe", "definitely-wrong-password")
    ub, wb = (u.text or ""), (w.text or "")
    assert common.lower() in ub.lower()
    assert common.lower() in wb.lower()
    for body in (ub, wb):
        low = body.lower()
        assert "no account found" not in low
        assert "password incorrect" not in low
        assert "[distmp]" not in low
        assert "[disprm]" not in low
    ru = http.post(
        base_url + "/rest/login",
        json={"username": "sec.no.such.user.zzz", "password": "wrong"},
        timeout=10,
    )
    rw = http.post(
        base_url + "/rest/login",
        json={"username": "john.doe", "password": "definitely-wrong-password"},
        timeout=10,
    )
    for body in ((ru.text or ""), (rw.text or "")):
        low = body.lower()
        assert common.lower() in low
        assert "no account found" not in low
        assert "password incorrect" not in low
        assert '"loggedin":true' not in low.replace(" ", "")


def test_password_reset_unknown_user_shows_shared_message(http, base_url, require_server):
    common = "If an account exists for that username or email, a reset password was sent"
    r0 = http.get(base_url + "/Login", timeout=10)
    token = r0.headers.get("X-CSRF-Token") or r0.headers.get("moquiSessionToken")
    data = {"username": "sec.no.such.user.zzz", "initialTab": "reset"}
    if token:
        data["moquiSessionToken"] = token
    r = http.post(base_url + "/Login/resetPassword", data=data, timeout=10, allow_redirects=True)
    body = (r.text or "").lower()
    assert common.lower() in body
    assert "could not find account" not in body
    assert "does not have an email address" not in body


def test_password_reset_unknown_and_existing_share_public_text(http, base_url, require_server):
    common = "If an account exists for that username or email, a reset password was sent"
    # session token is created on the first GET /Login and returned in that response's headers
    r0 = http.get(base_url + "/Login", timeout=10)
    token = r0.headers.get("X-CSRF-Token") or r0.headers.get("moquiSessionToken")
    def post_reset(name):
        data = {"username": name, "initialTab": "reset"}
        if token:
            data["moquiSessionToken"] = token
        return http.post(
            base_url + "/Login/resetPassword",
            data=data,
            timeout=10,
            allow_redirects=True,
        )
    u = post_reset("sec.no.such.user.zzz")
    e = post_reset("john.doe")
    ub, eb = (u.text or ""), (e.text or "")
    assert common.lower() in ub.lower()
    assert common.lower() in eb.lower()
    # neither should contain the old distinguishing phrases
    for body in (ub, eb):
        low = body.lower()
        assert "could not find account" not in low
        assert "does not have an email address" not in low


def test_ip_restricted_user_cannot_login_from_loopback(http, base_url, require_server):
    # sec.ip.v4 is only allowed from 10.99.99.99; login from this client is supposed
    # to fail with the same public text as a missing user, so probe another fixture.
    require_sec_fixtures(base_url)
    r = rest_login(http, base_url, "sec.ip.v4", "SecIpV41!!")
    assert not logged_in_json(r)
    r2 = http.post(
        base_url + "/rest/login",
        json={"username": "sec.ip.v4", "password": "SecIpV41!!"},
        headers={"X-Forwarded-For": "10.99.99.99"},
        timeout=10,
    )
    assert not logged_in_json(r2)
    r3 = http.post(
        base_url + "/rest/login",
        json={"username": "sec.ip.v4", "password": "SecIpV41!!"},
        headers={"X-Forwarded-For": "::1"},
        timeout=10,
    )
    assert not logged_in_json(r3)


def test_loopback_ip_allowed_user_can_login(http, base_url, require_server):
    require_sec_fixtures(base_url)
    r = rest_login(http, base_url, "sec.ip.loop", "SecIpLoop1!!")
    assert logged_in_json(r)
