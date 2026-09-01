"""A07 additional authn proofs."""


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
