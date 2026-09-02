"""A10 Mishandling of Exceptional Conditions — anonymous error pages."""


def test_unauthorized_error_page_html_has_no_script_tag(http, base_url, require_server):
    # Direct screen hit has no attacker-controlled message; Spock injects the payload.
    r = http.get(base_url + "/error/Unauthorized", timeout=10, headers={"Accept": "text/html"})
    body = r.text or ""
    assert "<script>" not in body.lower()


def test_rest_unauth_html_error_has_no_script_or_stack(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"Accept": "text/html"},
        timeout=10,
    )
    body = r.text or ""
    assert r.status_code == 401
    assert "<script>" not in body.lower()
    assert "at org.moqui" not in body


def test_internal_error_page_does_not_include_stack_trace_when_hit_directly(http, base_url, require_server):
    r = http.get(base_url + "/error/InternalError", timeout=10)
    body = r.text or ""
    assert "at org.moqui" not in body
    assert "groovy:" not in body.lower() or "at " not in body


def test_rest_unauth_json_does_not_include_stack_frames(http, base_url, require_server):
    r = http.get(
        base_url + "/rest/e1/moqui.basic.Enumeration",
        headers={"Accept": "application/json"},
        timeout=10,
    )
    body = r.text or ""
    assert r.status_code == 401
    assert "at org.moqui.impl" not in body
    assert "Exception" not in body or "stack" not in body.lower()
