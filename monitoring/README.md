# Monitoring Scripts

Two shell scripts for collecting metrics during performance tests.

## queue_stats.sh

Polls the **RabbitMQ Management API** every N seconds and prints queue depth + publish/consume rates for all 20 room queues.

```bash
# On the RabbitMQ EC2 instance (or any machine that can reach port 15672):
RABBIT_HOST=<rabbitmq-public-ip> \
RABBIT_USER=guest \
RABBIT_PASS=guest \
INTERVAL=5 \
bash queue_stats.sh
```

**What to watch:**
- `Depth` should stay < 1000 under normal load (good plateau profile)
- `PubRate/s` ≈ `ConsumeRate/s` means consumers are keeping up
- Sawtooth depth pattern means consumers are slower than producers → add more consumer threads

## server_metrics.sh

Polls `/metrics` on each `server-v2` instance and the `consumer` instance.

```bash
# 2-server setup:
SERVER1=<server1-ip>:8081 \
SERVER2=<server2-ip>:8081 \
CONSUMER=<consumer-ip>:8082 \
INTERVAL=5 \
bash server_metrics.sh
```

```bash
# 4-server setup:
SERVER1=<server1-ip>:8081 \
SERVER2=<server2-ip>:8081 \
SERVER3=<server3-ip>:8081 \
SERVER4=<server4-ip>:8081 \
CONSUMER=<consumer-ip>:8082 \
INTERVAL=5 \
bash server_metrics.sh
```

**Metrics returned:**
| Field | Description |
|---|---|
| `enqueued` | Total messages published to RabbitMQ (successes only) |
| `conn` | Active WebSocket connections right now |
| `failures` | Total RabbitMQ publish failures |
| `msgs/s` | Average publish rate since server start |
| `processed` (consumer) | Total messages broadcast to WebSocket clients |

## Prerequisites

- `curl` and `python3` must be available on the machine running the scripts
- RabbitMQ Management plugin enabled: `rabbitmq-plugins enable rabbitmq_management`
- Port `15672` open in RabbitMQ EC2 security group (from your laptop or monitoring host)
- Port `8081`/`8082` open in server/consumer EC2 security groups
