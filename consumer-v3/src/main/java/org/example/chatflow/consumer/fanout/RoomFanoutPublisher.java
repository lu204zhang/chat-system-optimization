package org.example.chatflow.consumer.fanout;

public interface RoomFanoutPublisher {
    void publish(String roomId, String jsonPayload);
}

