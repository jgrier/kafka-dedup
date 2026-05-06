#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export INPUT_TOPIC="${INPUT_TOPIC:-orders-raw}"
export MESSAGE_COUNT="${MESSAGE_COUNT:-100}"
export DUPLICATE_RATE="${DUPLICATE_RATE:-0.3}"

echo "==> Generating $MESSAGE_COUNT messages (~${DUPLICATE_RATE} duplicate rate) -> $INPUT_TOPIC"
./gradlew :demo:runProducer --quiet --console=plain
