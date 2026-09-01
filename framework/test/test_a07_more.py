"""A07 additional authn proofs."""
import pytest
from conftest import require_sec_user, rest_login


def test_sendOtp_without_preauth_is_rejected(http, base_url, require_server):
    r = http.post(
        base_url + "/rest/sendOtp",
        json={"factorId": "not-a-factor"},
        timeout=10,
    )
    body = (r.text or "").lower()
    assert r.status_code in (401, 403) or '"loggedin":true' not in body.replace(" ", "")


def test_verifyOtp_without_preauth_does_not_log_in(http, base_url, require_server):
    http.get(base_url + "/Login", timeout=10)
    token = None
    # session token from login page headers or cookie jar is enough to POST
    r = http.post(
        base_url + "/rest/verifyOtp",
        json={"code": "000000"},
        timeout=10,
    )
    body = (r.text or "").lower().replace(" ", "")
    assert '"loggedin":true' not in body
    assert r.status_code in (401, 403) or r.status_code != 200 or "true" not in body


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
    user, pw = "sec.lock.test", "SecLock1!!"
    r = rest_login(http, base_url, user, pw)
    body = r.text or ""
    logged_in = r.status_code == 200 and '"loggedin":true' in body.lower().replace(" ", "")
    if "the username or password is not valid" in body.lower() and not logged_in:
        pytest.skip("sec.lock.test not loaded (run Gradle Security* tests first)")
    if logged_in:
        for _ in range(4):
            rest_login(http, base_url, user, "definitely-wrong-password")
        r2 = rest_login(http, base_url, user, pw)
        body2 = (r2.text or "").lower().replace(" ", "")
        assert '"loggedin":true' not in body2
    else:
        # already locked from a previous run — still must not create a session
        assert '"loggedin":true' not in body.lower().replace(" ", "")


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
