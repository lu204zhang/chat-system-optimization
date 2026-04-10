package org.example.chatflow.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.consumer.database.BatchWriteBuffer;
import org.example.chatflow.consumer.database.DatabaseWriter;
import org.example.chatflow.consumer.database.DbCircuitBreaker;
import org.example.chatflow.consumer.database.MessageRepository;
import org.example.chatflow.consumer.dedup.MessageDeduplicator;
import org.example.chatflow.consumer.fanout.RoomFanoutPublisher;
import org.example.chatflow.consumer.monitoring.FanoutMetrics;
import org.example.chatflow.consumer.queue.ConsumerChannelManager;
import org.example.chatflow.consumer.queue.ConsumerScaler;
import org.example.chatflow.consumer.queue.ConsumerWorker;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Wires up the full consumer infrastructure for Assignment 3 Part 2.
 *
 * <p>Three separate thread-pool roles (write-behind architecture):
 * <ol>
 *   <li><b>Message consumers</b> ({@code consumerExecutor}) — RabbitMQ workers that
 *       receive messages and enqueue them to {@link BatchWriteBuffer}.</li>
 *   <li><b>Database writers</b> ({@code dbWriterExecutor}) — drain the buffer in
 *       configurable batch sizes and write to PostgreSQL with retry + DLQ.</li>
 *   <li><b>Statistics aggregators</b> — {@code @Scheduled} in
 *       {@link org.example.chatflow.consumer.database.StatsAggregator}; refreshes
 *       materialized views on a fixed interval.</li>
 * </ol>
 */
@Configuration
@EnableScheduling
public class ConsumerConfig implements DisposableBean {

    // ---- RabbitMQ ----
    @Value("${chat.rabbitmq.host:localhost}")
    private String host;

    @Value("${chat.rabbitmq.port:5672}")
    private int port;

    @Value("${chat.rabbitmq.username:guest}")
    private String username;

    @Value("${chat.rabbitmq.password:guest}")
    private String password;

    // ---- Consumer workers ----
    @Value("${chat.consumer.minWorkers:5}")
    private int minWorkers;

    @Value("${chat.consumer.prefetch:50}")
    private int prefetch;

    // ---- Write buffer ----
    @Value("${chat.db.bufferCapacity:500000}")
    private int bufferCapacity;

    // ---- Database writers ----
    @Value("${chat.db.writerThreads:4}")
    private int writerThreads;

    @Value("${chat.db.batchSize:1000}")
    private int batchSize;

    @Value("${chat.db.flushIntervalMs:500}")
    private long flushIntervalMs;

    @Value("${chat.db.maxRetries:3}")
    private int maxRetries;

    // ---- DB circuit breaker ----
    @Value("${chat.db.circuitBreaker.failureThreshold:5}")
    private int dbFailureThreshold;

    @Value("${chat.db.circuitBreaker.cooldownMillis:30000}")
    private long dbCooldownMs;

    // ---- Internal state for lifecycle management ----
    private final List<ConsumerWorker> initialWorkers = new ArrayList<>();
    private final List<DatabaseWriter> dbWriters       = new ArrayList<>();
    private ExecutorService consumerExecutor;
    private ExecutorService dbWriterExecutor;
    private ConsumerChannelManager channelManager;

    private static final List<String> ALL_QUEUES;
    static {
        ALL_QUEUES = new ArrayList<>(20);
        for (int i = 1; i <= 20; i++) ALL_QUEUES.add("room." + i);
    }

    // =========================================================================
    // Beans
    // =========================================================================

    @Bean
    public RoomManager roomManager() {
        return new RoomManager();
    }

    @Bean
    public ConsumerChannelManager consumerChannelManager() throws IOException, TimeoutException {
        this.channelManager = new ConsumerChannelManager(host, port, username, password);
        return this.channelManager;
    }

    @Bean
    public BatchWriteBuffer batchWriteBuffer() {
        return new BatchWriteBuffer(bufferCapacity);
    }

    @Bean
    public DbCircuitBreaker dbCircuitBreaker() {
        return new DbCircuitBreaker(dbFailureThreshold, dbCooldownMs);
    }

    /** Database writer thread pool — drains BatchWriteBuffer → PostgreSQL. */
    @Bean
    public ExecutorService dbWriterExecutor(BatchWriteBuffer buffer,
                                            MessageRepository repository,
                                            DbCircuitBreaker circuitBreaker) {
        dbWriterExecutor = Executors.newFixedThreadPool(writerThreads);
        for (int i = 0; i < writerThreads; i++) {
            DatabaseWriter writer = new DatabaseWriter(
                    buffer, repository, circuitBreaker, batchSize, flushIntervalMs, maxRetries);
            dbWriters.add(writer);
            dbWriterExecutor.submit(writer);
        }
        return dbWriterExecutor;
    }

    /** RabbitMQ consumer thread pool — receives messages and enqueues to BatchWriteBuffer. */
    @Bean
    public ExecutorService consumerExecutor(ConsumerChannelManager manager,
                                            ObjectMapper objectMapper,
                                            BatchWriteBuffer writeBuffer,
                                            MessageDeduplicator deduplicator,
                                            RoomFanoutPublisher fanoutPublisher,
                                            FanoutMetrics metrics,
                                            ConsumerScaler scaler) {
        consumerExecutor = Executors.newCachedThreadPool();

        for (int i = 0; i < minWorkers; i++) {
            ConsumerWorker worker = new ConsumerWorker(
                    manager, new ArrayList<>(ALL_QUEUES), objectMapper,
                    writeBuffer, deduplicator, fanoutPublisher, metrics, prefetch);
            initialWorkers.add(worker);
            consumerExecutor.submit(worker);
        }

        scaler.registerInitialWorkers(new ArrayList<>(initialWorkers));
        return consumerExecutor;
    }

    @Override
    public void destroy() throws Exception {
        // Stop consumer workers first (stop enqueuing)
        for (ConsumerWorker w : initialWorkers) w.stop();
        if (consumerExecutor != null) consumerExecutor.shutdownNow();

        // Stop DB writers (they flush remaining buffer on shutdown)
        for (DatabaseWriter w : dbWriters) w.stop();
        if (dbWriterExecutor != null) dbWriterExecutor.shutdownNow();

        if (channelManager != null) channelManager.close();
    }
}
