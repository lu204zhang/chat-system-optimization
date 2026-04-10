package org.example.chatflow.consumer.fanout;

import org.example.chatflow.consumer.monitoring.FanoutMetrics;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

public class RoomFanoutSubscriber implements MessageListener {

    private static final String CHANNEL_PREFIX = "chat:room:";

    private final RoomManager roomManager;
    private final FanoutMetrics metrics;

    public RoomFanoutSubscriber(RoomManager roomManager, FanoutMetrics metrics) {
        this.roomManager = roomManager;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        metrics.incRedisSubscribeReceived();

        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        if (!channel.startsWith(CHANNEL_PREFIX)) {
            return;
        }
        String roomId = channel.substring(CHANNEL_PREFIX.length());
        if (roomId.isEmpty()) {
            return;
        }

        try {
            roomManager.broadcastToRoom(roomId, payload);
            metrics.incLocalBroadcastSuccess();
        } catch (Exception e) {
            metrics.incLocalBroadcastFailure();
            System.err.println("[RoomFanoutSubscriber] Broadcast failed for room " + roomId + ": " + e.getMessage());
        }
    }
}

