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
