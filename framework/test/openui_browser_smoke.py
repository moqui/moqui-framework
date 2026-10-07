#!/usr/bin/env python3
"""Headless browser smoke for Assist OpenUI widgets against a running Moqui.

Uses john.doe / moqui on DevConf. Does not talk to the LLM: injects lang onto
the Assist canvas via the Vue instance (applyYield).

  python3 framework/test/openui_browser_smoke.py
  MOQUI_BASE_URL=http://127.0.0.1:8080 python3 framework/test/openui_browser_smoke.py
"""
from __future__ import print_function

import json
import os
import sys
import time
import traceback

from selenium import webdriver
from selenium.webdriver.chrome.options import Options as ChromeOptions
from selenium.webdriver.common.by import By
from selenium.webdriver.firefox.options import Options as FirefoxOptions
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

BASE = os.environ.get("MOQUI_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USER = os.environ.get("MOQUI_TEST_USERNAME", "john.doe")
PASSWORD = os.environ.get("MOQUI_TEST_PASSWORD", "moqui")
SHOT_DIR = os.environ.get(
    "OPENUI_SHOT_DIR",
    os.path.join(os.path.dirname(__file__), "../../runtime/tmp/openui-browser"),
)
WAIT = 25


def make_driver():
    last = None
    chrome = ChromeOptions()
    chrome.add_argument("--headless=new")
    chrome.add_argument("--no-sandbox")
    chrome.add_argument("--disable-dev-shm-usage")
    chrome.add_argument("--window-size=1400,1000")
    chrome.set_capability("goog:loggingPrefs", {"browser": "ALL"})
    try:
        d = webdriver.Chrome(options=chrome)
        d.set_page_load_timeout(60)
        return d, "chrome"
    except Exception as e:
        last = e
    ff = FirefoxOptions()
    ff.add_argument("-headless")
    ff.set_preference("devtools.console.stdout.content", True)
    try:
        d = webdriver.Firefox(options=ff)
        d.set_window_size(1400, 1000)
        d.set_page_load_timeout(60)
        return d, "firefox"
    except Exception as e:
        raise RuntimeError("neither Chrome nor Firefox started: %s / %s" % (last, e))


def console_errors(driver):
    out = []
    try:
        for e in driver.get_log("browser"):
            lv = (e.get("level") or "").upper()
            msg = e.get("message") or ""
            if lv in ("SEVERE", "ERROR"):
                out.append(lv + " " + msg)
    except Exception:
        pass
    return out


def js(driver, script, *args):
    return driver.execute_script(script, *args)


def wait_js(driver, expr, timeout=WAIT, msg=None):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = js(driver, "return (" + expr + ");")
            if last:
                return last
        except Exception as e:
            last = str(e)
        time.sleep(0.2)
    raise TimeoutError(msg or ("wait_js failed: " + expr + " last=" + repr(last)))


def login(driver):
    driver.get(BASE + "/Login")
    WebDriverWait(driver, WAIT).until(EC.presence_of_element_located((By.ID, "login_form_username")))
    driver.find_element(By.ID, "login_form_username").clear()
    driver.find_element(By.ID, "login_form_username").send_keys(USER)
    driver.find_element(By.CSS_SELECTOR, "#login_form input[name='password']").send_keys(PASSWORD)
    driver.find_element(By.CSS_SELECTOR, "#login_form button[type='submit']").click()
    WebDriverWait(driver, WAIT).until(
        lambda d: "/Login" not in d.current_url or "assist-root" in d.page_source
    )
    if "/Login" in driver.current_url:
        raise RuntimeError("still on Login after submit: " + driver.current_url)


def goto_assist(driver):
    driver.get(BASE + "/qapps/assist")
    wait_js(
        driver,
        "!!document.querySelector('.assist-root')",
        msg="Assist Vue root did not mount at /qapps/assist",
    )
    wait_js(
        driver,
        "!!(window.OpenUILang && window.AssistOpenUiLibrary && window.AssistOpenUiNav && window.AssistOpenUiReady)",
        timeout=40,
        msg="OpenUI runtime (lang-core / library / AssistOpenUi) did not load",
    )
    end = time.time() + 20
    while time.time() < end:
        if js(driver, FIND_ASSIST.replace("return findYield(root, 0);", "return !!findYield(root, 0);")):
            break
        time.sleep(0.2)
    else:
        raise TimeoutError("Assist applyYield not found on Vue tree")


FIND_ASSIST = r"""
function findYield(vm, depth) {
    if (!vm || depth > 20) return null;
    if (typeof vm.applyYield === 'function') return vm;
    var ch = vm.$children || [];
    for (var i = 0; i < ch.length; i++) {
        var f = findYield(ch[i], depth + 1);
        if (f) return f;
    }
    return null;
}
var root = (window.moqui && moqui.webrootVue) || (document.querySelector('#apps-root') && document.querySelector('#apps-root').__vue__);
return findYield(root, 0);
"""

APPLY = r"""
var lang = arguments[0];
var title = arguments[1] || 'OpenUI smoke';
function findYield(vm, depth) {
    if (!vm || depth > 20) return null;
    if (typeof vm.applyYield === 'function') return vm;
    var ch = vm.$children || [];
    for (var i = 0; i < ch.length; i++) {
        var f = findYield(ch[i], depth + 1);
        if (f) return f;
    }
    return null;
}
var root = (window.moqui && moqui.webrootVue) || null;
var vm = findYield(root, 0);
if (!vm) return 'no-applyYield';
vm.applyYield({ kind: 'openui', title: title, lang: lang });
return 'ok';
"""


def apply_lang(driver, lang, title):
    rc = js(driver, APPLY, lang, title)
    if rc != "ok":
        raise RuntimeError("applyYield: " + str(rc))
    wait_js(
        driver,
        "!!document.querySelector('.assist-openui')",
        msg="assist-openui did not appear",
    )
    # finish loading / parse
    end = time.time() + WAIT
    last = None
    while time.time() < end:
        last = js(
            driver,
            """
            var el = document.querySelector('.assist-openui');
            if (!el) return {t:'missing'};
            var t = el.innerText || '';
            if (t.indexOf('Loading OpenUI') >= 0) return {t:'loading', text:t};
            var vm = el.__vue__;
            var errs = (vm && vm.parseErrors) || (vm && vm.$parent && vm.$parent.parseErrors) || [];
            // climb to assist-openui component
            while (vm && vm.$options && vm.$options.name !== 'assist-openui') vm = vm.$parent;
            if (vm && vm.parseErrors) errs = vm.parseErrors;
            return {t:'ready', text:t, loadError: vm && vm.loadError, errs: errs, html: el.innerHTML.slice(0, 4000)};
            """,
        )
        if last and last.get("t") == "ready" and not last.get("loadError"):
            return last
        time.sleep(0.2)
    raise TimeoutError("OpenUI canvas not ready: " + json.dumps(last, default=str)[:800])


def shot(driver, name):
    os.makedirs(SHOT_DIR, exist_ok=True)
    path = os.path.abspath(os.path.join(SHOT_DIR, name + ".png"))
    driver.save_screenshot(path)
    return path


def check(name, cond, detail=""):
    if cond:
        print("PASS  " + name)
        return True
    print("FAIL  " + name + (("  " + detail) if detail else ""))
    return False


CASES = []


def case_basic():
    return {
        "id": "basic",
        "title": "Basic stack",
        "lang": 'root = Stack([hdr, body])\nhdr = CardHeader("Smoke header", "subtitle")\nbody = TextContent("plain body text")\n',
        "check": lambda d, info: (
            check("basic renders title", "Smoke header" in (info.get("text") or "")),
            check("basic renders body", "plain body text" in (info.get("text") or "")),
            check("basic no unknown component", "Unknown component" not in (info.get("text") or "")),
        ),
    }


def case_link():
    lang = """root = Stack([good, apps, bad, trav])
good = Link("Find open orders", "/qapps/popc/Order/FindOrder", {statusId: "OrderOpen"}, "Summary")
apps = Link("via apps", "/apps/popc/Order/FindOrder")
bad = Link("evil", "https://evil.example/x")
trav = Link("trav", "/qapps/../etc/passwd")
"""
    def chk(d, info):
        data = js(
            d,
            """
            var as = Array.prototype.slice.call(document.querySelectorAll('.assist-openui a'));
            return as.map(function(a){
              return {text:a.textContent, href:a.getAttribute('href'), target:a.getAttribute('target'),
                      rel:a.getAttribute('rel')};
            });
            """,
        )
        good = [a for a in data if a.get("text") == "Find open orders"]
        apps = [a for a in data if a.get("text") == "via apps"]
        ok = []
        ok.append(check("link valid present", len(good) == 1, json.dumps(data)))
        if good:
            href = good[0]["href"] or ""
            ok.append(check("link href has path", "/qapps/popc/Order/FindOrder" in href, href))
            ok.append(check("link href has param", "statusId=OrderOpen" in href, href))
            ok.append(check("link href has hash", href.endswith("#Summary") or "#Summary" in href, href))
            ok.append(check("link target blank", good[0].get("target") == "_blank"))
            ok.append(check("link rel noopener", "noopener" in (good[0].get("rel") or "")))
        ok.append(check("link /apps rewritten to qapps", bool(apps) and "/qapps/" in (apps[0].get("href") or ""), json.dumps(apps)))
        text = info.get("text") or ""
        hrefs = " ".join((a.get("href") or "") for a in data)
        ok.append(check("link rejects host URL", "evil.example" not in hrefs and "path must start with /" in text, text[:300]))
        ok.append(check("link rejects ..", "/etc/passwd" not in hrefs and ".." in text, json.dumps(data)))
        before = len(d.window_handles)
        js(
            d,
            """
            var a = document.querySelector('.assist-openui a[target="_blank"]');
            if (a) { a.click(); return a.href; }
            return null;
            """,
        )
        end = time.time() + 5
        while time.time() < end and len(d.window_handles) <= before:
            time.sleep(0.2)
        opened = len(d.window_handles) > before
        if opened:
            d.switch_to.window(d.window_handles[-1])
            url = d.current_url
            d.close()
            d.switch_to.window(d.window_handles[0])
            ok.append(check("link click opens new tab", "/qapps/" in url, url))
        else:
            ok.append(check("link click opens new tab", False, "no extra window (headless popup?)"))
        return ok
    return {"id": "link", "title": "Link", "lang": lang, "check": chk}


def case_charts():
    lang = """root = Stack([bar, pie], "row", "m", true)
bar = BarChart(["A", "B", "C"], [Series("N", [1, 4, 2])])
pie = PieChart(["Open", "Closed"], [3, 1], "donut")
"""
    def chk(d, info):
        # Chart.js from /libs
        end = time.time() + 20
        n = 0
        chart_ok = False
        while time.time() < end:
            n = js(d, "return document.querySelectorAll('.assist-openui canvas').length;")
            chart_ok = js(d, "return !!(window.Chart);")
            if n >= 2 and chart_ok:
                break
            time.sleep(0.3)
        text = info.get("text") or ""
        failed = "failed to load" in text.lower()
        oks = [
            check("charts two canvases", n >= 2, "canvases=%s Chart=%s text=%s" % (n, chart_ok, text[:200])),
            check("charts Chart.js loaded", chart_ok or failed, "libs/Chart.js missing?" if not chart_ok else "ok"),
        ]
        if failed:
            oks.append(check("charts reported load error (cdn)", True, text[:200]))
        return oks
    return {"id": "charts", "title": "Charts", "lang": lang, "check": chk}


def case_markdown():
    lang = """root = Stack([md])
md = MarkDownRenderer("# Hello md\\n\\n<script>alert(1)</script>\\n\\n**bold**\\n\\n[ok](/qapps/assist)\\n")
"""
    def chk(d, info):
        end = time.time() + 20
        html = ""
        while time.time() < end:
            html = js(d, "var el=document.querySelector('.assist-openui .openui-md'); return el ? el.innerHTML : '';") or ""
            if html.strip():
                break
            time.sleep(0.3)
        oks = [
            check("markdown rendered", "<h1" in html.lower() or "Hello md" in html, html[:300]),
            check("markdown XSS stripped", "<script" not in html.lower(), html[:300]),
            check("markdown bold", "<strong>" in html.lower() or "<b>" in html.lower(), html[:300]),
        ]
        return oks
    return {"id": "markdown", "title": "Markdown", "lang": lang, "check": chk}


def case_mermaid():
    lang = """root = Stack([m])
m = Mermaid("stateDiagram-v2\\n    [*] --> Open\\n    Open --> Closed")
"""
    def chk(d, info):
        end = time.time() + 20
        html = ""
        while time.time() < end:
            html = js(d, "var el=document.querySelector('.assist-openui'); return el ? el.innerHTML : '';") or ""
            if "<svg" in html.lower() or "mermaid" in html.lower():
                break
            time.sleep(0.3)
        has_svg = "<svg" in html.lower()
        return [
            check("mermaid rendered svg", has_svg, html[:400]),
        ]
    return {"id": "mermaid", "title": "Mermaid", "lang": lang, "check": chk}


def case_layout_forms():
    lang = """$on = true
$show = false
root = Stack([tags, sw, acc, btn, mdl])
tags = Stack([Tag("Open", "positive", "sm"), Stat("Count", 12, "items")], "row", "m", true)
sw = Switch("on", $on, "Enabled")
acc = Accordion([AccordionItem("a", "Section A", TextContent("acc body"))])
btn = Button("Open modal", Action([@Set($show, true)]), "primary")
mdl = Modal("show", "Edit thing", [TextContent("inside modal")])
"""
    def chk(d, info):
        text = info.get("text") or ""
        oks = [
            check("tag chip", js(d, "return document.querySelectorAll('.assist-openui .q-chip').length > 0;")),
            check("stat value", "12" in text),
            check("switch toggle", js(d, "return document.querySelectorAll('.assist-openui .q-toggle').length > 0;")),
            check("accordion", "Section A" in text or js(d, "return document.querySelectorAll('.assist-openui .q-expansion-item').length > 0;")),
            check("modal closed initially", "inside modal" not in text or js(d, """
                var dlg = document.querySelector('.q-dialog');
                return !dlg || dlg.getAttribute('aria-hidden') === 'true' || !dlg.classList.contains('q-dialog--modal');
            """)),
        ]
        # open modal
        js(
            d,
            """
            var btns = document.querySelectorAll('.assist-openui .q-btn');
            for (var i=0;i<btns.length;i++) {
              if ((btns[i].innerText||'').indexOf('Open modal')>=0) { btns[i].click(); return true; }
            }
            return false;
            """,
        )
        time.sleep(0.6)
        body = js(d, "return document.body.innerText;") or ""
        oks.append(check("modal opens on button", "inside modal" in body or "Edit thing" in body, body[-400:]))
        return oks
    return {"id": "layout", "title": "Layout/forms", "lang": lang, "check": chk}


def case_unknown():
    lang = "root = Stack([Nope()])\n"
    def chk(d, info):
        body = js(d, "return document.body.innerText || '';") or ""
        return [check("unknown component caption", "Nope" in body and "Unknown" in body, body[:500])]
    return {"id": "unknown", "title": "Unknown", "lang": lang, "check": chk}


# --- Assist chat thread / canvas chrome (no LLM needed, drives the Vue component directly) ---

# Same lookup as FIND_ASSIST but leaves the component in `vm` so callers can append code.
ASSIST_VM = r"""
function findYield(vm, depth) {
    if (!vm || depth > 20) return null;
    if (typeof vm.applyYield === 'function') return vm;
    var ch = vm.$children || [];
    for (var i = 0; i < ch.length; i++) {
        var f = findYield(ch[i], depth + 1);
        if (f) return f;
    }
    return null;
}
var root = (window.moqui && moqui.webrootVue) || (document.querySelector('#apps-root') && document.querySelector('#apps-root').__vue__);
var vm = findYield(root, 0);
"""

CHAT_MD = (
    "# head\n\n"
    '<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n\n'
    "**bold** and `code`\n\n"
    "```java\nint x = 1;\n```\n\n"
    "[ok](/qapps/assist)\n"
)


def push_message(driver, msg):
    return js(
        driver,
        ASSIST_VM
        + "\nvm.$set(vm, 'messages', (vm.messages || []).concat([arguments[0]])); return 'ok';",
        msg,
    )


def case_chat_markdown():
    def chk(d, info):
        oks = []
        js(d, ASSIST_VM + "\nvm.$set(vm, 'messages', []); vm.$set(vm, 'openUiRuntimeReady', false); return 1;")
        push_message(d, {"role": "assistant", "content": CHAT_MD})
        time.sleep(0.6)
        html = js(d, "var el=document.querySelector('.assist-md-fallback'); return el ? el.innerHTML : '';") or ""
        low = html.lower()
        oks.append(check("fallback renders markdown", "<strong>bold</strong>" in low, html[:300]))
        oks.append(check("fallback code fence", "<pre>" in low, html[:300]))
        # Escape-then-tag: the payloads survive as text, never as markup.
        oks.append(check("fallback XSS escaped", "<script" not in low and "<img" not in low and "&lt;script&gt;" in low, html[:300]))

        # The library renderer is DOM based: marked + DOMPurify, then links get target/rel.
        js(d, ASSIST_VM + "\nvm.$set(vm, 'openUiRuntimeReady', true); return 1;")
        end = time.time() + 25
        html = ""
        while time.time() < end:
            html = js(d, "var el=document.querySelector('.assist-md'); return el ? el.innerHTML : '';") or ""
            if "<pre" in html.lower():
                break
            time.sleep(0.3)
        low = html.lower()
        oks.append(check("assist-markdown component rendered", "<pre" in low and "bold" in low, html[:300]))
        oks.append(check("markdown XSS stripped (DOMPurify)", "<script" not in low and "onerror" not in low, html[:300]))
        oks.append(check("markdown link rewritten for target", 'target="_blank"' in low, html[:400]))
        oks.append(check("fallback markup gone", not js(d, "return !!document.querySelector('.assist-md-fallback');"), "both branches rendered"))
        return oks

    return {"id": "chat-markdown", "title": "Chat markdown", "lang": "root = Stack([])\n", "check": chk}


def case_chat_tool_row():
    def chk(d, info):
        oks = []
        js(d, ASSIST_VM + "\nvm.$set(vm, 'messages', []); return 1;")
        push_message(d, {"role": "tool", "kind": "call", "name": "request",
                         "arguments": '{"method":"POST","path":"/x","body":{"password":"hunter2"}}'})
        rows = js(d, "return document.querySelectorAll('.assist-tool-row').length;")
        oks.append(check("tool row rendered", rows == 1, "rows=%s" % rows))
        oks.append(check("detail collapsed by default",
                         not js(d, "return !!document.querySelector('.assist-source-pre');"), "detail open on load"))
        js(d, "document.querySelector('.assist-tool-row').click(); return 1;")
        time.sleep(0.3)
        pre = js(d, "var el=document.querySelector('.assist-source-pre'); return el ? el.textContent : '';") or ""
        oks.append(check("click expands tool detail", '"path": "/x"' in pre, pre[:200]))
        oks.append(check("tool detail redacts secrets", "hunter2" not in pre and '"password": "***"' in pre, pre[:200]))
        oks.append(check("row marked expanded",
                         js(d, "var el=document.querySelector('.assist-tool-row'); return el.getAttribute('aria-expanded');") == "true", "aria-expanded"))
        # keyboard: Enter toggles the same detail
        js(d, "document.querySelector('.assist-source-pre').remove(); "
              "document.querySelector('.assist-tool-row').dispatchEvent("
              "new KeyboardEvent('keyup', {key: 'Enter', bubbles: true})); return 1;")
        time.sleep(0.3)
        oks.append(check("Enter toggles tool detail",
                         not js(d, "return !!document.querySelector('.assist-source-pre');"), "keyboard did not collapse"))
        return oks

    return {"id": "chat-tool-row", "title": "Tool detail", "lang": "root = Stack([])\n", "check": chk}


def case_result_links():
    def chk(d, info):
        oks = []
        apply_lang(d, "root = Stack([txt])\ntxt = TextContent('canvas ready')\n", "Result links")
        js(d, ASSIST_VM + "\nvm.$set(vm, 'lastActionResults', arguments[0]); return 1;", [
            {"id": "create", "status": 200, "body": {"screenUrl": "/apps/Order/Edit"}},
            {"id": "bad", "status": 200, "body": {"screenUrl": "//evil.example.com/x"}},
            {"id": "skipped", "skipped": True, "body": {"screenUrl": "/apps/No/Edit"}},
            {"id": "failed", "error": "boom", "body": {"screenUrl": "/apps/No/Edit2"}},
            {"id": "list", "status": 200, "body": {"redirectUrl": "/apps/Order/List?page=2"}},
        ])
        time.sleep(0.5)
        labels = js(d, "var els=document.querySelectorAll('#assist-result-links .q-btn');"
                       "var out=[]; for (var i=0;i<els.length;i++) out.push(els[i].innerText.trim()); return out;") or []
        hrefs = js(d, ASSIST_VM + "return (vm.resultLinks || []).map(function(r) { return r.href; });") or []
        # innerText also carries the q-icon ligature, so match on the tail of each label.
        oks.append(check("only valid result links shown",
                         len(labels) == 2 and labels[0].endswith("Open Edit") and labels[1].endswith("Open List"),
                         "labels=%s" % labels))
        oks.append(check("no off-origin result link",
                         all("evil.example.com" not in (h or "") for h in hrefs), "hrefs=%s" % hrefs))
        # Regression: the write_ui yield that follows a submit must not clear the links.
        apply_lang(d, "root = Stack([txt])\ntxt = TextContent('revised canvas')\n", "Result links")
        time.sleep(0.4)
        after = js(d, "return document.querySelectorAll('#assist-result-links .q-btn').length;") or 0
        oks.append(check("links survive the next write_ui yield", after == 2, "count=%s after=%s" % (len(labels), after)))
        return oks

    return {"id": "result-links", "title": "Result links", "lang": "root = Stack([])\n", "check": chk}


def case_session_stats():
    def chk(d, info):
        oks = []
        js(d, ASSIST_VM + "\nvm.$set(vm, 'messages', []); vm.sessionTurns = 0; vm.sessionTokens = 0; return 1;")
        js(d, ASSIST_VM + "\nvm.recordDoneStats({usage:{promptTokens:5, completionTokens:5, totalTokens:10}});"
                         "vm.recordDoneStats({usage:{promptTokens:5, completionTokens:5, totalTokens:10}}); return 1;")
        time.sleep(0.3)
        header = js(d, "var els=document.querySelectorAll('.assist-root .text-caption');"
                       "for (var i=0;i<els.length;i++) { if (els[i].innerText.indexOf('turn') >= 0) return els[i].innerText.trim(); }"
                       "return '';") or ""
        oks.append(check("turn/token totals", "2 turns" in header and "20" in header, header[:120]))
        js(d, ASSIST_VM + "\nvm.recordDoneStats({usage:{promptTokens:1, completionTokens:2}}); return 1;")
        time.sleep(0.3)
        header = js(d, "var els=document.querySelectorAll('.assist-root .text-caption');"
                       "for (var i=0;i<els.length;i++) { if (els[i].innerText.indexOf('turn') >= 0) return els[i].innerText.trim(); }"
                       "return '';") or ""
        oks.append(check("usage without totalTokens", "3 turns" in header and "23" in header, header[:120]))
        # A restored conversation counts user turns, not history rows.
        js(d, ASSIST_VM + "\nvm.restoreConversation({conversationId: 'smoke-restore-conv',"
                          " history: [{role:'user', content:'a'}, {role:'assistant', content:'b'},"
                          " {role:'user', content:'c'}, {role:'assistant', content:'d'},{role:'user', content:'e'}],"
                          " pendingToolCalls: [], canvas: {kind:'openui', title:'restored',"
                          " lang:'root = Stack([txt])\\ntxt = TextContent(\\'restored\\')\\n'}}); return 1;")
        time.sleep(0.5)
        header = js(d, "var els=document.querySelectorAll('.assist-root .text-caption');"
                       "for (var i=0;i<els.length;i++) { if (els[i].innerText.indexOf('turn') >= 0) return els[i].innerText.trim(); }"
                       "return '';") or ""
        oks.append(check("restored conversation counts user turns", header.startswith("3 turns"), header[:120]))
        js(d, "try { sessionStorage.removeItem('assist.conversationId'); } catch (e) { } return 1;")
        return oks

    return {"id": "session-stats", "title": "Session stats", "lang": "root = Stack([])\n", "check": chk}


def main():
    os.makedirs(SHOT_DIR, exist_ok=True)
    driver, browser = make_driver()
    print("browser", browser, "base", BASE)
    results = []
    try:
        login(driver)
        print("logged in as", USER, "url", driver.current_url)
        goto_assist(driver)
        print("assist mounted", driver.current_url)
        shot(driver, "00-assist-empty")

        cases = [
            case_basic(),
            case_link(),
            case_charts(),
            case_markdown(),
            case_mermaid(),
            case_layout_forms(),
            case_unknown(),
            case_chat_markdown(),
            case_chat_tool_row(),
            case_result_links(),
            case_session_stats(),
        ]
        for c in cases:
            print("---", c["id"], "---")
            try:
                info = apply_lang(driver, c["lang"], c["title"])
                time.sleep(0.4)
                shot(driver, c["id"])
                oks = list(c["check"](driver, info))
                results.extend(oks)
            except Exception as e:
                shot(driver, c["id"] + "-error")
                print("FAIL  %s exception: %s" % (c["id"], e))
                traceback.print_exc()
                results.append(False)
            errs = console_errors(driver)
            # filter noise
            severe = [e for e in errs if "favicon" not in e.lower() and "404" not in e]
            if severe:
                print("console", "\n  ".join(severe[:8]))
    finally:
        driver.quit()

    passed = sum(1 for r in results if r)
    failed = sum(1 for r in results if not r)
    print("shots", os.path.abspath(SHOT_DIR))
    print("summary pass=%s fail=%s" % (passed, failed))
    return 0 if failed == 0 and passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
