#!/usr/bin/env bash
set -euo pipefail

OUTPUT_TOPIC="${OUTPUT_TOPIC:-orders-clean}"

echo "==> Consuming deduplicated stream from $OUTPUT_TOPIC. Ctrl-C to stop."
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic "$OUTPUT_TOPIC" \
    --from-beginning \
    --property print.key=true \
    --property key.separator=' | '
