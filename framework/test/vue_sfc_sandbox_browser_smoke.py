#!/usr/bin/env python3
"""Browser smoke for the vue-sfc sandbox: generated Vue renders in an isolated frame and cannot reach the page.

Run against a running Moqui (dev conf has allow-vue-sfc on for the assist profile):
  java -jar moqui.war &
  python3 framework/test/vue_sfc_sandbox_browser_smoke.py
No LLM is used: canvases are injected through the Assist Vue instance, like openui_browser_smoke.py.
"""
from __future__ import print_function

import json
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openui_browser_smoke as base  # noqa: E402
from openui_browser_smoke import check, js, wait_js  # noqa: E402
from selenium.webdriver.common.by import By  # noqa: E402

BASE = base.BASE

APPLY_SFC = r"""
var args = arguments[0];
function findYield(vm, depth) {
    if (!vm || depth > 20) return null;
    if (typeof vm.applyYield === 'function') return vm;
    var ch = vm.$children || [];
    for (var i = 0; i < ch.length; i++) { var f = findYield(ch[i], depth + 1); if (f) return f; }
    return null;
}
var vm = findYield((window.moqui && moqui.webrootVue) || null, 0);
if (!vm) return 'no-applyYield';
vm.applyYield(args);
var vals = arguments[1] || {};
Object.keys(vals).forEach(function(k) { vm.$set(vm.fieldValues, k, vals[k]); });
return 'ok';
"""

VM = r"""
function findYield(vm, depth) {
    if (!vm || depth > 20) return null;
    if (typeof vm.applyYield === 'function') return vm;
    var ch = vm.$children || [];
    for (var i = 0; i < ch.length; i++) { var f = findYield(ch[i], depth + 1); if (f) return f; }
    return null;
}
var vm = findYield((window.moqui && moqui.webrootVue) || null, 0);
"""


def wait_vm(driver, expr, timeout=30, msg=None):
    """Poll an expression evaluated with `vm` (the Assist Vue instance) in scope."""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = js(driver, VM + "return (" + expr + ");")
            if last:
                return last
        except Exception as e:
            last = str(e)
        time.sleep(0.3)
    raise TimeoutError(msg or ("wait_vm failed: " + expr + " last=" + repr(last)))


def canvas(driver, template, script="module.exports = {}", values=None, style=None):
    args = {"kind": "vue-sfc", "title": "Sandbox smoke", "template": template, "script": script,
            "fields": [], "actions": []}
    if style:
        args["style"] = style
    rc = js(driver, APPLY_SFC, args, values or {})
    if rc != "ok":
        raise RuntimeError("applyYield: " + str(rc))


def frame_element(driver, timeout=30):
    wait_js(driver, "!!document.querySelector('iframe.assist-sandbox-frame')", timeout=timeout,
            msg="sandbox frame did not appear")
    return driver.find_element(By.CSS_SELECTOR, "iframe.assist-sandbox-frame")


def in_frame(driver, fn, timeout=30):
    """Run fn() with the driver inside the sandbox frame, waiting for the runtime to mount the component."""
    el = frame_element(driver, timeout)
    driver.switch_to.frame(el)
    try:
        return fn()
    finally:
        driver.switch_to.default_content()


def frame_wait(driver, expr, timeout=30):
    """Wait, inside the frame, for a JS expression to be truthy. Returns its value."""
    def run():
        end = time.time() + timeout
        last = None
        while time.time() < end:
            try:
                last = js(driver, "return (" + expr + ");")
                if last:
                    return last
            except Exception as e:
                last = str(e)
            time.sleep(0.25)
        return last
    return in_frame(driver, run, timeout + 5)


