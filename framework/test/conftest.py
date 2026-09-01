"""HTTP proof tests against a running Moqui instance. Not invoked by Gradle.

Requires MoquiProductionConf.xml. DevConf (CORS *, tarpit off, /h2 console) must fail.

Users named sec.* (sec.view.only, sec.all.only, sec.none.only, sec.lock.test,
sec.ent.view, sec.ent.all, sec.api.view, sec.api.all, sec.es.view, sec.es.all,
sec.ip.v4, sec.ip.loop)
are created by the Gradle Security* Spock tests (SecurityTestSupport.ensureUsers).
They are not demo seed. If they are missing, tests skip rather than pass.
"""
import os
import pytest
import requests

DEFAULT_BASE = "http://localhost:8080"
EVIL_ORIGIN = "https://evil.example"
PROD_FAIL_MSG = (
    "HTTP security proofs require MoquiProductionConf.xml "
    "(unknown Origin must be 401 with no Access-Control-Allow-Origin). "
    "Start with: java -jar moqui.war conf=conf/MoquiProductionConf.xml"
)
SEC_USERS_HINT = "sec.* user not loaded (run Gradle :framework:test Security* first)"


def pytest_configure(config):
    config.addinivalue_line("markers", "http: tests that need a running Moqui server")


@pytest.fixture(scope="session")
def base_url():
    return os.environ.get("MOQUI_BASE_URL", DEFAULT_BASE).rstrip("/")


@pytest.fixture(scope="session")
def server_up(base_url):
    try:
        r = requests.get(base_url + "/", timeout=3, allow_redirects=False)
        return r.status_code < 500
    except requests.RequestException:
        return False


@pytest.fixture(scope="session")
def require_production_conf(base_url, server_up):
    if not server_up:
        pytest.skip("Moqui is not running at MOQUI_BASE_URL (default http://localhost:8080)")
    r = requests.get(
        base_url + "/Login",
        headers={"Origin": EVIL_ORIGIN},
        timeout=10,
    )
    acao = r.headers.get("Access-Control-Allow-Origin")
    if r.status_code != 401 or acao:
        pytest.fail(PROD_FAIL_MSG)


@pytest.fixture
def require_server(require_production_conf):
    return require_production_conf


@pytest.fixture
def http(base_url, require_production_conf):
    s = requests.Session()
    s.headers.update({"User-Agent": "moqui-security-proof-tests"})
    return s


@pytest.fixture
def username():
    return os.environ.get("MOQUI_TEST_USERNAME", "john.doe")


@pytest.fixture
def password():
    return os.environ.get("MOQUI_TEST_PASSWORD", "moqui")


def rest_login(http, base_url, username, password):
    r = http.post(
        base_url + "/rest/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    return r


def logged_in_json(resp):
    body = (resp.text or "").lower().replace(" ", "")
    return resp.status_code == 200 and '"loggedin":true' in body


def csrf_token(resp):
    return resp.headers.get("X-CSRF-Token") or resp.headers.get("moquiSessionToken")


def screen_login(http, base_url, username, password, extra=None, fetch_login=True):
    """Login via /Login/login (no CSRF). extra is extra form fields (e.g. returnTo).

    fetch_login=False when the caller already GET /Login (so returnTo / last-path stay set).
    """
    if fetch_login:
        http.get(base_url + "/Login", timeout=10)
    data = {"username": username, "password": password}
    if extra:
        data.update(extra)
    return http.post(
        base_url + "/Login/login",
        data=data,
        timeout=10,
        allow_redirects=False,
    )


def require_rest_login(http, base_url, username, password):
    """REST login with loggedIn true, or skip. Never a silent pass."""
    r = rest_login(http, base_url, username, password)
    if logged_in_json(r):
        return r
    body = (r.text or "").lower()
    if "factor" in body:
        pytest.skip(f"rest-login as {username} requires MFA; not used in these proofs")
    pytest.skip(f"could not rest-login as {username} (status {r.status_code})")


def require_screen_login(http, base_url, username, password, extra=None, fetch_login=True):
    r = screen_login(http, base_url, username, password, extra=extra, fetch_login=fetch_login)
    if r.status_code not in (200, 302, 303):
        pytest.skip(f"could not screen-login as {username} (status {r.status_code})")
    return r


def require_sec_user(base_url, username, password):
    """Gradle SecurityTestSupport users. Skip if that suite has not been run on this DB.

    Uses a throwaway session so the test client is not left logged in.
    """
    probe = requests.Session()
    r = rest_login(probe, base_url, username, password)
    body = (r.text or "").lower()
    if "the username or password is not valid" in body:
        pytest.skip(f"{username}: {SEC_USERS_HINT}")
    if r.status_code not in (200, 401, 403):
        pytest.skip(f"{username}: unexpected login status {r.status_code}")
    return r


def looks_like_authn(resp):
    if resp.status_code in (401, 403):
        return True
    loc = (resp.headers.get("Location") or "").lower()
    if resp.status_code in (301, 302, 303, 307, 308) and "login" in loc:
        return True
    body = (resp.text or "").lower()
    return "login" in body or "authentication required" in body or "unauthorized" in body
