#! /bin/bash
# HTTP security proofs in framework/test/. Moqui must already be listening
# with MoquiProductionConf.xml, e.g.:
#   java -jar moqui.war conf=conf/MoquiProductionConf.xml
# Extra args go to pytest, e.g. ./pytest.sh -v -k cors
# DevConf is expected to fail (CORS, tarpit, H2 console).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TEST_DIR="$ROOT/framework/test"
VENV="$TEST_DIR/.venv"
REQ="$TEST_DIR/requirements.txt"
STAMP="$VENV/.requirements-stamp"

if [ ! -f "$REQ" ]; then
    echo "Missing $REQ" >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required" >&2
    exit 1
fi

if [ ! -x "$VENV/bin/python" ]; then
    python3 -m venv "$VENV"
fi

need_install=0
if [ ! -x "$VENV/bin/pytest" ]; then
    need_install=1
elif [ ! -f "$STAMP" ] || [ "$REQ" -nt "$STAMP" ]; then
    need_install=1
fi
if [ "$need_install" -eq 1 ]; then
    "$VENV/bin/pip" install -r "$REQ"
    touch "$STAMP"
fi

cd "$TEST_DIR"
exec "$VENV/bin/pytest" "$@"
