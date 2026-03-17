package org.example.chatflow.consumer.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import org.example.chatflow.consumer.websocket.RoomManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class ConsumerWorker implements Runnable {

    private final ConsumerChannelManager channelManager;
    private final List<String> queues;
    private final ObjectMapper objectMapper;
    private final RoomManager roomManager;
    private final int prefetch;
    private volatile boolean running = true;

    public ConsumerWorker(ConsumerChannelManager channelManager,
                          List<String> queues,
                          ObjectMapper objectMapper,
                          RoomManager roomManager,
                          int prefetch) {
        this.channelManager = channelManager;
        this.queues = queues;
        this.objectMapper = objectMapper;
        this.roomManager = roomManager;
        this.prefetch = prefetch;
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
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                JsonNode node = objectMapper.readTree(body);
                String messageId = node.hasNonNull("messageId") ? node.get("messageId").asText() : null;
                String roomId = node.hasNonNull("roomId") ? node.get("roomId").asText() : null;

                if (messageId != null && roomManager.isDuplicate(messageId)) {
                    channel.basicAck(deliveryTag, false);
                    return;
                }

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

