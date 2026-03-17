## Assignment 2 - Part 3: Load Balancing Setup (ALB + Multiple Servers)

This describes how to deploy and test **Part 3** once `server-v2` and the `consumer` are working locally.

### 1. Components you will run on AWS

- **RabbitMQ instance** (EC2)  
  - Runs the `chat.exchange` and `room.1`–`room.20` queues.
- **WebSocket servers (server-v2)**  
  - 2–4 EC2 instances, each running the Spring Boot `server-v2` app.
- **Consumer app**  
  - 1 EC2 instance running the `consumer` Spring Boot app.
- **Application Load Balancer (ALB)**  
  - Accepts client WebSocket connections and load-balances across the `server-v2` instances.

### 2. Prepare RabbitMQ on EC2

1. Launch an EC2 instance (e.g. `t3.small`, Amazon Linux 2 or Ubuntu).
2. Install Erlang and RabbitMQ (similar to your local setup).
3. Enable management plugin:
   - `rabbitmq-plugins enable rabbitmq_management`
4. Open security-group inbound rules:
   - TCP `5672` (AMQP) from your **server-v2** and **consumer** instances.
   - TCP `15672` from your IP (for management UI, optional).
5. Configure:
   - Topic exchange `chat.exchange`
   - Queues `room.1`–`room.20`
   - Bindings: `room.N` queue with routing key `room.N`

### 3. Configure and run `server-v2` instances on EC2

1. Launch 2–4 EC2 instances (e.g. `t3.small`) in the same VPC as RabbitMQ.
2. Copy the `server-v2` project (via `git clone` or SCP).
3. Update `server-v2/src/main/resources/application.properties`:

   ```properties
   server.port=8081
   chat.rabbitmq.host=<rabbitmq-private-ip-or-hostname>
   chat.rabbitmq.port=5672
   chat.rabbitmq.username=guest
   chat.rabbitmq.password=guest
   chat.rabbitmq.exchange=chat.exchange
   chat.rabbitmq.channelPoolSize=32

   chat.serverId=server-v2-1   # change per instance, e.g. server-v2-2, etc.
   ```

4. Build and run on each instance:

   ```bash
   mvn clean package
   mvn spring-boot:run
   ```

5. Confirm each instance responds on:
   - `http://<instance-private-ip>:8081/health` → `OK`

### 4. Configure and run the consumer on EC2

1. Launch a separate EC2 instance (or reuse one if you prefer) in the same VPC.
2. Copy the `consumer` project.
3. Update `consumer/src/main/resources/application.properties`:

   ```properties
   server.port=8082
   chat.rabbitmq.host=<rabbitmq-private-ip-or-hostname>
   chat.rabbitmq.port=5672
   chat.rabbitmq.username=guest
   chat.rabbitmq.password=guest
   chat.consumer.threadCount=20
   chat.consumer.prefetch=100
   ```

4. Build and run:

   ```bash
   mvn clean package
   mvn spring-boot:run
   ```

5. Optionally open a WebSocket connection to `ws://<consumer-instance>:8082/chat/1` to verify that broadcasts from the queues are received.

### 5. Create the Application Load Balancer

1. In AWS console, go to **EC2 → Load Balancers → Create load balancer**.
2. Choose **Application Load Balancer**.
3. Configure:
   - Scheme: Internet-facing (for external clients) or internal (for only internal clients).
   - Listeners: HTTP on port 80 (you can later add HTTPS).
4. Create a **target group**:
   - Target type: Instances.
   - Protocol: HTTP.
   - Port: `8081`.
   - Health check:
     - Protocol: HTTP
     - Path: `/health`
     - Interval: 30s
     - Timeout: 5s
     - Healthy threshold: 2
     - Unhealthy threshold: 3
5. Register your `server-v2` EC2 instances as targets in this target group.
6. In the ALB listener rule for port 80:
   - Forward all traffic (path `/chat/*`) to this target group.

### 6. Enable WebSocket and sticky sessions on ALB

1. ALB already supports WebSockets over HTTP/1.1; just ensure:
   - Idle timeout is increased (e.g. to 120 seconds or more).
2. For sticky sessions:
   - In the target group’s attributes:
     - Enable **stickiness**.
     - Use an ALB-generated cookie.
     - Set a reasonable duration (e.g. 60 minutes).

This keeps a WebSocket client pinned to the same `server-v2` instance.

### 7. Testing Part 3 from your laptop

1. Get the **DNS name** of your ALB (e.g. `a1b2c3d4e5f6.us-west-2.elb.amazonaws.com`).
2. Open [WebSocketKing](https://websocketking.com/).
3. Connect to:

   ```text
   ws://<your-alb-dns-name>/chat/1
   ```

4. Send a `JOIN` and `TEXT` message like you did locally.
5. In RabbitMQ UI on EC2:
   - Confirm messages are flowing into `room.1`.
6. In your **consumer** WebSocket endpoint (`ws://<consumer-instance>:8082/chat/1`):
   - Confirm the broadcasted messages show up.

### 8. Verifying load distribution and stickiness

1. In the **ALB target group** metrics / console:
   - Check that connections and requests are distributed across your `server-v2` instances.
2. For stickiness:
   - Open 2–4 WebSocketKing tabs, all connecting via the ALB.
   - In each `server-v2` instance logs, confirm that once a connection is established, subsequent messages from that client keep hitting the same instance.

This completes the Part 3 requirements: ALB in front of multiple WebSocket servers, `/health` endpoint, sticky sessions, and load-balanced message flow into the queue and out through the consumer.

