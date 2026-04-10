package org.example.chatflow.consumer.fanout;

import org.example.chatflow.consumer.monitoring.FanoutMetrics;
import org.example.chatflow.consumer.websocket.RoomManager;

public class DirectRoomFanoutPublisher implements RoomFanoutPublisher {

    private final RoomManager roomManager;
    private final FanoutMetrics metrics;

    public DirectRoomFanoutPublisher(RoomManager roomManager, FanoutMetrics metrics) {
        this.roomManager = roomManager;
        this.metrics = metrics;
    }

    @Override
    public void publish(String roomId, String jsonPayload) {
        if (roomId == null) {
            return;
        }
        try {
            roomManager.broadcastToRoom(roomId, jsonPayload);
            metrics.incLocalBroadcastSuccess();
        } catch (Exception e) {
            metrics.incLocalBroadcastFailure();
        }
    }
}

