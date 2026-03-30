package org.example.chatflow.consumer.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * All database interactions for the chat system.
 *
 * <p>Write path (Part 1 – single insert; upgraded to batch in Part 2):
 * <ul>
 *   <li>{@link #save(ChatMessage)} – idempotent upsert via ON CONFLICT DO NOTHING</li>
 * </ul>
 *
 * <p>Read path (Metrics API):
 * <ul>
 *   <li>Four core queries (Q1–Q4)</li>
 *   <li>Four analytics queries (A1–A4) using materialized views</li>
 *   <li>{@link #refreshMaterializedViews()} – call before serving analytics</li>
 * </ul>
 */
@Repository
public class MessageRepository {

    private static final String INSERT_SQL = """
            INSERT INTO messages
                (message_id, room_id, user_id, username, message,
                 message_type, server_id, client_ip, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // Write
    // =========================================================================

    /**
     * Idempotent single-row insert.
     * Duplicate {@code message_id} values are silently ignored (ON CONFLICT DO NOTHING),
     * satisfying the at-least-once / deduplication requirement.
     */
    public void save(ChatMessage msg) {
        jdbc.update(INSERT_SQL,
                msg.getMessageId(),
                msg.getRoomId(),
                msg.getUserId(),
                msg.getUsername(),
                msg.getMessage(),
                msg.getMessageType(),
                msg.getServerId(),
                msg.getClientIp(),
                Timestamp.from(msg.getCreatedAt()));
    }

    /**
     * Part 2 – Batch insert (write-behind path).
     *
     * <p>Uses {@code JdbcTemplate.batchUpdate} to send a single multi-row
     * prepared-statement batch to PostgreSQL, which is far faster than N
     * individual round-trips.  {@code ON CONFLICT (message_id) DO NOTHING}
     * ensures idempotency — duplicate deliveries from RabbitMQ are silently
     * ignored at the database level.
     *
     * @return total rows actually inserted (excluding conflicts)
     */
    public int batchSave(List<ChatMessage> messages) {
        if (messages.isEmpty()) return 0;
        int[][] results = jdbc.batchUpdate(INSERT_SQL, messages, messages.size(),
                (ps, msg) -> {
                    ps.setString(   1, msg.getMessageId());
                    ps.setString(   2, msg.getRoomId());
                    ps.setString(   3, msg.getUserId());
                    ps.setString(   4, msg.getUsername());
                    ps.setString(   5, msg.getMessage());
                    ps.setString(   6, msg.getMessageType());
                    ps.setString(   7, msg.getServerId());
                    ps.setString(   8, msg.getClientIp());
                    ps.setTimestamp(9, Timestamp.from(msg.getCreatedAt()));
                });
        return Arrays.stream(results).flatMapToInt(Arrays::stream).sum();
    }

    /**
     * Dead-letter queue — saves a message that failed all retry attempts to
     * {@code messages_dlq} along with the error reason and failure timestamp.
     * Uses a best-effort insert; if this also fails the message is logged to
     * stderr by the caller.
     */
    public void saveToDlq(ChatMessage msg, String errorReason) {
        String sql = """
                INSERT INTO messages_dlq
                    (message_id, room_id, user_id, username, message,
                     message_type, server_id, client_ip, created_at, error_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """;
        jdbc.update(sql,
                msg.getMessageId(), msg.getRoomId(), msg.getUserId(), msg.getUsername(),
                msg.getMessage(),   msg.getMessageType(), msg.getServerId(),
                msg.getClientIp(),  Timestamp.from(msg.getCreatedAt()), errorReason);
    }

    // =========================================================================
    // Core Query 1 – Room messages in time range
    // Target: < 100 ms for 1 000 messages
    // Index used: idx_room_time (room_id, created_at DESC)
    // =========================================================================

    /**
     * Returns the count of messages in the room within the time window plus up
     * to {@code sampleLimit} message rows for display in the API response.
     */
    public long countRoomMessages(String roomId, Instant start, Instant end) {
        String sql = """
                SELECT COUNT(*)
                FROM messages
                WHERE room_id = ?
                  AND created_at BETWEEN ? AND ?
                """;
        Long count = jdbc.queryForObject(sql, Long.class,
                roomId, Timestamp.from(start), Timestamp.from(end));
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> getRoomMessagesInTimeRange(
            String roomId, Instant start, Instant end, int limit) {
        String sql = """
                SELECT message_id, room_id, user_id, username, message,
                       message_type, server_id, client_ip, created_at
                FROM messages
                WHERE room_id = ?
                  AND created_at BETWEEN ? AND ?
                ORDER BY created_at
                LIMIT ?
                """;
        return jdbc.queryForList(sql,
                roomId, Timestamp.from(start), Timestamp.from(end), limit);
    }

    // =========================================================================
    // Core Query 2 – User message history
    // Target: < 200 ms
    // Index used: idx_user_time (user_id, created_at DESC)
    // =========================================================================

    public long countUserMessages(String userId, Instant start, Instant end) {
        String sql = """
                SELECT COUNT(*)
                FROM messages
                WHERE user_id = ?
                  AND created_at BETWEEN ? AND ?
                """;
        Long count = jdbc.queryForObject(sql, Long.class,
                userId, Timestamp.from(start), Timestamp.from(end));
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> getUserMessageHistory(
            String userId, Instant start, Instant end, int limit) {
        String sql = """
                SELECT message_id, room_id, user_id, username, message,
                       message_type, server_id, client_ip, created_at
                FROM messages
                WHERE user_id = ?
                  AND created_at BETWEEN ? AND ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        return jdbc.queryForList(sql,
                userId, Timestamp.from(start), Timestamp.from(end), limit);
    }

    // =========================================================================
    // Core Query 3 – Count active users in time window
    // Target: < 500 ms
    // Index used: idx_time (created_at) or idx_user_time (user_id, created_at)
    // =========================================================================

    public long countActiveUsersInWindow(Instant start, Instant end) {
        String sql = """
                SELECT COUNT(DISTINCT user_id)
                FROM messages
                WHERE created_at BETWEEN ? AND ?
                """;
        Long count = jdbc.queryForObject(sql, Long.class,
                Timestamp.from(start), Timestamp.from(end));
        return count == null ? 0 : count;
    }

    // =========================================================================
    // Core Query 4 – Rooms a user participated in (with last activity)
    // Target: < 50 ms
    // Index used: idx_user_time (user_id, created_at DESC)
    // =========================================================================

    public List<Map<String, Object>> getRoomsForUser(String userId) {
        String sql = """
                SELECT room_id,
                       MAX(created_at)  AS last_activity,
                       COUNT(*)         AS message_count
                FROM messages
                WHERE user_id = ?
                GROUP BY room_id
                ORDER BY last_activity DESC
                """;
        return jdbc.queryForList(sql, userId);
    }

    // =========================================================================
    // Analytics helpers
    // =========================================================================

    /** Returns the most active user_id in the DB (used as default for Q2/Q4). */
    public String getMostActiveUserId() {
        String sql = """
                SELECT user_id
                FROM messages
                GROUP BY user_id
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """;
        try {
            return jdbc.queryForObject(sql, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the total row count in the messages table. */
    public long getTotalMessageCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Earliest {@code created_at} in the table – used to derive a default
     * time window for the metrics API.
     */
    public Instant getEarliestTimestamp() {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(created_at) FROM messages", Timestamp.class);
        return ts == null ? Instant.now().minusSeconds(3600) : ts.toInstant();
    }

    /**
     * Latest {@code created_at} in the table.
     */
    public Instant getLatestTimestamp() {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM messages", Timestamp.class);
        return ts == null ? Instant.now() : ts.toInstant();
    }

    // =========================================================================
    // Analytics Query A1 – Messages per minute / second
    // Uses materialized view mv_msg_per_minute / mv_msg_per_second
    // =========================================================================

    public List<Map<String, Object>> getMessagesPerMinute() {
        return jdbc.queryForList(
                "SELECT bucket, message_count FROM mv_msg_per_minute ORDER BY bucket");
    }

    public List<Map<String, Object>> getMessagesPerSecond() {
        return jdbc.queryForList(
                "SELECT bucket, message_count FROM mv_msg_per_second ORDER BY bucket");
    }

    // =========================================================================
    // Analytics Query A2 – Top N most active users
    // Uses materialized view mv_top_users
    // =========================================================================

    public List<Map<String, Object>> getTopActiveUsers(int n) {
        return jdbc.queryForList(
                "SELECT user_id, username, message_count, last_active " +
                "FROM mv_top_users ORDER BY message_count DESC LIMIT ?", n);
    }

    // =========================================================================
    // Analytics Query A3 – Top N most active rooms
    // Uses materialized view mv_top_rooms
    // =========================================================================

    public List<Map<String, Object>> getTopActiveRooms(int n) {
        return jdbc.queryForList(
                "SELECT room_id, message_count, last_activity " +
                "FROM mv_top_rooms ORDER BY message_count DESC LIMIT ?", n);
    }

    // =========================================================================
    // Analytics Query A4 – User participation patterns
    // Uses materialized view mv_user_participation
    // =========================================================================

    public List<Map<String, Object>> getUserParticipationPatterns() {
        return jdbc.queryForList(
                "SELECT rooms_visited, user_count " +
                "FROM mv_user_participation ORDER BY rooms_visited");
    }

    // =========================================================================
    // Materialized view refresh
    // Called once before serving the metrics API response.
    // CONCURRENTLY avoids blocking reads; requires unique indexes on views.
    // =========================================================================

    public void refreshMaterializedViews() {
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_msg_per_minute");
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_msg_per_second");
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_users");
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_top_rooms");
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_participation");
    }
}
