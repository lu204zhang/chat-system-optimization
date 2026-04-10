# ChatFlow HW04 — Developer Runbook

Quick reference to run **server-v2** (HTTP + WebSocket ingress → RabbitMQ) and **consumer-v3** (RabbitMQ → Redis fanout + WebSocket broadcast + PostgreSQL).

---

## Prerequisites

- **JDK 17**, **Maven 3.x**
- **Docker** (for PostgreSQL, Redis, and optionally RabbitMQ)
- **RabbitMQ** reachable at `localhost:5672` with exchange **`chat.exchange`** and queues **`room.1` … `room.20`** bound by routing key (see course / `deployment/README-part3.md`). Default creds in properties: `guest` / `guest`.

---

## 1. Start dependencies

From the repo root, use the `database/` compose files:

```bash
cd database
docker compose -f docker-compose.postgres.yml up -d
docker compose -f docker-compose.redis.yml up -d
```

| Service    | Default URL / port | Notes |
|-----------|---------------------|--------|
| PostgreSQL | `localhost:5432`, DB `chatflow`, user `chatuser` / `chatpassword` | Schema loaded from `database/schema.sql` on first start |
| Redis      | `localhost:6379` | Required when `chat.fanout.redis.enabled=true` (default in consumer) |

**RabbitMQ** (if not already running), example:

```bash
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

Management UI: `http://localhost:15672`.

---

## 2. Run the applications

**Server** (default port **8081**):

```bash
cd server-v2
mvn spring-boot:run
```

**Consumer** (default port **8082**):

```bash
cd consumer-v3
mvn spring-boot:run
```

**Second consumer instance** (different HTTP port; same Rabbit + Redis):

```bash
cd consumer-v3
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

Or override in `consumer-v3/src/main/resources/application.properties` / env / profile.

---

## 3. Endpoints (local defaults)

| App        | Health        | Metrics / observability |
|-----------|---------------|-------------------------|
| server-v2 | `GET http://localhost:8081/health` | `GET http://localhost:8081/metrics` |
| consumer-v3 | `GET http://localhost:8082/health` | `GET /metrics` (runtime counters); `GET /api/metrics` (assignment analytics / DB — needs PostgreSQL + data) |

**WebSocket paths**

- **server-v2**: `ws://localhost:8081/chat/{roomId}` (e.g. `room.1` … `room.20` per assignment)
- **consumer-v3**: `ws://localhost:8082/chat/{roomId}` — clients receive broadcasts drained from RabbitMQ (and cross-instance via Redis when fanout is on)

---

## 4. Configuration knobs (consumer)

Defined in `consumer-v3/src/main/resources/application.properties`:

- **`chat.fanout.redis.enabled`** — cross-instance fanout via Redis; requires Redis up
- **`chat.consumer.scaler.enabled`** — dynamic worker scaling (default **off** for multi-instance safety)
- **`chat.db.stats.enabled`** — periodic DB stats (needs PostgreSQL)
- Rabbit / Redis / JDBC hosts and ports for non-local deploys

---

## 5. What to check under load

On **`/metrics`** for each consumer:

- **`local_broadcast_failure`** should stay **0** if WebSocket writes are healthy
- **`redis_publish_failure`** / **`redis_subscribe_received`** vs **`local_broadcast_success`** — should line up with your topology (each subscriber counts all channel messages)

With **N** consumer JVMs subscribed to the same Redis channels, **each** instance’s `redis_subscribe_received` reflects **total publishes seen by that subscriber**, not `1/N` of traffic.

---

## 6. Troubleshooting (short)

| Symptom | Likely cause |
|--------|----------------|
| Consumer fails to start after enabling Redis fanout | Redis not running or wrong `spring.data.redis.*` |
| DB errors / stats failures | PostgreSQL not running or wrong JDBC URL / credentials |
| AMQP connection refused | RabbitMQ not running or firewall / wrong `chat.rabbitmq.host` |
| **`TEXT_PARTIAL_WRITING`** on broadcast | Concurrent `sendMessage` on the same session — **should be prevented** by per-session synchronization in `RoomManager.broadcastToRoom` |

