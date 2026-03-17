package org.example.chatflow.consumer.websocket;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RoomManager {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> processedMessageIds = new ConcurrentHashMap<>();
    private final AtomicLong messagesProcessed = new AtomicLong();

    public void addSession(String roomId, WebSocketSession session) {
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void removeSession(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    public boolean isDuplicate(String messageId) {
        if (messageId == null) {
            return false;
        }
        return processedMessageIds.putIfAbsent(messageId, Boolean.TRUE) != null;
    }

    public void broadcastToRoom(String roomId, String payload) throws IOException {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            messagesProcessed.incrementAndGet();
            return;
        }
        TextMessage msg = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    // Ignore per-session send failures; others may still succeed.
                }
            }
        }
        messagesProcessed.incrementAndGet();
    }
}

