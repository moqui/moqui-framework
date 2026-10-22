#!/usr/bin/env python3
"""Real WAR/Jetty smoke test with a local protocol server; not a cross-engine equivalence test.
Run from the framework checkout, with Java 21 on PATH. --postgres uses a fresh disposable container.
"""
import argparse
import collections
import http.server
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import threading
import time
import urllib.request


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    runtime = root / 'runtime'
    assert runtime.is_dir() and not runtime.is_symlink(), 'private runtime required'
    evidence = root / 'framework/build/reports/deterministic-server'
    evidence.mkdir(parents=True, exist_ok=True)
    events = []
    active = set()
    random_counts = collections.Counter()
    errors = []
    lock = threading.Lock()

    class Hook(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            try:
                assert self.path == '/transaction'
                event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                assert event['version'] == 1 and event['engine'] == 'java'
                with lock:
                    if event['event'] == 'random':
                        domain, length = event['domain'], event['length']
                        assert 1 <= length <= 4096
                        index = random_counts[domain]
                        data = b''.join(hmac.digest(bytes(32), json.dumps(
                            ['moqui-random-v1', 'activity', domain, index, block],
                            separators=(',', ':')).encode('ascii'), hashlib.sha256)
                            for block in range((length + 31) // 32))
                        response = {'bytes': data[:length].hex()}
                        random_counts[domain] += 1
                    elif event['event'] == 'begin':
                        assert event['sequence'] not in active
                        assert event['artifact_stack'] and event['artifact'] == event['artifact_stack'][0]
                        assert all(set(a) == {'type', 'sub_type', 'name'} for a in event['artifact_stack'])
                        active.add(event['sequence'])
                        response = {'operation_id': f"op-{event['sequence']}",
                                    'time_epoch_ms': 1760000000000 + event['sequence'] * 1000}
                    else:
                        assert event['event'] == 'complete' and event['sequence'] in active
                        assert event['outcome'] in ('committed', 'rolled_back', 'begin_failed')
                        active.remove(event['sequence'])
                        response = {'ok': True}
                    events.append(event)
                body = json.dumps(response).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(body)
            except Exception as exc:
                errors.append(type(exc).__name__)
                self.send_error(500)

    hook = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Hook)
    threading.Thread(target=hook.serve_forever, daemon=True).start()
    port = free_port()
    environment = dict(os.environ, MOQUI_DETERMINISTIC='true',
                       MOQUI_DETERMINISTIC_HOOK=f'http://127.0.0.1:{hook.server_port}/transaction',
                       MOQUI_DETERMINISTIC_ENGINE='java', MOQUI_DETERMINISTIC_RUN='war-smoke',
                       MOQUI_DETERMINISTIC_LOG=str(evidence / 'deterministic.log'))
    (evidence / 'deterministic.log').unlink(missing_ok=True)
    container = None
    process = None
    statuses = {}
    try:
        if args.postgres:
            assert list((runtime / 'lib').glob('postgresql-*.jar')), 'private runtime PostgreSQL driver required'
            container = 'moqui-deterministic-java-' + secrets.token_hex(4)
            password = secrets.token_hex(20)
            db_port = free_port()
            subprocess.run(['docker', 'run', '-d', '--rm', '--name', container, '-p', f'127.0.0.1:{db_port}:5432',
                            '-e', 'POSTGRES_DB=moqui', '-e', 'POSTGRES_USER=moqui', '-e', 'POSTGRES_PASSWORD=' + password,
                            'docker.io/library/postgres:18'], check=True, stdout=subprocess.DEVNULL)
            for _ in range(120):
                result = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'moqui'],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if result.returncode == 0:
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError('PostgreSQL did not become ready')
            environment.update(entity_ds_db_conf='postgres', entity_ds_host='127.0.0.1', entity_ds_port=str(db_port),
                               entity_ds_database='moqui', entity_ds_schema='public', entity_ds_user='moqui', entity_ds_password=password)
        else:
            environment.update(entity_ds_db_conf='h2', entity_ds_url='jdbc:h2:mem:deterministic-war;DB_CLOSE_DELAY=-1',
                               entity_ds_user='sa', entity_ds_password='')
        command = ['java', '-Dmoqui.runtime=' + str(runtime), '-Dmoqui.conf=conf/MoquiDevConf.xml',
                   '-Dbitronix.tm.serverId=deterministic-war-smoke', '-Dbitronix.tm.journal=null',
                   '-jar', str(root / 'moqui.war'), '--deterministic', 'no-run-es', f'port={port}']
        with (evidence / 'server.log').open('w') as output:
            process = subprocess.Popen(command, cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                assert process.poll() is None, 'WAR exited during initialization; see server.log'
                text = (evidence / 'server.log').read_text(errors='replace')
                if 'Error initializing webapp context:' in text:
                    raise RuntimeError('WAR webapp initialization failed; see server.log')
                if 'Moqui Framework initialized in' in text and 'Started oejs.Server@' in text:
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError('WAR readiness timed out; see server.log')
            for path in ('/status', '/Login'):
                with urllib.request.urlopen(f'http://127.0.0.1:{port}{path}', timeout=30) as response:
                    statuses[path] = response.status
                    assert response.status == 200
                    assert response.read()
            process.send_signal(signal.SIGTERM)
            process.wait(timeout=60)
        assert 'Moqui ExecutionContextFactory Destroyed' in (evidence / 'server.log').read_text(errors='replace')
        assert not errors, errors
        assert not active, f'uncompleted operations: {sorted(active)}'
        records = [json.loads(line) for line in (evidence / 'deterministic.log').read_text().splitlines()]
        traces = [record for record in records if record['event'] == 'trace']
        assert [record['trace_sequence'] for record in traces] == list(range(1, len(traces) + 1))
        assert any(record['kind'] == 'jdbc' and 'CREATE TABLE' in record['name'].upper() for record in traces)
        assert any(record['kind'] == 'data' and record['action'] == 'record' for record in traces)
        records = [record for record in records if record['event'] != 'trace']
        assert len(records) == len(events), (len(records), len(events))
        begins = [e for e in events if e['event'] == 'begin']
        assert len({e['sequence'] for e in begins}) == len(begins)
        statistics = None
        if args.postgres:
            # Confirm writes really reached this fresh PostgreSQL instance (no copied/seeded database).
            count = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'moqui', '-Atc',
                                             'select count(*) from enumeration'], text=True, stderr=subprocess.DEVNULL).strip()
            assert int(count) > 0
            saved = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'moqui', '-Atc',
                "select (select count(*) from artifact_hit), count(*), coalesce(sum(hit_count),0), "
                "count(*) filter (where artifact_name like '%Login%') from artifact_hit_bin"],
                text=True, stderr=subprocess.DEVNULL).strip()
            statistics = dict(zip(('artifact_hits', 'artifact_bins', 'bin_hit_count', 'login_bins'),
                                  map(int, saved.split('|'))))
            assert all(value > 0 for value in statistics.values()), statistics
        summary = {'database': 'postgres18' if args.postgres else 'h2-memory', 'http': statuses,
                   'operations': len(begins), 'outcomes': dict(collections.Counter(
                       e['outcome'] for e in events if e['event'] == 'complete')), 'uncompleted': len(active),
                   'persisted_statistics': statistics, 'random_draw_counts': dict(random_counts)}
        (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary))
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        if container is not None:
            subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        hook.shutdown()
        hook.server_close()
        (evidence / 'coordinator.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in events))


if __name__ == '__main__':
    main()
