#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

INPUT_TOPIC="${INPUT_TOPIC:-orders-raw}"
OUTPUT_TOPIC="${OUTPUT_TOPIC:-orders-clean}"
RESTATE_ADMIN_URL="${RESTATE_ADMIN_URL:-http://localhost:9070}"
APP_URL="${APP_URL:-http://host.docker.internal:9080}"

echo "==> Starting docker compose stack (Kafka + Restate)..."
docker compose up -d --wait

echo "==> Creating Kafka topics if missing..."
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists --partitions 3 --replication-factor 1 \
    --topic "$INPUT_TOPIC" >/dev/null
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists --partitions 3 --replication-factor 1 \
    --topic "$OUTPUT_TOPIC" >/dev/null

echo "==> Starting demo application (DemoMain) in background on :9080..."
mkdir -p .demo
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export INPUT_TOPIC OUTPUT_TOPIC
nohup ./gradlew :demo:run --quiet > .demo/app.log 2>&1 &
APP_PID=$!
echo "$APP_PID" > .demo/app.pid
echo "    pid=$APP_PID  log=.demo/app.log"

echo "==> Waiting for app to listen on :9080..."
for i in $(seq 1 60); do
    if nc -z localhost 9080 2>/dev/null; then
        break
    fi
    sleep 1
    if [ "$i" = 60 ]; then
        echo "ERROR: app did not start. Tail of .demo/app.log:"
        tail -40 .demo/app.log
        exit 1
    fi
done
echo "    ok"

echo "==> Registering app endpoint with Restate..."
curl -sf -X POST "$RESTATE_ADMIN_URL/deployments" \
    -H 'content-type: application/json' \
    -d "{\"uri\": \"$APP_URL\"}" \
    | python3 -m json.tool 2>/dev/null || true

echo "==> Registering Kafka subscription ($INPUT_TOPIC -> OrderProcessor/process)..."
curl -sf -X POST "$RESTATE_ADMIN_URL/subscriptions" \
    -H 'content-type: application/json' \
    -d "{
        \"source\": \"kafka://default/$INPUT_TOPIC\",
        \"sink\": \"service://OrderProcessor/process\"
    }" \
    | python3 -m json.tool 2>/dev/null || true

echo ""
echo "==> Stack ready."
echo "    Restate admin: $RESTATE_ADMIN_URL"
echo "    App log:       .demo/app.log"
echo ""
echo "Suggested terminals:"
echo "    Terminal A (raw):        ./bin/consume-raw.sh"
echo "    Terminal B (deduped):    ./bin/consume-deduped.sh"
echo "    Terminal C (producer):   ./bin/produce.sh"
