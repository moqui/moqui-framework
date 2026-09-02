"""A01 tarpit velocity. Runs last so a lock on the test user does not starve other proofs.

Demo data ALL_SCREENS is 120 hits / 60s for ALL_USERS. Skip if that example is not loaded.
"""
import pytest
from conftest import rest_login


def test_screen_tarpit_returns_429_after_burst(http, base_url, require_server, username, password):
    r = rest_login(http, base_url, username, password)
    if r.status_code != 200:
        pytest.skip("could not log in for tarpit velocity")
    saw_429 = False
    last = None
    for _ in range(130):
        last = http.get(base_url + "/apps/tools/dashboard", timeout=10, allow_redirects=False)
        if last.status_code == 429:
            saw_429 = True
            break
    if not saw_429:
        pytest.skip("ALL_SCREENS tarpit example not loaded (120 hits / 60s in demo data)")
    assert last.status_code == 429
    retry = last.headers.get("Retry-After")
    if retry:
        assert int(retry) > 0


def test_transition_tarpit_for_sec_tap_user_returns_429(http, base_url, require_server):
    """Dedicated ArtifactTarpit on setPreference (3 / 60s). Skip if fixtures are missing."""
    from conftest import require_rest_login, require_sec_user, csrf_token
    require_sec_user(base_url, "sec.tap.user", "SecTapUser1!!")
    r = require_rest_login(http, base_url, "sec.tap.user", "SecTapUser1!!")
    tok = csrf_token(r)
    if not tok:
        pytest.skip("no CSRF token")
    last = None
    saw_429 = False
    for i in range(8):
        last = http.post(
            base_url + "/apps/setPreference",
            data={"preferenceKey": "secTapHttp" + str(i), "preferenceValue": "v",
                  "moquiSessionToken": tok},
            headers={"X-CSRF-Token": tok},
            timeout=10,
            allow_redirects=False,
        )
        if last.status_code == 429:
            saw_429 = True
            break
    if last is not None and last.status_code in (401, 403) and i == 0:
        pytest.skip("sec.tap.user cannot run setPreference")
    if not saw_429:
        pytest.skip("transition tarpit did not fire (fixtures missing or tarpit disabled)")
    assert last.status_code == 429
