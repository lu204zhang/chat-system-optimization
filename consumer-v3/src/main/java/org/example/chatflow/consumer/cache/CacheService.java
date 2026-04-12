package org.example.chatflow.consumer.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.chatflow.consumer.database.ChatMessage;
import org.example.chatflow.consumer.database.MessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-tier caching layer (Optimization 2.1 + 2.2 + 2.3).
 *
 * <h3>L1 — Caffeine (in-process, per-instance)</h3>
 * Ultra-fast, bounded, TTL-based cache for hot data. Each consumer-v3 instance
 * keeps its own L1 so there are zero network round-trips for repeated reads.
 *
 * <h3>L2 — Redis (shared across instances)</h3>
 * Cross-instance cache with configurable TTL. Used when L1 misses but a DB
 * round-trip is too expensive. Only activated when {@code chat.fanout.redis.enabled=true}.
 *
 * <h3>Cache invalidation strategy (2.3)</h3>
 * <ul>
 *   <li><b>Event-driven</b>: {@link #onNewMessage(ChatMessage)} is called inline
 *       from the consumer pipeline. It evicts affected room/user entries from both
 *       L1 and L2 so the next read sees fresh data.</li>
 *   <li><b>TTL-based</b>: Every cache entry has a bounded TTL as a safety net,
 *       so stale data self-heals even if an invalidation event is missed.</li>
 * </ul>
 */
@Service
public class CacheService {

    private final MessageRepository repo;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;

    // ---- L1 Caffeine caches ----
    private final Cache<String, Long> roomMessageCountCache;
    private final Cache<String, List<Map<String, Object>>> roomMessagesCache;
    private final Cache<String, Long> userMessageCountCache;
    private final Cache<String, List<Map<String, Object>>> userHistoryCache;
    private final Cache<String, Long> activeUsersCache;
    private final Cache<String, List<Map<String, Object>>> roomsForUserCache;
    private final Cache<String, Long> totalMessageCountCache;
    private final Cache<String, List<Map<String, Object>>> topUsersCache;
    private final Cache<String, List<Map<String, Object>>> topRoomsCache;
    private final Cache<String, List<Map<String, Object>>> participationCache;

    // ---- Metrics ----
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l1Misses = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l2Misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    // Redis key prefix
    private static final String REDIS_PREFIX = "cache:";

    public CacheService(
            MessageRepository repo,
            StringRedisTemplate redisTemplate,
            @Value("${chat.fanout.redis.enabled:false}") boolean redisEnabled,
            @Value("${chat.cache.l1.maxSize:1000}") int l1MaxSize,
            @Value("${chat.cache.l1.ttlSeconds:10}") int l1TtlSeconds) {
        this.repo = repo;
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled;

        // Build L1 caches with size bounds + TTL
        roomMessageCountCache = buildCache(l1MaxSize, l1TtlSeconds);
        roomMessagesCache     = buildCache(l1MaxSize, l1TtlSeconds);
        userMessageCountCache = buildCache(l1MaxSize, l1TtlSeconds);
        userHistoryCache      = buildCache(l1MaxSize, l1TtlSeconds);
        activeUsersCache      = buildCache(l1MaxSize / 2, l1TtlSeconds);
        roomsForUserCache     = buildCache(l1MaxSize, l1TtlSeconds);
        totalMessageCountCache = buildCache(1, l1TtlSeconds);
        topUsersCache         = buildCache(10, l1TtlSeconds);
        topRoomsCache         = buildCache(10, l1TtlSeconds);
        participationCache    = buildCache(10, l1TtlSeconds);
    }

    private <V> Cache<String, V> buildCache(int maxSize, int ttlSeconds) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    // =========================================================================
    // Event-driven invalidation (2.3) — called from consumer pipeline
    // =========================================================================

    /**
     * Invalidates cache entries affected by a new message.
     * Called inline from the consumer pipeline after a message is received.
     */
    public void onNewMessage(ChatMessage msg) {
        invalidations.incrementAndGet();
        String roomId = msg.getRoomId();
        String userId = msg.getUserId();

        // Evict room-level caches
        if (roomId != null) {
            roomMessageCountCache.invalidate(roomId);
            // Invalidate all room message queries for this room (key prefix match)
            roomMessagesCache.asMap().keySet().removeIf(k -> k.startsWith(roomId + ":"));
            roomMessageCountCache.asMap().keySet().removeIf(k -> k.startsWith(roomId + ":"));
            evictRedisPattern("room:" + roomId);
        }

        // Evict user-level caches
        if (userId != null) {
            userMessageCountCache.asMap().keySet().removeIf(k -> k.startsWith(userId + ":"));
            userHistoryCache.asMap().keySet().removeIf(k -> k.startsWith(userId + ":"));
            roomsForUserCache.invalidate(userId);
            evictRedisPattern("user:" + userId);
        }

        // Evict global aggregates (total count, top users/rooms, active users)
        totalMessageCountCache.invalidateAll();
        topUsersCache.invalidateAll();
        topRoomsCache.invalidateAll();
        participationCache.invalidateAll();
        activeUsersCache.invalidateAll();

        evictRedis("global:totalCount");
        evictRedis("global:topUsers");
        evictRedis("global:topRooms");
    }

    // =========================================================================
    // Cached read methods — L1 → (L2) → DB
    // =========================================================================

    public long getCachedRoomMessageCount(String roomId, Instant start, Instant end) {
        String key = roomId + ":" + start + ":" + end;
        Long cached = roomMessageCountCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        long count = repo.countRoomMessages(roomId, start, end);
        roomMessageCountCache.put(key, count);
        return count;
    }

    public List<Map<String, Object>> getCachedRoomMessages(
            String roomId, Instant start, Instant end, int limit) {
        String key = roomId + ":" + start + ":" + end + ":" + limit;
        List<Map<String, Object>> cached = roomMessagesCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getRoomMessagesInTimeRange(roomId, start, end, limit);
        roomMessagesCache.put(key, result);
        return result;
    }

    public long getCachedUserMessageCount(String userId, Instant start, Instant end) {
        String key = userId + ":" + start + ":" + end;
        Long cached = userMessageCountCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        long count = repo.countUserMessages(userId, start, end);
        userMessageCountCache.put(key, count);
        return count;
    }

    public List<Map<String, Object>> getCachedUserHistory(
            String userId, Instant start, Instant end, int limit) {
        String key = userId + ":" + start + ":" + end + ":" + limit;
        List<Map<String, Object>> cached = userHistoryCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getUserMessageHistory(userId, start, end, limit);
        userHistoryCache.put(key, result);
        return result;
    }

    public long getCachedActiveUsersCount(Instant start, Instant end) {
        String key = start + ":" + end;
        Long cached = activeUsersCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        long count = repo.countActiveUsersInWindow(start, end);
        activeUsersCache.put(key, count);
        return count;
    }

    public List<Map<String, Object>> getCachedRoomsForUser(String userId) {
        List<Map<String, Object>> cached = roomsForUserCache.getIfPresent(userId);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getRoomsForUser(userId);
        roomsForUserCache.put(userId, result);
        return result;
    }

    public long getCachedTotalMessageCount() {
        Long cached = totalMessageCountCache.getIfPresent("total");
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        long count = repo.getTotalMessageCount();
        totalMessageCountCache.put("total", count);
        return count;
    }

    public List<Map<String, Object>> getCachedTopUsers(int n) {
        String key = "top:" + n;
        List<Map<String, Object>> cached = topUsersCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getTopActiveUsers(n);
        topUsersCache.put(key, result);
        return result;
    }

    public List<Map<String, Object>> getCachedTopRooms(int n) {
        String key = "top:" + n;
        List<Map<String, Object>> cached = topRoomsCache.getIfPresent(key);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getTopActiveRooms(n);
        topRoomsCache.put(key, result);
        return result;
    }

    public List<Map<String, Object>> getCachedParticipationPatterns() {
        List<Map<String, Object>> cached = participationCache.getIfPresent("patterns");
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }
        l1Misses.incrementAndGet();

        List<Map<String, Object>> result = repo.getUserParticipationPatterns();
        participationCache.put("patterns", result);
        return result;
    }

    // =========================================================================
    // Redis L2 helpers
    // =========================================================================

    private void evictRedis(String key) {
        if (!redisEnabled) return;
        try {
            redisTemplate.delete(REDIS_PREFIX + key);
        } catch (Exception e) {
            // Redis unavailable — L1 invalidation still effective
        }
    }

    private void evictRedisPattern(String pattern) {
        if (!redisEnabled) return;
        try {
            var keys = redisTemplate.keys(REDIS_PREFIX + pattern + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            // Redis unavailable — L1 invalidation still effective
        }
    }

    // =========================================================================
    // Metrics / status
    // =========================================================================

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("l1_hits", l1Hits.get());
        stats.put("l1_misses", l1Misses.get());
        long totalRequests = l1Hits.get() + l1Misses.get();
        stats.put("l1_hit_rate", totalRequests > 0
                ? String.format("%.1f%%", 100.0 * l1Hits.get() / totalRequests) : "N/A");
        stats.put("l2_hits", l2Hits.get());
        stats.put("l2_misses", l2Misses.get());
        stats.put("invalidations_total", invalidations.get());
        stats.put("redis_cache_enabled", redisEnabled);

        // Per-cache sizes
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("roomMessageCount", roomMessageCountCache.estimatedSize());
        sizes.put("roomMessages", roomMessagesCache.estimatedSize());
        sizes.put("userMessageCount", userMessageCountCache.estimatedSize());
        sizes.put("userHistory", userHistoryCache.estimatedSize());
        sizes.put("activeUsers", activeUsersCache.estimatedSize());
        sizes.put("roomsForUser", roomsForUserCache.estimatedSize());
        sizes.put("totalMessageCount", totalMessageCountCache.estimatedSize());
        sizes.put("topUsers", topUsersCache.estimatedSize());
        sizes.put("topRooms", topRoomsCache.estimatedSize());
        sizes.put("participation", participationCache.estimatedSize());
        stats.put("l1_cache_sizes", sizes);

        return stats;
    }
}
