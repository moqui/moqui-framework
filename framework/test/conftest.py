"""HTTP proof tests against a running Moqui instance. Not invoked by Gradle."""
import os
import pytest
import requests

DEFAULT_BASE = "http://localhost:8080"


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


@pytest.fixture
def require_server(server_up):
    if not server_up:
        pytest.skip("Moqui is not running at MOQUI_BASE_URL (default http://localhost:8080)")


@pytest.fixture
def http(base_url, require_server):
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
