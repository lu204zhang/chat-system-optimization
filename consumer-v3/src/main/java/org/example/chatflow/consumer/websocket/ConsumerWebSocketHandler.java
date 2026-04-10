package org.example.chatflow.consumer.websocket;

import org.example.chatflow.consumer.fanout.RoomFanoutPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class ConsumerWebSocketHandler extends TextWebSocketHandler {

    private final RoomManager         roomManager;
    private final RoomFanoutPublisher fanoutPublisher;

    public ConsumerWebSocketHandler(RoomManager roomManager, RoomFanoutPublisher fanoutPublisher) {
        this.roomManager    = roomManager;
        this.fanoutPublisher = fanoutPublisher;
    }

    /**
     * Client-originated text: fan out the same payload string as MQ-originated messages
     * (direct {@link RoomManager#broadcastToRoom} or Redis {@code chat:room:{id}} → subscriber → broadcast).
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String roomId = (String) session.getAttributes().get("roomId");
        String payload = message.getPayload();
        if (roomId == null || payload == null || payload.isBlank()) {
            return;
        }
        fanoutPublisher.publish(roomId, payload);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        String roomId = segments.length > 0 ? segments[segments.length - 1] : "unknown";
        session.getAttributes().put("roomId", roomId);
        roomManager.addSession(roomId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = (String) session.getAttributes().get("roomId");
        if (roomId != null) {
            roomManager.removeSession(roomId, session);
        }
    }
}

