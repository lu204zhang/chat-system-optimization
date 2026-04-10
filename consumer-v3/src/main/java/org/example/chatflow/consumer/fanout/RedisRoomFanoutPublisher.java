package org.example.chatflow.consumer.fanout;

import org.example.chatflow.consumer.monitoring.FanoutMetrics;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisRoomFanoutPublisher implements RoomFanoutPublisher {

    private static final String CHANNEL_PREFIX = "chat:room:";

    private final StringRedisTemplate redis;
    private final RoomManager roomManager;
    private final FanoutMetrics metrics;

    public RedisRoomFanoutPublisher(StringRedisTemplate redis, RoomManager roomManager, FanoutMetrics metrics) {
        this.redis = redis;
        this.roomManager = roomManager;
        this.metrics = metrics;
    }

    @Override
    public void publish(String roomId, String jsonPayload) {
        if (roomId == null) {
            return;
        }
        try {
            redis.convertAndSend(CHANNEL_PREFIX + roomId, jsonPayload);
            metrics.incRedisPublishSuccess();
        } catch (Exception e) {
            metrics.incRedisPublishFailure();
            System.err.println("[RedisRoomFanoutPublisher] Publish failed; falling back to local broadcast: "
                    + e.getMessage());
            try {
                roomManager.broadcastToRoom(roomId, jsonPayload);
                metrics.incLocalBroadcastSuccess();
            } catch (Exception ex) {
                metrics.incLocalBroadcastFailure();
            }
        }
    }
}

