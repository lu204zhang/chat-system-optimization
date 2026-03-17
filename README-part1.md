## Assignment 2 - Part 1 (Server V2 with RabbitMQ)

- **Choice**: This implementation uses **RabbitMQ (Option A)** rather than AWS SQS.
- **Why RabbitMQ**:
  - Native support for **topic exchanges** and routing keys like `room.{roomId}`, matching the assignment’s exchange/queue topology exactly.
  - Easier local development and benchmarking: you can run RabbitMQ on an EC2 instance or locally without managing AWS credentials, IAM roles, or per-request network overhead to SQS.
  - Rich visibility via the RabbitMQ Management UI (queue depth, publish rate, consumer rate), which aligns well with the monitoring and tuning parts of the assignment.
  - Fine-grained control over connection and **channel pooling**, publisher confirms, TTLs, and queue limits, which is useful for demonstrating connection management and reliability features.

### What Part 1 Implements

- A new Spring Boot server in `Assignment 2/server-v2`:
  - WebSocket endpoint: `/chat/{roomId}` (same shape as Assignment 1).
  - On each **valid** message it:
    - Enforces the same session state machine: `JOIN` → `TEXT` → `LEAVE`.
    - Builds a queue message with the required fields:
      - `messageId`, `roomId`, `userId`, `username`, `message`, `timestamp`, `messageType`, `serverId`, `clientIp`.
    - Publishes to a RabbitMQ **topic exchange** (name: `chat.exchange` by default) using routing key `room.{roomId}`.
    - Returns a small JSON ack to the client: `{ status: "ENQUEUED", ... }`.
- RabbitMQ integration:
  - `QueuePublisher` wraps RabbitMQ publishing, uses **publisher confirms** and a **ChannelPool**.
  - `ChannelPool` pre-creates a fixed number of channels from a single connection and safely borrows/returns channels.
  - Basic circuit-breaker behavior: if publishing fails, the publisher flips a flag so subsequent calls fail fast instead of hanging.

### How to Run Server V2

1. Make sure RabbitMQ is running and has a topic exchange named `chat.exchange`.
   - Example (on your RabbitMQ node):
     - Create exchange: type `topic`, name `chat.exchange`.
     - Create queues: `room.1` … `room.20`.
     - Bind each queue with routing key `room.<roomId>`.
2. From the `Assignment 2/server-v2` directory:
   - Build: `mvn clean package`
   - Run: `mvn spring-boot:run`
3. Connect your existing clients (from Assignment 1) to the new WebSocket URL, e.g.:
   - `ws://<server-host>:8081/chat/1`

