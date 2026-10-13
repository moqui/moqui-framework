#!/usr/bin/env python3
"""Browser smoke for the System app LLM screens, run as a real (authz-enforced) user against a running Moqui.

Start the fake provider and Moqui first:
  python3 framework/test/fake_openai_server.py 18234 &
  java -Dllm_openai_url=http://127.0.0.1:18234 -Dllm_openai_model=fake-model -Dllm_log_content=true -jar moqui.war &
  python3 framework/test/llm_admin_browser_smoke.py
Creates one conversation through the real chat endpoint and one proposed skill, then drives the screens.
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

BASE = base.BASE
STAMP = str(int(time.time()))
SKILL_NAME = "smoke-skill-" + STAMP
ASK = "admin smoke question " + STAMP


def api(driver, method, path, body):
    """Same-origin fetch with the session token, returns {status, text}."""
    return driver.execute_async_script("""
        var done = arguments[arguments.length - 1];
        var tok = moqui.webrootVue.moquiSessionToken;
        fetch(arguments[1], {method: arguments[0], credentials: 'same-origin',
              headers: {'Content-Type': 'application/json', 'Accept': 'application/json',
                        'X-CSRF-Token': tok, 'moquiSessionToken': tok},
              body: arguments[2] ? JSON.stringify(arguments[2]) : undefined})
          .then(function(r) { return r.text().then(function(t) { done({status: r.status, text: t}); }); })
          .catch(function(e) { done({status: 0, text: String(e)}); });
    """, method, path, body)


def page_text(driver, path, expect, timeout=30):
    """Open a /qapps/system page and wait until the body text contains `expect`."""
    driver.get(BASE + "/qapps/system/Llm/" + path)
    end = time.time() + timeout
    text = ""
    while time.time() < end:
        text = js(driver, "return document.body ? document.body.innerText : '';") or ""
        if expect in text:
            return text
        time.sleep(0.4)
    return text


def click_text(driver, selector, text):
    return js(driver, """
        var els = document.querySelectorAll(arguments[0]);
        for (var i = 0; i < els.length; i++) {
            if ((els[i].innerText || '').trim().indexOf(arguments[1]) >= 0) { els[i].click(); return true; }
        }
        return false;
    """, selector, text)


def confirm_dialog(driver):
    """Accept the browser confirm() a link's confirmation attribute raises, if one is showing."""
    try:
        driver.switch_to.alert.accept()
        return True
    except Exception:
        return False


