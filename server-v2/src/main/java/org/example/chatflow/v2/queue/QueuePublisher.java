package org.example.chatflow.v2.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.example.chatflow.v2.monitoring.ServerMetrics;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes chat messages to a RabbitMQ topic exchange.
 *
 * <p>Fix 3 – full three-state circuit breaker:
 * <ul>
 *   <li><b>CLOSED</b>    – normal operation; consecutive failures are counted.</li>
 *   <li><b>OPEN</b>      – fail-fast; no publish attempts are made.
 *       After {@code cooldownMillis} the breaker enters HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> – one trial publish is allowed (first thread wins CAS).
 *       Success → CLOSED + counter reset.  Failure → OPEN immediately.</li>
 * </ul>
 * The old implementation only set {@code circuitOpen = true} with no recovery
 * path, causing permanent publish downtime after any transient RabbitMQ error.
 */
public class QueuePublisher {

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final ChannelPool channelPool;
    private final ObjectMapper objectMapper;
    private final String exchangeName;
    private final String serverId;
    private final ServerMetrics serverMetrics;

    // ---- circuit-breaker state ----
    private final AtomicReference<CircuitState> circuitState =
            new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount   = new AtomicInteger(0);
    private final AtomicLong    openedAtMillis = new AtomicLong(0);
    private final int  failureThreshold;   // consecutive failures before opening
    private final long cooldownMillis;     // ms to wait before HALF_OPEN trial

    public QueuePublisher(String host,
                          int port,
                          String username,
                          String password,
                          String exchangeName,
                          int channelPoolSize,
                          ObjectMapper objectMapper,
                          String serverId,
                          ServerMetrics serverMetrics,
                          int failureThreshold,
                          long cooldownMillis) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        this.channelPool      = new ChannelPool(factory, channelPoolSize);
        this.exchangeName     = exchangeName;
        this.objectMapper     = objectMapper;
        this.serverId         = serverId;
        this.serverMetrics    = serverMetrics;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis   = cooldownMillis;
    }

    // ---- accessors used by metrics/health endpoints ----

    public CircuitState getCircuitState() {
        return circuitState.get();
    }

    public boolean isCircuitOpen() {
        return circuitState.get() == CircuitState.OPEN;
    }

    // ---- publish ----

    public void publish(String roomId,
                        String userId,
                        String username,
                        String message,
                        String messageType,
                        String clientIp,
                        String originalTimestampIso) throws IOException, InterruptedException {

        guardCircuit();   // throws if OPEN and cooldown not expired

        String messageId  = UUID.randomUUID().toString();
        String routingKey = "room." + roomId;

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId",   messageId);
        payload.put("roomId",      roomId);
        payload.put("userId",      userId);
        payload.put("username",    username);
        payload.put("message",     message);
        payload.put("timestamp",   originalTimestampIso);
        payload.put("messageType", messageType);
        payload.put("serverId",    serverId);
        payload.put("clientIp",    clientIp);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize queue message", e);
        }

        Channel channel = null;
        try {
            channel = channelPool.borrowChannel();

            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .timestamp(java.util.Date.from(OffsetDateTime.now().toInstant()))
                    .build();

            channel.basicPublish(exchangeName, routingKey, props, body);
            channel.waitForConfirmsOrDie(5000);

            onPublishSuccess();
            if (serverMetrics != null) serverMetrics.incrementEnqueued();

        } catch (Exception e) {
            onPublishFailure();
            if (serverMetrics != null) serverMetrics.incrementPublishFailures();
            throw new IOException("Failed to publish to RabbitMQ", e);
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    // ---- circuit-breaker helpers ----

    /**
     * Gate that decides whether a publish attempt should proceed.
     *
     * <ul>
     *   <li>CLOSED   → always proceed.</li>
     *   <li>OPEN     → check cooldown; on expiry the first thread to win the
     *       CAS transitions to HALF_OPEN and proceeds; all others fast-fail.</li>
     *   <li>HALF_OPEN → the trial thread is already in flight; other threads
     *       fast-fail to avoid saturating a potentially recovering broker.</li>
     * </ul>
     */
    private void guardCircuit() throws IOException {
        CircuitState current = circuitState.get();

        if (current == CircuitState.CLOSED) {
            return;
        }

        if (current == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAtMillis.get();
            if (elapsed < cooldownMillis) {
                throw new IOException("Circuit breaker OPEN — retry in "
                        + (cooldownMillis - elapsed) + " ms");
            }
            // Cooldown expired: one thread wins the race to HALF_OPEN
            if (circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                return;  // this thread may proceed with the trial publish
            }
            // Another thread already transitioned; this thread fast-fails
            throw new IOException("Circuit breaker transitioning — request rejected");
        }

        // HALF_OPEN: trial already in progress; reject concurrent requests
        throw new IOException("Circuit breaker HALF_OPEN — trial in progress, request rejected");
    }

    private void onPublishSuccess() {
        circuitState.set(CircuitState.CLOSED);
        failureCount.set(0);
    }

    private void onPublishFailure() {
        CircuitState current = circuitState.get();
        if (current == CircuitState.HALF_OPEN) {
            // Trial failed → reopen immediately
            openedAtMillis.set(System.currentTimeMillis());
            circuitState.set(CircuitState.OPEN);
        } else {
            // CLOSED: count up; open when threshold is reached
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                if (circuitState.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                    openedAtMillis.set(System.currentTimeMillis());
                }
            }
        }
    }

    public void close() throws IOException, TimeoutException {
        channelPool.shutdown();
    }
}
