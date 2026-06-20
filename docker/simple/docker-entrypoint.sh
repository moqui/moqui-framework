#!/usr/bin/env sh
set -e

MOQUI_HOME="${MOQUI_HOME:-/opt/moqui}"
VENV_DIR="${VENV_DIR:-$MOQUI_HOME/runtime/python_venv}"
PORT="${PORT:-80}"

PYBIN="$VENV_DIR/bin/python"

if [ -x "$PYBIN" ]; then
    # JEP mode: resolve libjep.so from the venv and configure JVM flags
    unset PYTHONPATH
    export PYTHONNOUSERSITE=1

    read -r JEP_LIB SITE_PKGS <<EOF
$("$PYBIN" - <<'PY'
import sysconfig, os
site = sysconfig.get_paths().get('purelib') or ''
lib  = os.path.join(site, 'jep', 'libjep.so')
print((lib if os.path.isfile(lib) else '') + ' ' + site)
PY
)
EOF

    if [ -f "$JEP_LIB" ]; then
        export LD_LIBRARY_PATH="$(dirname "$JEP_LIB")${LD_LIBRARY_PATH:+:}$LD_LIBRARY_PATH"
        exec java -Djep.lib="$JEP_LIB" -Djep_site_pkgs="$SITE_PKGS" -cp . MoquiStart "port=$PORT" "$@"
    else
        echo "WARN: Python venv found but libjep.so not found at $JEP_LIB, starting without JEP" >&2
    fi
fi

# Standard start — no JEP venv present or libjep.so not found
exec java -cp . MoquiStart "port=$PORT" "$@"
