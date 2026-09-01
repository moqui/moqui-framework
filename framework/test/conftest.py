"""HTTP proof tests against a running Moqui instance. Not invoked by Gradle.

Requires MoquiProductionConf.xml. DevConf (CORS *, tarpit off, /h2 console) must fail.
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


def screen_login(http, base_url, username, password, extra=None):
    """Login via /Login/login (no CSRF). extra is extra form fields (e.g. returnTo)."""
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


def looks_like_authn(resp):
    if resp.status_code in (401, 403):
        return True
    loc = (resp.headers.get("Location") or "").lower()
    if resp.status_code in (301, 302, 303, 307, 308) and "login" in loc:
        return True
    body = (resp.text or "").lower()
    return "login" in body or "authentication required" in body or "unauthorized" in body
