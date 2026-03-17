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
import java.util.concurrent.atomic.AtomicBoolean;

public class QueuePublisher {

    private final ChannelPool channelPool;
    private final ObjectMapper objectMapper;
    private final String exchangeName;
    private final String serverId;
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final ServerMetrics serverMetrics;

    public QueuePublisher(String host,
                          int port,
                          String username,
                          String password,
                          String exchangeName,
                          int channelPoolSize,
                          ObjectMapper objectMapper,
                          String serverId,
                          ServerMetrics serverMetrics) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        this.channelPool = new ChannelPool(factory, channelPoolSize);
        this.exchangeName = exchangeName;
        this.objectMapper = objectMapper;
        this.serverId = serverId;
        this.serverMetrics = serverMetrics;
    }

    public boolean isCircuitOpen() {
        return circuitOpen.get();
    }

    public void publish(String roomId,
                        String userId,
                        String username,
                        String message,
                        String messageType,
                        String clientIp,
                        String originalTimestampIso) throws IOException, InterruptedException {
        if (circuitOpen.get()) {
            throw new IOException("Circuit breaker open for queue publishing");
        }

        String messageId = UUID.randomUUID().toString();
        String routingKey = "room." + roomId;

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId);
        payload.put("roomId", roomId);
        payload.put("userId", userId);
        payload.put("username", username);
        payload.put("message", message);
        payload.put("timestamp", originalTimestampIso);
        payload.put("messageType", messageType);
        payload.put("serverId", serverId);
        payload.put("clientIp", clientIp);

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
            if (serverMetrics != null) {
                serverMetrics.incrementEnqueued();
            }
        } catch (Exception e) {
            circuitOpen.set(true);
            if (serverMetrics != null) {
                serverMetrics.incrementPublishFailures();
            }
            throw new IOException("Failed to publish to RabbitMQ", e);
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    public void close() throws IOException, TimeoutException {
        channelPool.shutdown();
    }
}

