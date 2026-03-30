package org.example.chatflow.consumer.websocket;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

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

    /**
     * Broadcasts {@code payload} to every open WebSocket session in the room.
     *
     * <p>Fix 2 (at-least-once delivery): if there is at least one open session
     * and <em>every</em> send attempt fails with an IOException, this method
     * propagates the exception so the caller (ConsumerWorker) can basicNack
     * the message back to RabbitMQ for redelivery.  When no sessions are
     * connected the message is acknowledged normally — there is no client to
     * deliver to, so requeuing would loop forever.</p>
     *
     * @throws IOException if all open sessions failed to receive the message
     */
    public void broadcastToRoom(String roomId, String payload) throws IOException {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            // No clients connected — nothing to deliver; ack is correct.
            messagesProcessed.incrementAndGet();
            return;
        }

        TextMessage msg       = new TextMessage(payload);
        int         attempted = 0;
        int         failed    = 0;

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            attempted++;
            try {
                session.sendMessage(msg);
            } catch (IOException e) {
                failed++;
                System.err.println("[RoomManager] Send failed for session "
                        + session.getId() + " in room " + roomId + ": " + e.getMessage());
            }
        }

        if (attempted > 0 && failed == attempted) {
            // Every open session rejected the write — surface as an exception so
            // ConsumerWorker will basicNack and RabbitMQ will redeliver.
            throw new IOException("Broadcast failed: all " + attempted
                    + " session(s) rejected the message for room " + roomId);
        }

        messagesProcessed.incrementAndGet();
    }
}

