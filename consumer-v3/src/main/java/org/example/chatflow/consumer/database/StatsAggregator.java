package org.example.chatflow.consumer.database;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Statistics aggregator — separate thread pool role in the write-behind architecture.
 *
 * <p>Periodically refreshes the five PostgreSQL materialized views used by the
 * analytics queries (A1–A4) in the Metrics API:
 * <ul>
 *   <li>mv_msg_per_minute</li>
 *   <li>mv_msg_per_second</li>
 *   <li>mv_top_users</li>
 *   <li>mv_top_rooms</li>
 *   <li>mv_user_participation</li>
 * </ul>
 *
 * <p>Using {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} means reads are never
 * blocked during the refresh, so the Metrics API remains responsive.
 *
 * <p>By decoupling the refresh from the API request, the API handler avoids
 * an expensive blocking call on every invocation.
 */
@Component
public class StatsAggregator {

    private final MessageRepository repository;

    /** When false, scheduled refresh is skipped (no JDBC); bean still exists for MetricsApiController. */
    @Value("${chat.db.stats.enabled:false}")
    private boolean statsRefreshEnabled;

    private volatile Instant lastRefreshedAt = null;

    public StatsAggregator(MessageRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${chat.db.statsRefreshIntervalMs:30000}")
    public void refreshStats() {
        if (!statsRefreshEnabled) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            repository.refreshMaterializedViews();
            long elapsed = System.currentTimeMillis() - start;
            lastRefreshedAt = Instant.now();
            System.out.printf("[StatsAggregator] Materialized views refreshed in %d ms%n", elapsed);
        } catch (Exception e) {
            System.err.println("[StatsAggregator] Refresh failed: " + e.getMessage());
        }
    }

    /** Returns the timestamp of the last successful refresh, or {@code null} if never refreshed. */
    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }
}
