#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .demo/app.pid ]; then
    PID="$(cat .demo/app.pid)"
    if kill -0 "$PID" 2>/dev/null; then
        echo "==> Stopping app (pid=$PID)..."
        kill "$PID" 2>/dev/null || true
        # Gradle 'run' spawns a child JVM; kill the whole process group.
        pkill -P "$PID" 2>/dev/null || true
    fi
    rm -f .demo/app.pid
fi

# Catch any leftover gradle/JVM listening on 9080.
LEFTOVER=$(lsof -nP -iTCP:9080 -sTCP:LISTEN -t 2>/dev/null || true)
if [ -n "$LEFTOVER" ]; then
    echo "==> Killing leftover process(es) on :9080: $LEFTOVER"
    kill $LEFTOVER 2>/dev/null || true
fi

echo "==> Stopping docker compose stack..."
docker compose down

echo "Done."
