package org.example.chatflow.consumer.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import org.example.chatflow.consumer.database.BatchWriteBuffer;
import org.example.chatflow.consumer.database.ChatMessage;
import org.example.chatflow.consumer.websocket.RoomManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class ConsumerWorker implements Runnable {

    private final ConsumerChannelManager channelManager;
    private final List<String>           queues;
    private final ObjectMapper           objectMapper;
    private final RoomManager            roomManager;
    private final BatchWriteBuffer       writeBuffer;
    private final int                    prefetch;
    private volatile boolean             running = true;

    public ConsumerWorker(ConsumerChannelManager channelManager,
                          List<String> queues,
                          ObjectMapper objectMapper,
                          RoomManager roomManager,
                          BatchWriteBuffer writeBuffer,
                          int prefetch) {
        this.channelManager = channelManager;
        this.queues         = queues;
        this.objectMapper   = objectMapper;
        this.roomManager    = roomManager;
        this.writeBuffer    = writeBuffer;
        this.prefetch       = prefetch;
    }

    @Override
    public void run() {
        Channel channel = null;
        try {
            channel = channelManager.createChannel();
            channel.basicQos(prefetch);
            registerConsumers(channel);

            // Block this thread while the RabbitMQ client dispatch thread pushes messages
            while (running && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // channel or connection issue; worker exits
        } finally {
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException | TimeoutException ignored) {
                }
            }
        }
    }

    private void registerConsumers(Channel channel) throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            try {
                String   body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                JsonNode node = objectMapper.readTree(body);

                String messageId   = node.hasNonNull("messageId")   ? node.get("messageId").asText()   : null;
                String roomId      = node.hasNonNull("roomId")       ? node.get("roomId").asText()       : null;
                String userId      = node.hasNonNull("userId")       ? node.get("userId").asText()       : null;
                String username    = node.hasNonNull("username")     ? node.get("username").asText()     : null;
                String message     = node.hasNonNull("message")      ? node.get("message").asText()      : null;
                String messageType = node.hasNonNull("messageType")  ? node.get("messageType").asText()  : null;
                String serverId    = node.hasNonNull("serverId")     ? node.get("serverId").asText()     : null;
                String clientIp    = node.hasNonNull("clientIp")     ? node.get("clientIp").asText()     : null;
                String timestamp   = node.hasNonNull("timestamp")    ? node.get("timestamp").asText()    : null;

                // Deduplication check
                if (messageId != null && roomManager.isDuplicate(messageId)) {
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // Parse timestamp — fall back to now if missing or malformed
                Instant createdAt;
                try {
                    createdAt = (timestamp != null) ? Instant.parse(timestamp) : Instant.now();
                } catch (Exception e) {
                    createdAt = Instant.now();
                }

                // 1. Enqueue for write-behind batch persistence
                //    The DatabaseWriter pool drains this buffer and batch-inserts to PostgreSQL.
                //    We ack to RabbitMQ immediately after enqueue (write-behind trade-off:
                //    high throughput at the cost of in-flight message loss on crash).
                ChatMessage chatMessage = new ChatMessage(
                        messageId, roomId, userId, username,
                        message != null ? message : "",
                        messageType, serverId, clientIp, createdAt);
                writeBuffer.offer(chatMessage);

                // 2. Broadcast to connected WebSocket clients
                if (roomId != null) {
                    roomManager.broadcastToRoom(roomId, body);
                }

                channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
                try {
                    channel.basicNack(deliveryTag, false, true);
                } catch (IOException ex) {
                    // ignore ack failure
                }
            }
        };

        for (String queue : queues) {
            channel.basicConsume(queue, false, deliverCallback, consumerTag -> {});
        }
    }

    public void stop() {
        running = false;
    }
}
