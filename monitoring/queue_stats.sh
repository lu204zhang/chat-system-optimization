#!/usr/bin/env bash
# Polls the RabbitMQ Management API every INTERVAL seconds and prints
# queue depth + publish/consume rates for room.1 through room.20.
#
# Usage:
#   RABBIT_HOST=<rabbitmq-public-ip> ./queue_stats.sh
#
# Override any variable via environment:
#   RABBIT_HOST  - RabbitMQ host           (default: localhost)
#   RABBIT_PORT  - Management UI port      (default: 15672)
#   RABBIT_USER  - RabbitMQ username       (default: guest)
#   RABBIT_PASS  - RabbitMQ password       (default: guest)
#   INTERVAL     - Seconds between polls   (default: 5)
#   ROOMS        - Number of rooms to poll (default: 20)

RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_PORT="${RABBIT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
INTERVAL="${INTERVAL:-5}"
ROOMS="${ROOMS:-20}"

BASE_URL="http://${RABBIT_HOST}:${RABBIT_PORT}/api/queues/%2F"

echo "Polling RabbitMQ at ${RABBIT_HOST}:${RABBIT_PORT} every ${INTERVAL}s — Ctrl+C to stop"
printf "%-22s %-10s %8s %8s %12s %12s\n" "Timestamp" "Queue" "Depth" "Consumers" "PubRate/s" "ConsumeRate/s"
echo "--------------------------------------------------------------------------------"

while true; do
    TS=$(date +"%Y-%m-%d %H:%M:%S")
    TOTAL_DEPTH=0
    for i in $(seq 1 "$ROOMS"); do
        QUEUE="room.${i}"
        RESP=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
            "${BASE_URL}/${QUEUE}" 2>/dev/null)
        if [ -z "$RESP" ] || echo "$RESP" | grep -q '"error"'; then
            continue
        fi
        MSGS=$(echo "$RESP"      | grep -o '"messages":[0-9]*'              | head -1 | grep -o '[0-9]*$')
        CONSUMERS=$(echo "$RESP" | grep -o '"consumers":[0-9]*'             | head -1 | grep -o '[0-9]*$')
        PUB=$(echo "$RESP"       | grep -o '"publish_details":{"rate":[0-9.]*' | grep -o '[0-9.]*$')
        DEL=$(echo "$RESP"       | grep -o '"deliver_get_details":{"rate":[0-9.]*' | grep -o '[0-9.]*$')
        MSGS="${MSGS:-0}"; CONSUMERS="${CONSUMERS:-0}"; PUB="${PUB:-0}"; DEL="${DEL:-0}"
        TOTAL_DEPTH=$((TOTAL_DEPTH + MSGS))
        if [ "$MSGS" -gt 0 ] || [ "$PUB" != "0" ] || [ "$DEL" != "0" ]; then
            printf "%-22s %-10s %8s %8s %12s %12s\n" "$TS" "$QUEUE" "$MSGS" "$CONSUMERS" "$PUB" "$DEL"
        fi
    done
    echo "--- Total queue depth: ${TOTAL_DEPTH} ---"
    sleep "$INTERVAL"
done
