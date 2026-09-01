"""A10 Mishandling of Exceptional Conditions — anonymous error pages."""


def test_unauthorized_error_page_does_not_reflect_raw_html(http, base_url, require_server):
    r = http.get(base_url + "/error/Unauthorized", timeout=10)
    body = r.text or ""
    assert "<script>alert" not in body.lower() or r.status_code != 200


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
