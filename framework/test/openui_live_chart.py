#!/usr/bin/env python3
"""Live Assist test: ask Ornith to chart ArtifactHitBin service hits.

  python3 framework/test/openui_live_chart.py
"""
from __future__ import print_function

import os
import sys
import time

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

BASE = os.environ.get("MOQUI_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USER = os.environ.get("MOQUI_TEST_USERNAME", "john.doe")
PASSWORD = os.environ.get("MOQUI_TEST_PASSWORD", "moqui")
SHOT_DIR = os.path.join(os.path.dirname(__file__), "../../runtime/tmp/openui-browser")
PROMPT = (
    "Show me a chart summarizing the ArtifactHitBin data for services "
    "for the last 12 recorded time periods."
)
WAIT_SEC = int(os.environ.get("OPENUI_LIVE_WAIT", "480"))


def js(driver, script, *args):
    return driver.execute_script(script, *args)


def main():
    os.makedirs(SHOT_DIR, exist_ok=True)
    opts = Options()
    opts.add_argument("--headless=new")
    opts.add_argument("--no-sandbox")
    opts.add_argument("--disable-dev-shm-usage")
    opts.add_argument("--window-size=1400,1000")
    driver = webdriver.Chrome(options=opts)
    driver.set_page_load_timeout(60)
    try:
        driver.get(BASE + "/Login")
        WebDriverWait(driver, 25).until(EC.presence_of_element_located((By.ID, "login_form_username")))
        driver.find_element(By.ID, "login_form_username").clear()
        driver.find_element(By.ID, "login_form_username").send_keys(USER)
        driver.find_element(By.CSS_SELECTOR, "#login_form input[name='password']").send_keys(PASSWORD)
        driver.find_element(By.CSS_SELECTOR, "#login_form button[type='submit']").click()
        time.sleep(1.5)
        driver.get(BASE + "/qapps/assist")
        WebDriverWait(driver, 25).until(
            EC.presence_of_element_located((By.CSS_SELECTOR, "textarea.q-field__native"))
        )
        box = driver.find_element(By.CSS_SELECTOR, "textarea.q-field__native")
        box.click()
        box.send_keys(PROMPT)
        send = driver.find_element(By.ID, "assist-send")
        send.click()
        print("sent prompt, waiting up to %ss" % WAIT_SEC)
        end = time.time() + WAIT_SEC
        last = {}
        while time.time() < end:
            last = js(
                driver,
                """
                var el = document.querySelector('.assist-openui');
                var n = el ? el.querySelectorAll('canvas').length : 0;
                var pts = 0;
                if (window.Chart && Chart.instances) {
                  Object.keys(Chart.instances).forEach(function(k) {
                    var ch = Chart.instances[k];
                    var ds = ch && ch.data && ch.data.datasets;
                    if (ds) ds.forEach(function(d) { pts += (d.data && d.data.length) || 0; });
                  });
                }
                var text = el ? (el.innerText || '') : '';
                var unknown = text.indexOf('Unknown component') >= 0;
                var empty = /no artifact-hit bins|0 rows|No artifact/i.test(text);
                return {n:n, pts:pts, unknown:unknown, empty:empty, text:text.slice(0,400),
                        href: location.href};
                """,
            ) or {}
            print("poll canvases=%s pts=%s empty=%s unknown=%s" % (
                last.get("n"), last.get("pts"), last.get("empty"), last.get("unknown")))
            if last.get("pts") and int(last.get("pts") or 0) > 0 and int(last.get("n") or 0) > 0:
                path = os.path.abspath(os.path.join(SHOT_DIR, "live-artifact-chart.png"))
                driver.save_screenshot(path)
                print("SUCCESS shot", path)
                return 0
            time.sleep(5)
        path = os.path.abspath(os.path.join(SHOT_DIR, "live-artifact-chart-timeout.png"))
        driver.save_screenshot(path)
        print("TIMEOUT last", last, "shot", path)
        return 1
    finally:
        driver.quit()


if __name__ == "__main__":
    sys.exit(main())
