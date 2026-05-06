#!/usr/bin/env bash
set -euo pipefail

INPUT_TOPIC="${INPUT_TOPIC:-orders-raw}"

echo "==> Consuming dirty stream from $INPUT_TOPIC (with duplicates). Ctrl-C to stop."
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic "$INPUT_TOPIC" \
    --from-beginning \
    --property print.key=true \
    --property key.separator=' | '
