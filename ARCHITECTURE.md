# CS6650 Assignment 2 — Architecture Document

## 1. System Architecture

### Component Overview

| Component | Technology | Role |
|---|---|---|
| Client | Java (Assignment 1) | Sends WebSocket messages to the system |
| Application Load Balancer | AWS ALB | Distributes connections across server-v2 instances |
| server-v2 (×2–4) | Spring Boot / Java 17 | Accepts WebSocket connections; publishes messages to RabbitMQ |
| RabbitMQ | RabbitMQ 3.x, EC2 | Topic exchange + 20 room queues; durable message broker |
| Consumer | Spring Boot / Java 17 | Pulls from RabbitMQ; broadcasts to connected WebSocket clients |

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                   Clients (Java, multi-threaded)             │
│        64–512 threads, each: JOIN → TEXT×N → LEAVE           │
└─────────────────────────┬────────────────────────────────────┘
                          │  ws://ALB/chat/{roomId}
                          ▼
┌──────────────────────────────────────────────────────────────┐
│         AWS Application Load Balancer (ALB)                  │
│  • Sticky sessions (AWSALB cookie, 60 min)                   │
│  • WebSocket support (HTTP → WS upgrade, idle timeout 120s)  │
│  • Health check: GET /health, 30s interval                   │
└──────────┬───────────────────────────┬───────────────────────┘
           │                           │
           ▼                           ▼
┌──────────────────┐         ┌──────────────────┐
│  server-v2-1     │         │  server-v2-2     │  (+ server-v2-3/4)
│  :8081           │         │  :8081           │
│  ChannelPool×16  │         │  ChannelPool×16  │
│  circuit breaker │         │  circuit breaker │
└────────┬─────────┘         └────────┬─────────┘
         │                            │
         └─────────────┬──────────────┘
                       │  AMQP :5672
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                  RabbitMQ EC2 :5672                          │
│  Exchange: chat.exchange  (type: topic, durable)             │
│  Queues:   room.1 … room.20  (durable, persistent msgs)      │
│  Bindings: room.N queue ← routing key "room.N"               │
│  Management UI: :15672                                       │
└──────────────────────────────┬───────────────────────────────┘
                               │  AMQP :5672
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                  Consumer EC2 :8082                          │
│  ConsumerChannelManager (single shared Connection)           │
│  ConsumerWorker × 10 threads (configurable, push-based)      │
│    Thread 0 → basicConsume(room.1, room.11)                  │
│    Thread 1 → basicConsume(room.2, room.12)  (round-robin)   │
│    …                                                         │
│    Thread 9 → basicConsume(room.10, room.20)                 │
│  RoomManager                                                 │
│    ConcurrentHashMap<roomId, Set<WebSocketSession>>          │
│    ConcurrentHashMap<messageId, Boolean>  (dedup)            │
│    AtomicLong messagesProcessed                              │
│  WebSocket endpoint: /chat/{roomId}  (receiving clients)     │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Message Flow Sequence

```
Client          ALB         server-v2        RabbitMQ        Consumer        Receivers
  │               │              │               │               │               │
  │─ ws connect ─▶│              │               │               │               │
  │               │─ sticky ────▶│               │               │               │
  │               │              │─ afterConn    │               │               │
  │               │              │  established  │               │               │
  │               │              │               │               │               │
  │─ JOIN msg ───▶│─────────────▶│               │               │               │
  │               │              │ validate      │               │               │
  │               │              │ state machine │               │               │
  │               │              │─ publish ────▶│               │               │
  │               │              │  (topic exch) │               │               │
  │               │              │  routing key  │               │               │
  │               │              │  "room.{id}"  │               │               │
  │               │              │◀─ confirms ───│               │               │
  │◀─ ENQUEUED ───│◀─────────────│               │               │               │
  │               │              │               │               │               │
  │─ TEXT msg ───▶│─────────────▶│               │               │               │
  │               │              │─ publish ────▶│               │               │
  │               │              │◀─ confirms ───│               │               │
  │◀─ ENQUEUED ───│◀─────────────│               │               │               │
  │               │              │               │               │               │
  │               │              │               │─ push msg ───▶│               │
  │               │              │               │  (basicConsume│               │
  │               │              │               │   DeliverCb)  │               │
  │               │              │               │               │ dedup check   │
  │               │              │               │               │ broadcastTo   │
  │               │              │               │               │ Room(roomId)  │
  │               │              │               │               │──────────────▶│
  │               │              │               │◀─ basicAck ───│               │
  │               │              │               │               │               │
  │─ LEAVE msg ──▶│─────────────▶│               │               │               │
  │               │              │─ publish ────▶│               │               │
  │               │              │◀─ confirms ───│               │               │
  │◀─ ENQUEUED ───│◀─────────────│               │               │               │
```

---

## 3. Queue Topology Design

**Exchange:** `chat.exchange` (type: `topic`, durable: true)

**Routing key pattern:** `room.{roomId}` (e.g. `room.1`, `room.7`)

