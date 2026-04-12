package org.example.chatflow.consumer.api;

import org.example.chatflow.consumer.cache.CacheService;
import org.example.chatflow.consumer.database.MessageRepository;
import org.example.chatflow.consumer.database.StatsAggregator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics API – returns results for all core and analytics queries defined
 * in Assignment 3.
 *
 * <p>Endpoint: {@code GET /api/metrics}
 *
 * <p>Part 3 optimisations applied here:
 * <ul>
 *   <li><b>Decoupled refresh</b> – materialized views are refreshed by
 *       {@link StatsAggregator} on a schedule; the API only triggers an on-demand
 *       refresh if the last scheduled refresh was more than 30 s ago.</li>
 *   <li><b>Response cache</b> – the full response map is cached for
 *       {@value #RESPONSE_CACHE_TTL_MS} ms.  Repeated API calls within the TTL
 *       return instantly without hitting PostgreSQL.  Cache is bypassed when query
 *       parameters differ from the cached call.</li>
 *   <li><b>Prepared statements</b> – all queries use {@code JdbcTemplate} which
 *       sends every SQL statement as a server-side prepared statement.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class MetricsApiController {

    /** Response cache TTL — 30 seconds (same as StatsAggregator refresh interval). */
    private static final long RESPONSE_CACHE_TTL_MS = 30_000;

    private final MessageRepository repo;
    private final CacheService      cacheService;
    private final StatsAggregator   statsAggregator;

    // Simple single-entry response cache (no external library required)
    private final AtomicReference<Map<String, Object>> cachedResponse = new AtomicReference<>();
    private final AtomicLong cacheExpiryMs = new AtomicLong(0);

    public MetricsApiController(MessageRepository repo, CacheService cacheService,
                                StatsAggregator statsAggregator) {
        this.repo            = repo;
        this.cacheService    = cacheService;
        this.statsAggregator = statsAggregator;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestParam(defaultValue = "1")   String roomId,
            @RequestParam(required = false)      String userId,
            @RequestParam(required = false)      String startTime,
            @RequestParam(required = false)      String endTime,
            @RequestParam(defaultValue = "10")   int    topN,
            @RequestParam(defaultValue = "20")   int    sampleSize) {

        // ---- Part 3: response cache ----
        // Only cache default (no custom params) requests, since custom params change the result.
        boolean isDefaultRequest = (userId == null && startTime == null && endTime == null
                && "1".equals(roomId) && topN == 10 && sampleSize == 20);
        if (isDefaultRequest) {
            long now = System.currentTimeMillis();
            if (now < cacheExpiryMs.get()) {
                Map<String, Object> cached = cachedResponse.get();
                if (cached != null) return cached;
            }
        }

        // ---- resolve time window ----
        Instant start = startTime != null
                ? Instant.parse(startTime) : repo.getEarliestTimestamp();
        Instant end   = endTime   != null
                ? Instant.parse(endTime)   : repo.getLatestTimestamp();

        // guard: if DB is empty use a 1-hour window ending now
        if (start.equals(end)) {
            start = end.minus(1, ChronoUnit.HOURS);
        }

        // ---- resolve userId ----
        String resolvedUserId = (userId != null && !userId.isBlank())
                ? userId : repo.getMostActiveUserId();

        // ---- Part 3: decoupled materialized view refresh ----
        // StatsAggregator refreshes on a schedule. Only refresh here if it hasn't run yet
        // (last refresh null) or is stale (> RESPONSE_CACHE_TTL_MS since last refresh).
        Instant lastRefresh = statsAggregator.getLastRefreshedAt();
        boolean needsRefresh = lastRefresh == null
                || lastRefresh.isBefore(Instant.now().minusMillis(RESPONSE_CACHE_TTL_MS));
        if (needsRefresh) {
            try {
                repo.refreshMaterializedViews();
            } catch (Exception e) {
                System.err.println("[MetricsApi] Could not refresh materialized views: " + e.getMessage());
            }
        }

        // =========================================================
        // Build response
        // =========================================================
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt",        Instant.now().toString());
        response.put("viewsLastRefreshed", statsAggregator.getLastRefreshedAt() != null
                ? statsAggregator.getLastRefreshedAt().toString() : "pending");
        response.put("totalMessagesInDB",  cacheService.getCachedTotalMessageCount());
        response.put("queryWindowStart",   start.toString());
        response.put("queryWindowEnd",     end.toString());
        response.put("queriedRoomId",      roomId);
        response.put("queriedUserId",      resolvedUserId != null ? resolvedUserId : "N/A (no data)");

        // ---- Core Queries ----
        Map<String, Object> core = new LinkedHashMap<>();

        // Q1 – Room messages in time range
        Map<String, Object> q1 = new LinkedHashMap<>();
        q1.put("description",    "Get messages for a room in time range");
        q1.put("performanceTarget", "< 100 ms for 1 000 messages");
        q1.put("roomId",         roomId);
        q1.put("startTime",      start.toString());
        q1.put("endTime",        end.toString());
        long q1Count = cacheService.getCachedRoomMessageCount(roomId, start, end);
        q1.put("messageCount",   q1Count);
        List<Map<String, Object>> q1Sample =
                cacheService.getCachedRoomMessages(roomId, start, end, sampleSize);
        q1.put("sampleMessages", q1Sample);
        core.put("Q1_roomMessagesInTimeRange", q1);

        // Q2 – User message history
        Map<String, Object> q2 = new LinkedHashMap<>();
        q2.put("description",    "Get user's message history");
        q2.put("performanceTarget", "< 200 ms");
        q2.put("userId",         resolvedUserId);
        q2.put("startTime",      start.toString());
        q2.put("endTime",        end.toString());
        if (resolvedUserId != null) {
            long q2Count = cacheService.getCachedUserMessageCount(resolvedUserId, start, end);
            q2.put("messageCount", q2Count);
            List<Map<String, Object>> q2Sample =
                    cacheService.getCachedUserHistory(resolvedUserId, start, end, sampleSize);
            q2.put("sampleMessages", q2Sample);
        } else {
            q2.put("messageCount", 0);
            q2.put("sampleMessages", List.of());
        }
        core.put("Q2_userMessageHistory", q2);

        // Q3 – Count active users in time window
        Map<String, Object> q3 = new LinkedHashMap<>();
        q3.put("description",    "Count active users in time window");
        q3.put("performanceTarget", "< 500 ms");
        q3.put("startTime",      start.toString());
        q3.put("endTime",        end.toString());
        q3.put("uniqueUserCount", cacheService.getCachedActiveUsersCount(start, end));
        core.put("Q3_activeUsersInWindow", q3);

        // Q4 – Rooms user participated in
        Map<String, Object> q4 = new LinkedHashMap<>();
        q4.put("description",    "Get rooms user has participated in");
        q4.put("performanceTarget", "< 50 ms");
        q4.put("userId",         resolvedUserId);
        if (resolvedUserId != null) {
            q4.put("rooms", cacheService.getCachedRoomsForUser(resolvedUserId));
        } else {
            q4.put("rooms", List.of());
        }
        core.put("Q4_roomsForUser", q4);

        response.put("coreQueries", core);

        // ---- Analytics Queries ----
        Map<String, Object> analytics = new LinkedHashMap<>();

        // A1 – Messages per second / minute
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("description", "Messages per second/minute statistics");
        a1.put("perMinute",   repo.getMessagesPerMinute());
        a1.put("perSecond",   repo.getMessagesPerSecond());
        analytics.put("A1_messagesPerTimeUnit", a1);

        // A2 – Top N active users
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("description", "Top " + topN + " most active users");
        a2.put("topN",        topN);
        a2.put("users",       cacheService.getCachedTopUsers(topN));
        analytics.put("A2_topActiveUsers", a2);

        // A3 – Top N active rooms
        Map<String, Object> a3 = new LinkedHashMap<>();
        a3.put("description", "Top " + topN + " most active rooms");
        a3.put("topN",        topN);
        a3.put("rooms",       cacheService.getCachedTopRooms(topN));
        analytics.put("A3_topActiveRooms", a3);

        // A4 – User participation patterns
        Map<String, Object> a4 = new LinkedHashMap<>();
        a4.put("description", "User participation patterns (rooms visited distribution)");
        a4.put("distribution", cacheService.getCachedParticipationPatterns());
        analytics.put("A4_userParticipationPatterns", a4);

        response.put("analyticsQueries", analytics);

        // ---- Optimization 2.1/2.2: cache statistics ----
        response.put("cacheStats", cacheService.getCacheStats());

        // ---- populate cache for default requests ----
        if (isDefaultRequest) {
            cachedResponse.set(response);
            cacheExpiryMs.set(System.currentTimeMillis() + RESPONSE_CACHE_TTL_MS);
        }

        return response;
    }
}
