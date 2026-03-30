package org.example.chatflow.consumer.database;

import java.time.Instant;

/**
 * Represents one chat message as it travels from RabbitMQ into PostgreSQL.
 * Fields map 1-to-1 to the {@code messages} table columns.
 */
public class ChatMessage {

    private String  messageId;
    private String  roomId;
    private String  userId;
    private String  username;
    private String  message;
    private String  messageType;   // TEXT | JOIN | LEAVE
    private String  serverId;
    private String  clientIp;
    private Instant createdAt;

    public ChatMessage() {}

    public ChatMessage(String messageId, String roomId, String userId, String username,
                       String message, String messageType, String serverId,
                       String clientIp, Instant createdAt) {
        this.messageId   = messageId;
        this.roomId      = roomId;
        this.userId      = userId;
        this.username    = username;
        this.message     = message;
        this.messageType = messageType;
        this.serverId    = serverId;
        this.clientIp    = clientIp;
        this.createdAt   = createdAt;
    }

    // ---- getters ----
    public String  getMessageId()   { return messageId; }
    public String  getRoomId()      { return roomId; }
    public String  getUserId()      { return userId; }
    public String  getUsername()    { return username; }
    public String  getMessage()     { return message; }
    public String  getMessageType() { return messageType; }
    public String  getServerId()    { return serverId; }
    public String  getClientIp()    { return clientIp; }
    public Instant getCreatedAt()   { return createdAt; }

    // ---- setters ----
    public void setMessageId(String messageId)     { this.messageId   = messageId; }
    public void setRoomId(String roomId)           { this.roomId      = roomId; }
    public void setUserId(String userId)           { this.userId      = userId; }
    public void setUsername(String username)       { this.username    = username; }
    public void setMessage(String message)         { this.message     = message; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public void setServerId(String serverId)       { this.serverId    = serverId; }
    public void setClientIp(String clientIp)       { this.clientIp    = clientIp; }
    public void setCreatedAt(Instant createdAt)    { this.createdAt   = createdAt; }
}