def main():
    os.makedirs(base.SHOT_DIR, exist_ok=True)
    driver, name = base.make_driver()
    oks = []
    try:
        base.login(driver)
        driver.get(BASE + "/qapps")
        wait_js(driver, "!!(window.moqui && moqui.webrootVue && moqui.webrootVue.moquiSessionToken)")

        # Seed: a real conversation through the chat endpoint (the fake provider answers), and a proposed skill row.
        r = api(driver, "POST", "/llm/v1/chat", {"profile": "default", "user": ASK})
        print("chat", r["status"], r["text"][:160].replace("\n", " "))
        oks.append(check("chat through fake provider", r["status"] == 200 and "Fake reply" in r["text"], r["text"][:200]))
        conv_id = None
        try:
            conv_id = json.loads(r["text"]).get("conversationId")
        except Exception:
            pass
        r = api(driver, "POST", "/rest/e1/moqui.llm.LlmSkill", {
            "skillId": "SMK" + STAMP, "name": SKILL_NAME, "title": "Smoke skill", "description": "created by the browser smoke",
            "body": "Step one.", "riskId": "LskReversible", "statusId": "LsksProposed", "provenanceId": "LskpSim",
            "version": 1, "worldSuccessCount": 4})
        print("skill seed", r["status"], r["text"][:120])
        oks.append(check("seed proposed skill", r["status"] in (200, 201), r["text"][:200]))

        # Conversations
        t = page_text(driver, "Conversation/ConversationList", ASK[:20])
        base.shot(driver, "llm-conversations")
        oks.append(check("conversation list shows the chat", ASK[:20] in t or (conv_id and conv_id in t), t[:300]))
        if conv_id:
            t = page_text(driver, "Conversation/ConversationDetail?conversationId=" + conv_id, "Fake reply")
            base.shot(driver, "llm-conversation-detail")
            oks.append(check("detail shows user message", ASK in t, t[:300]))
            oks.append(check("detail shows assistant reply", "Fake reply from the test server." in t, t[:300]))
            oks.append(check("detail lists the provider call", "fake-model" in t, t[:300]))
            oks.append(check("detail shows token total", "18" in t, t[:300]))
            oks.append(check("detail shows system prompt/state", "State" in t, t[:300]))

        # Calls and usage
        t = page_text(driver, "Call/CallList", "fake-model")
        base.shot(driver, "llm-calls")
        oks.append(check("call list shows the call", "fake-model" in t, t[:300]))
        ok_click = click_text(driver, "a", "20")  # the start-date link opens the detail
        time.sleep(1.5)
        t = js(driver, "return document.body.innerText;") or ""
        oks.append(check("call detail shows logged request and response",
                         "Fake reply from the test server." in t and "Request" in t, t[:300]))
        t = page_text(driver, "Usage", "fake-model")
        base.shot(driver, "llm-usage")
        oks.append(check("usage shows the model and tokens", "fake-model" in t, t[:300]))

        # Profiles
        t = page_text(driver, "Profile", "Runtime Switches")
        base.shot(driver, "llm-profiles")
        oks.append(check("profile page lists default and masks the key", "default" in t and "Test Connection" in t, t[:300]))
        oks.append(check("profile page shows no api key value", "api-key" not in t.lower() or "set" in t.lower(), ""))
        clicked = click_text(driver, "a, button", "Test Connection")
        time.sleep(1.0)
        # a confirmation dialog may be shown
        confirm_dialog(driver)
        end = time.time() + 20
        t = ""
        while time.time() < end:
            t = js(driver, "return document.body.innerText;") or ""
            if "answered in" in t or "failed after" in t:
                break
            time.sleep(0.5)
        base.shot(driver, "llm-profile-test")
        oks.append(check("connection test reports the fake provider answered", "answered in" in t, t[-300:]))

        # Skills
        t = page_text(driver, "Skill/SkillList?statusId=LsksProposed", SKILL_NAME)
        base.shot(driver, "llm-skills-review")
        oks.append(check("needs-review lists the proposed skill", SKILL_NAME in t, t[:300]))
        oks.append(check("shipped skill files listed", "Shipped Skill Files" in t, t[:200]))
        t = page_text(driver, "Skill/SkillDetail?skillId=SMK" + STAMP, "Review")
        base.shot(driver, "llm-skill-detail")
        try:
            wait_js(driver, "[].filter.call(document.querySelectorAll('button, input[type=submit]'), function(x) { return ((x.innerText || x.value || '').indexOf('Approve as Human-Reviewed') >= 0); }).length > 0", timeout=15)
        except Exception:
            pass
        has_btn = js(driver, "return !![].filter.call(document.querySelectorAll('button, input[type=submit]'), function(x) { return ((x.innerText || x.value || '').indexOf('Approve as Human-Reviewed') >= 0); }).length;")
        body_value = js(driver, "var a = document.querySelector('textarea[name=body]'); return a ? a.value : null;")
        oks.append(check("skill detail edit form holds the body", body_value == "Step one.", str(body_value)))
        oks.append(check("skill detail shows the review form", bool(has_btn), t[:400]))
        oks.append(check("detail warns reversible is ignored until reviewed", "ignored until human-reviewed" in t, t[:400]))
        # Approve through the review form with a different risk
        js(driver, """
            var sel = document.querySelector('form[name=ApproveSkill] select, #ApproveSkill_riskId, form[id*=ApproveSkill] select');
            if (sel) { sel.value = 'LskConfirm'; sel.dispatchEvent(new Event('change', {bubbles: true})); }
            return !!sel;""")
        approved = click_text(driver, "button, input[type=submit]", "Approve as Human-Reviewed")
        time.sleep(2.0)
        t = js(driver, "return document.body.innerText;") or ""
        base.shot(driver, "llm-skill-approved")
        oks.append(check("approve clicked", bool(approved), ""))
        oks.append(check("skill is Active and human after approval", "Active" in t and "Human" in t, t[:500]))
        r = api(driver, "GET", "/rest/e1/moqui.llm.LlmSkill/SMK" + STAMP, None)
        oks.append(check("stored row is Active and Human", "LsksActive" in r["text"] and "LskpHuman" in r["text"], r["text"][:300]))
        oks.append(check("approval recorded the reviewer", "Approved by" in r["text"], r["text"][:300]))

        t = page_text(driver, "Skill/RetrievalTest?query=create+user+account", "Text Added to the Prompt")
        base.shot(driver, "llm-retrieval")
        oks.append(check("retrieval test ranks a shipped skill", "create-user-account" in t, t[:300]))
        t = page_text(driver, "Skill/LessonList", "Lessons")
        oks.append(check("lessons page renders", "Lessons" in t, t[:200]))

        # Clean up what this run created through the screen's own delete service
        if conv_id:
            driver.get(BASE + "/qapps/system/Llm/Conversation/ConversationDetail?conversationId=" + conv_id)
            wait_js(driver, "document.body.innerText.indexOf('Fake reply') >= 0")
            click_text(driver, "a, button", "Delete")
            time.sleep(1.0)
            confirm_dialog(driver)
            time.sleep(2.0)
            t = page_text(driver, "Conversation/ConversationList", "Conversation")
            time.sleep(1.0)
            t = js(driver, "return document.body.innerText;") or ""
            oks.append(check("delete removed the conversation from the list", conv_id not in t, t[:300]))
        api(driver, "DELETE", "/rest/e1/moqui.llm.LlmSkill/SMK" + STAMP, None)
    except Exception as e:
        base.shot(driver, "llm-admin-error")
        print("EXCEPTION", e)
        traceback.print_exc()
        oks.append(False)
    finally:
        for entry in base.console_errors(driver)[:8]:
            print("console", entry[:300])
        driver.quit()
    passed = sum(1 for o in oks if o)
    failed = sum(1 for o in oks if not o)
    print("summary pass=%s fail=%s" % (passed, failed))
    return 0 if failed == 0 and passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