def main():
    os.makedirs(base.SHOT_DIR, exist_ok=True)
    driver, name = base.make_driver()
    oks = []
    try:
        base.login(driver)
        base.goto_assist(driver)
        token = js(driver, "return moqui.webrootVue.moquiSessionToken;")
        wait_js(driver, "window.moquiAssistVm && moquiAssistVm.profilesLoaded", timeout=30,
                msg="profiles did not load")
        allowed = js(driver, "return !!(moquiAssistVm.profileFlags && moquiAssistVm.profileFlags.allowVueSfc);")
        if not allowed:
            print("SKIP vue-sfc is not allowed for the assist profile (allow-vue-sfc=false); nothing to test")
            return 0

        # 1. renders, and the frame is a sandbox with an opaque origin
        canvas(driver,
               '<div id="sfc-hello"><q-btn id="sfc-btn" label="Quasar ok" @click="$emit(\'input\', {name:\'firstName\', value:\'Zed\'})"></q-btn>'
               '<span id="sfc-name">{{ values.name }}</span><div id="sfc-tall" style="height:640px"></div></div>',
               values={"name": "Ada"})
        el = frame_element(driver)
        base.shot(driver, "sandbox-render")
        oks.append(check("frame is sandbox=allow-scripts only", el.get_attribute("sandbox") == "allow-scripts", str(el.get_attribute("sandbox"))))
        oks.append(check("frame has no src and uses srcdoc", not el.get_attribute("src") and bool(el.get_attribute("srcdoc")), ""))
        name_text = frame_wait(driver, "(document.getElementById('sfc-name') || {}).textContent === 'Ada'")
        oks.append(check("generated component renders with values from the page", bool(name_text), str(name_text)))
        oks.append(check("Quasar component renders in the frame",
                         bool(frame_wait(driver, "!!document.querySelector('#sfc-btn') && document.querySelector('#sfc-btn').className.indexOf('q-btn') >= 0")), ""))
        origin_null = in_frame(driver, lambda: js(driver, "return location.origin;"))
        oks.append(check("frame origin is opaque", origin_null in ("null", None, ""), str(origin_null)))

        # 2. isolation from the page: every one of these must fail inside the frame
        def probes():
            return js(driver, """
                var out = {};
                function t(name, fn) { try { var v = fn(); out[name] = 'ALLOWED:' + String(v).slice(0, 60); } catch (e) { out[name] = 'blocked'; } }
                t('parentDocument', function() { return window.parent.document.title; });
                t('topLocation', function() { return window.top.location.href; });
                t('cookie', function() { return document.cookie; });
                t('localStorage', function() { return window.localStorage.length; });
                t('sessionStorage', function() { return window.sessionStorage.length; });
                t('moquiGlobal', function() { if (typeof window.moqui === 'undefined') throw new Error('absent'); return 'present'; });
                t('openWindow', function() { var w = window.open('about:blank'); if (!w) throw new Error('blocked'); return 'opened'; });
                t('navigateTop', function() { window.top.location = 'about:blank'; return 'navigated'; });
                return JSON.stringify(out);
            """)
        res = json.loads(in_frame(driver, probes))
        for k in ("parentDocument", "topLocation", "cookie", "localStorage", "sessionStorage", "moquiGlobal", "openWindow", "navigateTop"):
            oks.append(check("isolation: " + k + " is blocked", res.get(k) == "blocked", str(res.get(k))))
        still_on_assist = js(driver, "return !!document.querySelector('iframe.assist-sandbox-frame');")
        oks.append(check("page was not navigated away", bool(still_on_assist), ""))
        html = in_frame(driver, lambda: js(driver, "return document.documentElement.outerHTML;"))
        oks.append(check("the session token is nowhere in the frame", bool(token) and token not in html, ""))

        # 3. direct network from inside the frame is blocked by the CSP
        canvas(driver, '<div><span id="net-fetch">{{ fetchResult }}</span><span id="net-xhr">{{ xhrResult }}</span><span id="net-img">{{ imgResult }}</span></div>',
               script=("module.exports = { data: function() { return { fetchResult: 'pending', xhrResult: 'pending', imgResult: 'pending' }; },"
                       " mounted: function() { var vm = this;"
                       " fetch('" + BASE + "/rest/e1/moqui.llm.LlmSkill?pageSize=1', {credentials:'include'}).then(function() { vm.fetchResult = 'ALLOWED'; }, function() { vm.fetchResult = 'blocked'; });"
                       " try { var x = new XMLHttpRequest(); x.open('GET', '" + BASE + "/Login'); x.onload = function() { vm.xhrResult = 'ALLOWED'; }; x.onerror = function() { vm.xhrResult = 'blocked'; }; x.send(); } catch (e) { vm.xhrResult = 'blocked'; }"
                       " var img = new Image(); img.onload = function() { vm.imgResult = 'ALLOWED'; }; img.onerror = function() { vm.imgResult = 'blocked'; }; img.src = '" + BASE + "/favicon.ico'; } };"))
        frame_wait(driver, "document.getElementById('net-fetch') && document.getElementById('net-fetch').textContent !== 'pending'")
        time.sleep(1.0)
        net = in_frame(driver, lambda: js(driver, "return [ 'net-fetch', 'net-xhr', 'net-img' ].map(function(i) { var e = document.getElementById(i); return e ? e.textContent : null; });"))
        oks.append(check("direct fetch from the frame is blocked", net[0] == "blocked", str(net)))
        oks.append(check("direct XHR from the frame is blocked", net[1] == "blocked", str(net)))
        oks.append(check("an external image load from the frame is blocked", net[2] == "blocked", str(net)))

        # 4. the bridge: a read works, a write path and a bad path are refused
        canvas(driver, '<div><span id="b-ok">{{ ok }}</span><span id="b-write">{{ write }}</span><span id="b-bad">{{ bad }}</span><span id="b-host">{{ host }}</span></div>',
               script=("module.exports = { data: function() { return { ok: 'pending', write: 'pending', bad: 'pending', host: 'pending' }; },"
                       " mounted: function() { var vm = this;"
                       " this.$bridge.get('/apps/system/dashboard/actions').then(function(r) { vm.ok = 'status ' + r.status + ' json ' + (r.json ? 'yes' : 'no'); }, function(e) { vm.ok = 'ERR ' + e.message; });"
                       " this.$bridge.get('/apps/system/dashboard/reInitElasticFacade').then(function(r) { vm.write = 'ALLOWED'; }, function(e) { vm.write = 'refused: ' + e.message; });"
                       " this.$bridge.get('/apps/../etc/passwd').then(function(r) { vm.bad = 'ALLOWED'; }, function(e) { vm.bad = 'refused: ' + e.message; });"
                       " this.$bridge.get('https://evil.example.com/x').then(function(r) { vm.host = 'ALLOWED'; }, function(e) { vm.host = 'refused: ' + e.message; }); } };"))
        frame_wait(driver, "document.getElementById('b-host') && document.getElementById('b-host').textContent !== 'pending'", timeout=40)
        time.sleep(0.5)
        b = in_frame(driver, lambda: js(driver, "return [ 'b-ok', 'b-write', 'b-bad', 'b-host' ].map(function(i) { return document.getElementById(i).textContent; });"))
        base.shot(driver, "sandbox-bridge")
        oks.append(check("bridged GET reads a data endpoint", b[0] == "status 200 json yes", str(b)))
        oks.append(check("bridged GET of a path that would write is refused", b[1].startswith("refused:") and "changes data" in b[1], str(b)))
        oks.append(check("bridged path with .. is refused", b[2].startswith("refused:"), str(b)))
        oks.append(check("bridged URL with a host is refused", b[3].startswith("refused:"), str(b)))

        # 5. events reach the page, and nothing else can write the page's values
        canvas(driver, '<div><q-btn id="emit-btn" label="Emit" @click="$emit(\'input\', {name:\'firstName\', value:\'Zed\'})"></q-btn></div>')
        frame_wait(driver, "!!document.querySelector('#emit-btn')")
        in_frame(driver, lambda: js(driver, "document.querySelector('#emit-btn').click(); return 1;"))
        wait_vm(driver, "vm.fieldValues && vm.fieldValues.firstName === 'Zed'", timeout=15,
                msg="input event did not reach the page")
        oks.append(check("an input event from the frame updates the page's field values", True, ""))
        js(driver, "window.postMessage({t: 'input', payload: {name: 'evil', value: 'x'}}, '*'); return 1;")
        time.sleep(0.5)
        evil = js(driver, VM + "return vm.fieldValues.evil === undefined;")
        oks.append(check("a message posted to the page window, not through the port, is ignored", bool(evil), ""))

        # 6. a compile error comes back as a notice and falls back to the plain form
        canvas(driver, '<div>broken</div>', script="module.exports = { data: function( {")
        wait_vm(driver, "(vm.canvasNotices || []).some(function(n) { return String(n.message || n.text || JSON.stringify(n)).indexOf('failed to compile') >= 0; })",
                timeout=30, msg="compile error notice did not appear")
        failed = js(driver, VM + "return !!vm.vueSfcFailed && !vm.vueSfcSource;")
        oks.append(check("a compile error is reported to the page and falls back", bool(failed), ""))

        # 7. a tall screen resizes the frame
        canvas(driver, '<div id="tall" style="height:700px">tall</div>')
        wait_js(driver, "document.querySelector('iframe.assist-sandbox-frame') && document.querySelector('iframe.assist-sandbox-frame').offsetHeight >= 690", timeout=20,
                msg="frame did not resize")
        oks.append(check("the frame resizes to fit its content", True, ""))

        # 8. the shimmed widgets render and read through the bridge
        canvas(driver,
               '<div><m-container-box title="Box title"><m-text-line dense outlined label="Name" name="n" :value="values.n" @input="$emit(\'input\', {name:\'n\', value:$event})"></m-text-line>'
               '<m-drop-down id="dd-static" dense outlined label="Static" name="s" :options="[{value:\'a\',label:\'Alpha\'},{value:\'b\',label:\'Beta\'}]" allow-empty :value="values.s" @input="$emit(\'input\', {name:\'s\', value:$event})"></m-drop-down>'
               '<m-drop-down id="dd-remote" dense outlined label="Remote" name="r" options-url="/rest/s1/moqui/basic/geos" :options-parameters="{pageSize:\'5\'}" options-load-init allow-empty value-field="geoId" label-field="geoName" :value="values.r" @input="$emit(\'input\', {name:\'r\', value:$event})"></m-drop-down>'
               '<m-date-time name="d" type="date" label="Date" :value="values.d" @input="$emit(\'input\', {name:\'d\', value:$event})"></m-date-time>'
               '<m-display :value="values.n" label-wrapper label="Echo" name="e"></m-display>'
               '<m-date-period :fields="values" name="p" label="Period"></m-date-period></m-container-box></div>',
               values={"n": "hello"})
        frame_wait(driver, "[].some.call(document.querySelectorAll('label.q-select'), function(e) { return e.innerText.indexOf('Static') >= 0; })")
        time.sleep(2.0)
        widgets = in_frame(driver, lambda: js(driver, """
            function selectByLabel(text) {
                return [].filter.call(document.querySelectorAll('label.q-select'), function(e) { return e.innerText.indexOf(text) >= 0; })[0] || null;
            }
            var remote = selectByLabel('Remote');
            // The label element's Vue instance is the QSelect, and its parent is the m-drop-down
            var remoteVm = remote && remote.__vue__ ? remote.__vue__.$parent : null;
            var values = [].map.call(document.querySelectorAll('input, textarea'), function(e) { return e.value; });
            return JSON.stringify({
                title: document.body.innerText.indexOf('Box title') >= 0,
                text: !!document.querySelector('input[name=n]'),
                staticSel: !!selectByLabel('Static'),
                remoteLoaded: remoteVm && remoteVm.allOptions ? remoteVm.allOptions.length : -1,
                dateInput: !!document.querySelector('input[name=d]'),
                echo: values.indexOf('hello') >= 0,
                period: !!document.querySelector('input[name=p_pdate]')
            });
        """))
        w = json.loads(widgets)
        base.shot(driver, "sandbox-widgets")
        oks.append(check("m-container-box renders its title", w["title"], widgets))
        oks.append(check("m-text-line renders an input", w["text"], widgets))
        oks.append(check("m-drop-down renders with static options", w["staticSel"], widgets))
        oks.append(check("m-drop-down loads options through the bridge", w["remoteLoaded"] > 0, widgets))
        oks.append(check("m-date-time renders an input", w["dateInput"], widgets))
        oks.append(check("m-display shows the value", w["echo"], widgets))
        oks.append(check("m-date-period renders its date input", w["period"], widgets))
    except Exception as e:
        base.shot(driver, "sandbox-error")
        print("EXCEPTION", e)
        traceback.print_exc()
        oks.append(False)
    finally:
        for entry in base.console_errors(driver)[:10]:
            print("console", entry[:300])
        driver.quit()
    passed = sum(1 for o in oks if o)
    failed = sum(1 for o in oks if not o)
    print("summary pass=%s fail=%s" % (passed, failed))
    return 0 if failed == 0 and passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
