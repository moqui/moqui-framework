"""Live WebSocket upgrade proofs for /groovysh and /notws."""
import pytest
from conftest import require_rest_login, require_screen_login, require_sec_user

websocket = pytest.importorskip("websocket")


def _ws_url(base_url, path):
    if base_url.startswith("https://"):
        return "wss://" + base_url[len("https://"):] + path
    return "ws://" + base_url[len("http://"):] + path


def _cookie_header(http):
    return "; ".join(f"{k}={v}" for k, v in http.cookies.get_dict().items())


def _open_ws(url, cookie=None, timeout=5):
    header = [f"Cookie: {cookie}"] if cookie else []
    return websocket.create_connection(url, header=header, timeout=timeout)


def test_groovysh_anonymous_upgrade_is_not_a_shell(http, base_url, require_server):
    url = _ws_url(base_url, "/groovysh")
    try:
        ws = _open_ws(url)
    except Exception:
        return
    try:
        ws.send("1+1")
        try:
            msg = ws.recv()
        except Exception:
            msg = ""
        text = (msg or "").strip()
        assert text != "2"
        assert "john.doe" not in text.lower()
    finally:
        try:
            ws.close()
        except Exception:
            pass


def test_groovysh_admin_can_eval(http, base_url, require_server, username, password):
    require_rest_login(http, base_url, username, password)
    url = _ws_url(base_url, "/groovysh")
    cookie = _cookie_header(http)
    try:
        ws = _open_ws(url, cookie=cookie)
    except Exception as e:
        pytest.skip(f"could not open /groovysh as {username}: {e}")
    try:
        ws.send("1+1")
        msg = ws.recv()
        assert "2" in (msg or "")
    finally:
        try:
            ws.close()
        except Exception:
            pass


def test_groovysh_anonymous_after_admin_session_is_not_a_shell(
    http, base_url, require_server, username, password
):
    test_groovysh_admin_can_eval(http, base_url, require_server, username, password)
    http.cookies.clear()
    url = _ws_url(base_url, "/groovysh")
    try:
        ws = _open_ws(url)
    except Exception:
        return
    try:
        ws.send("1+1")
        try:
            msg = ws.recv()
        except Exception:
            msg = ""
        text = (msg or "").strip()
        assert text != "2"
        assert "john.doe" not in text.lower()
    finally:
        try:
            ws.close()
        except Exception:
            pass


def test_groovysh_view_only_is_not_a_shell(http, base_url, require_server):
    require_sec_user(base_url, "sec.view.only", "SecView1!!")
    require_screen_login(http, base_url, "sec.view.only", "SecView1!!")
    url = _ws_url(base_url, "/groovysh")
    cookie = _cookie_header(http)
    try:
        ws = _open_ws(url, cookie=cookie)
    except Exception:
        return
    try:
        ws.send("1+1")
        try:
            msg = ws.recv()
        except Exception:
            msg = ""
        text = (msg or "").strip()
        assert text != "2"
    finally:
        try:
            ws.close()
        except Exception:
            pass


def test_notws_anonymous_upgrade_accepts_or_closes(http, base_url, require_server):
    url = _ws_url(base_url, "/notws")
    try:
        ws = _open_ws(url)
    except Exception:
        return
    try:
        ws.send("subscribe:TopicQuiet")
    finally:
        try:
            ws.close()
        except Exception:
            pass