**Queues:** `room.1` through `room.20` — each bound with exact routing key match

```
chat.exchange (topic)
 ├─ binding "room.1"  ──▶  queue: room.1   (durable, persistent, max 10k msgs)
 ├─ binding "room.2"  ──▶  queue: room.2
 │   …
 └─ binding "room.20" ──▶  queue: room.20
```

**Message properties:**
- `deliveryMode = 2` (persistent — survives broker restart)
- `contentType = application/json`
- Publisher confirms enabled (`channel.confirmSelect()` + `waitForConfirmsOrDie(5000ms)`)

**Per-room isolation** ensures one noisy room cannot starve another and allows per-room consumer assignment.

---

## 4. Consumer Threading Model

```
ConsumerConfig (Spring bean)
│
├── ConsumerChannelManager  (1 shared AMQP Connection)
│
└── ExecutorService (fixed pool, threadCount=10 by default)
     │
     ├── ConsumerWorker-0  owns Channel-0, polls: room.1,  room.11
     ├── ConsumerWorker-1  owns Channel-1, polls: room.2,  room.12
     ├── ConsumerWorker-2  owns Channel-2, polls: room.3,  room.13
     ├── ConsumerWorker-3  owns Channel-3, polls: room.4,  room.14
     ├── ConsumerWorker-4  owns Channel-4, polls: room.5,  room.15
     ├── ConsumerWorker-5  owns Channel-5, polls: room.6,  room.16
     ├── ConsumerWorker-6  owns Channel-6, polls: room.7,  room.17
     ├── ConsumerWorker-7  owns Channel-7, polls: room.8,  room.18
     ├── ConsumerWorker-8  owns Channel-8, polls: room.9,  room.19
     └── ConsumerWorker-9  owns Channel-9, polls: room.10, room.20
```

**Each worker lifecycle:**
1. Creates its own `Channel`, sets `basicQos(prefetch)` — broker will push at most `prefetch` unacked messages at a time
2. Calls `channel.basicConsume(queue, ...)` for each assigned queue, registering a `DeliverCallback`
3. Worker thread blocks in a sleep loop; message delivery happens on the RabbitMQ client's dispatch thread

**Per-message `DeliverCallback`:**
1. Check `messageId` in `processedMessageIds` ConcurrentHashMap — `basicAck` and skip if duplicate
2. Call `RoomManager.broadcastToRoom(roomId, payload)` — fan out to all WebSocket sessions in the room
3. `basicAck(deliveryTag)` — acknowledge to broker
4. On exception: `basicNack(deliveryTag, false, requeue=true)` — return to queue for retry

Push consumers are more efficient than pull (`basicGet`) under high load: the broker proactively delivers messages as soon as they arrive, and `basicQos` prefetch provides natural back-pressure by limiting in-flight unacked messages.

**Thread-safe structures in RoomManager:**
```java
ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions  // room → active sessions
ConcurrentHashMap<String, Boolean> processedMessageIds          // at-least-once dedup
AtomicLong messagesProcessed                                    // throughput counter
```

---

## 5. Load Balancing Configuration

**ALB Settings:**
| Parameter | Value | Reason |
|---|---|---|
| Type | Application Load Balancer | Supports HTTP/WebSocket upgrade |
| Listener | HTTP :80 | Receives client connections |
| Target group protocol | HTTP :8081 | Forwards to server-v2 instances |
| Health check path | `/health` | Returns 200 "OK" when server is up |
| Health check interval | 30 s | Detect failures within ~90s |
| Healthy threshold | 2 | Quick recovery on restart |
| Unhealthy threshold | 3 | Avoid flapping |
| Sticky sessions | AWSALB cookie, 60 min | Required: WebSocket is stateful |
| Idle timeout | 120 s | Keeps long-lived WS connections alive |

**Why sticky sessions are required:** A WebSocket connection is a persistent TCP upgrade — all frames must travel to the same backend. Without stickiness, a new request from the same IP could land on a different server that has no knowledge of the WebSocket state.

---

## 6. Failure Handling Strategies

| Failure Scenario | Detection | Response |
|---|---|---|
| RabbitMQ publish timeout | `waitForConfirmsOrDie(5000ms)` throws | Circuit breaker opens; client receives `QUEUE_ERROR` |
| Circuit breaker open | `circuitOpen.get() == true` | All publishes fail-fast; prevents thread pile-up |
| Channel pool exhausted | `BlockingQueue.take()` blocks | Natural back-pressure; no unbounded channel creation |
| Consumer broadcast error | Per-session `IOException` caught | Logged, other sessions in room still receive the message |
| Consumer message processing error | `basicNack(requeue=true)` | Message returned to queue for retry by same/other worker |
| Server instance unhealthy | ALB health check fails ×3 | Instance removed from target group; clients reconnect to healthy instance |
| Duplicate message delivery | `processedMessageIds.putIfAbsent` | Duplicate silently discarded (at-least-once + dedup = effectively-once) |
