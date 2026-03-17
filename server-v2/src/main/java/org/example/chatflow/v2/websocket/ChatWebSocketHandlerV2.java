package org.example.chatflow.v2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.v2.monitoring.ServerMetrics;
import org.example.chatflow.v2.queue.QueuePublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatWebSocketHandlerV2 extends TextWebSocketHandler {

    private enum SessionState {
        NOT_JOINED,
        JOINED,
        LEFT
    }

    private static final String SESSION_STATE_KEY = "chatState";

    private final ObjectMapper objectMapper;
    private final QueuePublisher queuePublisher;
    private final ServerMetrics serverMetrics;

    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandlerV2(ObjectMapper objectMapper, QueuePublisher queuePublisher, ServerMetrics serverMetrics) {
        this.objectMapper = objectMapper;
        this.queuePublisher = queuePublisher;
        this.serverMetrics = serverMetrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = (String) session.getAttributes().get("roomId");
        if (roomId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        session.getAttributes().put(SESSION_STATE_KEY, SessionState.NOT_JOINED);

        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
        serverMetrics.incrementActiveConnections();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            sendError(session, "INVALID_JSON", "Malformed JSON payload");
            return;
        }

        if (!isValid(node)) {
            sendError(session, "INVALID_MESSAGE", "Validation failed for message fields");
            return;
        }

        SessionState state = (SessionState) session.getAttributes()
                .getOrDefault(SESSION_STATE_KEY, SessionState.NOT_JOINED);
        String type = node.get("messageType").asText();

        switch (type) {
            case "JOIN" -> {
                if (state != SessionState.NOT_JOINED) {
                    sendError(session, "INVALID_STATE", "JOIN is only allowed once at the beginning of a session");
                    return;
                }
                state = SessionState.JOINED;
            }
            case "TEXT" -> {
                if (state != SessionState.JOINED) {
                    sendError(session, "INVALID_STATE", "TEXT is only allowed after JOIN and before LEAVE");
                    return;
                }
            }
            case "LEAVE" -> {
                if (state != SessionState.JOINED) {
                    sendError(session, "INVALID_STATE", "LEAVE is only allowed after JOIN");
                    return;
                }
                state = SessionState.LEFT;
            }
            default -> {
                sendError(session, "INVALID_TYPE", "Unsupported messageType");
                return;
            }
        }

        session.getAttributes().put(SESSION_STATE_KEY, state);

        String roomId = (String) session.getAttributes().get("roomId");
        String clientIp = (String) session.getAttributes().get("clientIp");
        String userId = node.get("userId").asText();
        String username = node.get("username").asText();
        String msg = node.get("message").asText();
        String timestamp = node.get("timestamp").asText();

        try {
            queuePublisher.publish(
                    roomId,
                    userId,
                    username,
                    msg,
                    type,
                    clientIp != null ? clientIp : "unknown",
                    timestamp
            );
        } catch (IOException | InterruptedException e) {
            sendError(session, "QUEUE_ERROR", "Failed to enqueue message");
            return;
        }

        Map<String, Object> ack = Map.of(
                "status", "ENQUEUED",
                "roomId", roomId,
                "messageType", type,
                "serverTimestamp", OffsetDateTime.now().toString()
        );
        String json = objectMapper.writeValueAsString(ack);
        sendSafely(session, json);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = (String) session.getAttributes().get("roomId");
        if (roomId != null) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    roomSessions.remove(roomId);
                }
            }
        }
        serverMetrics.decrementActiveConnections();
    }

    private boolean isValid(JsonNode node) {
        if (node == null) {
            return false;
        }
        return node.hasNonNull("userId")
                && node.hasNonNull("username")
                && node.hasNonNull("message")
                && node.hasNonNull("timestamp")
                && node.hasNonNull("messageType");
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> error = Map.of(
                "status", "ERROR",
                "errorCode", code,
                "errorMessage", message,
                "serverTimestamp", OffsetDateTime.now().toString()
        );
        try {
            String json = objectMapper.writeValueAsString(error);
            sendSafely(session, json);
        } catch (Exception ignored) {
        }
    }

    private void sendSafely(WebSocketSession session, String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            if (session.isOpen()) {
                // ignored, could log at debug
            }
        }
    }
}

