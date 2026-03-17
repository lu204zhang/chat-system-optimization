#!/usr/bin/env bash
# Polls /metrics on one or more server-v2 instances and the consumer,
# printing messages/sec, active connections, and error rates.
#
# Usage:
#   SERVER1=<ip>:8081 SERVER2=<ip>:8081 CONSUMER=<ip>:8082 ./server_metrics.sh
#
# Override any variable via environment:
#   SERVER1   - First server-v2 host:port   (default: localhost:8081)
#   SERVER2   - Second server-v2 host:port  (optional)
#   SERVER3   - Third server-v2 host:port   (optional)
#   SERVER4   - Fourth server-v2 host:port  (optional)
#   CONSUMER  - Consumer host:port          (default: localhost:8082)
#   INTERVAL  - Seconds between polls       (default: 5)

SERVER1="${SERVER1:-localhost:8081}"
SERVER2="${SERVER2:-}"
SERVER3="${SERVER3:-}"
SERVER4="${SERVER4:-}"
CONSUMER="${CONSUMER:-localhost:8082}"
INTERVAL="${INTERVAL:-5}"

poll_server() {
    local label="$1"
    local host="$2"
    local resp
    resp=$(curl -s --connect-timeout 3 "http://${host}/metrics" 2>/dev/null)
    if [ -z "$resp" ]; then
        echo "  ${label}: unreachable"
        return
    fi
    local enqueued conns failures mps
    enqueued=$(echo "$resp" | grep -o '"messagesEnqueued":[0-9]*'       | grep -o '[0-9]*$')
    conns=$(echo "$resp"    | grep -o '"activeConnections":[0-9]*'       | grep -o '[0-9]*$')
    failures=$(echo "$resp" | grep -o '"queuePublishFailures":[0-9]*'    | grep -o '[0-9]*$')
    mps=$(echo "$resp"      | grep -o '"messagesPerSecondApprox":[0-9.]*'| grep -o '[0-9.]*$')
    enqueued="${enqueued:-0}"; conns="${conns:-0}"; failures="${failures:-0}"; mps="${mps:-0}"
    echo "  ${label}: enqueued=${enqueued}  conn=${conns}  failures=${failures}  msgs/s≈${mps}"
}

poll_consumer() {
    local host="$1"
    local resp
    resp=$(curl -s --connect-timeout 3 "http://${host}/metrics" 2>/dev/null)
    if [ -z "$resp" ]; then
        echo "  Consumer: unreachable"
        return
    fi
    local processed mps
    processed=$(echo "$resp" | grep -o '"messagesProcessed":[0-9]*'      | grep -o '[0-9]*$')
    mps=$(echo "$resp"       | grep -o '"messagesPerSecondApprox":[0-9.]*'| grep -o '[0-9.]*$')
    processed="${processed:-0}"; mps="${mps:-0}"
    echo "  Consumer: processed=${processed}  msgs/s≈${mps}"
}

echo "Polling server metrics every ${INTERVAL}s — Ctrl+C to stop"
echo ""

while true; do
    echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
    poll_server "Server1 (${SERVER1})" "$SERVER1"
    [ -n "$SERVER2" ] && poll_server "Server2 (${SERVER2})" "$SERVER2"
    [ -n "$SERVER3" ] && poll_server "Server3 (${SERVER3})" "$SERVER3"
    [ -n "$SERVER4" ] && poll_server "Server4 (${SERVER4})" "$SERVER4"
    poll_consumer "$CONSUMER"
    echo ""
    sleep "$INTERVAL"
done
