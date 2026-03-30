package org.example.chatflow.consumer.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class ConsumerWebSocketHandler extends TextWebSocketHandler {

    private final RoomManager roomManager;

    public ConsumerWebSocketHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
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

