package org.example.chatflow.consumer.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.consumer.database.BatchWriteBuffer;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fix 4 – dynamic consumer scaling.
 *
 * <p>Every {@code scaleIntervalMs} milliseconds this component queries the
 * RabbitMQ Management HTTP API for the total depth across all room queues and
 * adjusts the number of active {@link ConsumerWorker} threads accordingly:
 *
 * <ul>
 *   <li>depth &gt; {@code scaleUpThreshold}   → add a worker (up to {@code maxWorkers})</li>
 *   <li>depth &lt; {@code scaleDownThreshold} → remove a worker (down to {@code minWorkers})</li>
 * </ul>
 *
 * <p>Each worker subscribes to <em>all 20</em> room queues so that RabbitMQ
 * distributes messages round-robin among the active worker pool.  Adding or
 * removing a worker thus increases or decreases throughput proportionally
 * without any queue-reassignment logic.
 */
@Component
public class ConsumerScaler implements DisposableBean {

    private final ConsumerChannelManager channelManager;
    private final ObjectMapper           objectMapper;
    private final RoomManager            roomManager;
    private final BatchWriteBuffer       writeBuffer;

    @Value("${chat.rabbitmq.host:localhost}")
    private String mqHost;

    @Value("${chat.rabbitmq.managementPort:15672}")
    private int managementPort;

    @Value("${chat.rabbitmq.username:guest}")
    private String mqUser;

    @Value("${chat.rabbitmq.password:guest}")
    private String mqPassword;

    @Value("${chat.consumer.prefetch:50}")
    private int prefetch;

    @Value("${chat.consumer.minWorkers:5}")
    private int minWorkers;

    @Value("${chat.consumer.maxWorkers:40}")
    private int maxWorkers;

    @Value("${chat.consumer.scaleUpThreshold:500}")
    private long scaleUpThreshold;

    @Value("${chat.consumer.scaleDownThreshold:50}")
    private long scaleDownThreshold;

    // Growing thread pool; corePoolSize is updated dynamically
    private final ExecutorService scalingExecutor = Executors.newCachedThreadPool();
    private final List<ConsumerWorker> activeWorkers = new ArrayList<>();
    private final AtomicInteger workerCount = new AtomicInteger(0);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // All 20 room queues – every scaled worker subscribes to all of them
    private static final List<String> ALL_QUEUES;
    static {
        ALL_QUEUES = new ArrayList<>(20);
        for (int i = 1; i <= 20; i++) {
            ALL_QUEUES.add("room." + i);
        }
    }

    public ConsumerScaler(ConsumerChannelManager channelManager,
                          ObjectMapper objectMapper,
                          RoomManager roomManager,
                          BatchWriteBuffer writeBuffer) {
        this.channelManager = channelManager;
        this.objectMapper   = objectMapper;
        this.roomManager    = roomManager;
        this.writeBuffer    = writeBuffer;
    }

    /**
     * Called by {@link org.example.chatflow.consumer.config.ConsumerConfig}
     * after the initial batch of workers has been started, so that the scaler
     * knows the current worker count and can register them for lifecycle management.
     */
    public synchronized void registerInitialWorkers(List<ConsumerWorker> workers) {
        activeWorkers.addAll(workers);
        workerCount.set(workers.size());
    }

    @Scheduled(fixedDelayString = "${chat.consumer.scaleIntervalMs:5000}")
    public void scale() {
        long depth = fetchTotalQueueDepth();
        if (depth < 0) {
            return; // Management API unavailable — leave current worker count unchanged
        }

        int current = workerCount.get();

        if (depth > scaleUpThreshold && current < maxWorkers) {
            addWorker();
            System.out.printf("[ConsumerScaler] Scaled UP  to %d workers (queue depth=%d)%n",
                    workerCount.get(), depth);
        } else if (depth < scaleDownThreshold && current > minWorkers) {
            removeWorker();
            System.out.printf("[ConsumerScaler] Scaled DOWN to %d workers (queue depth=%d)%n",
                    workerCount.get(), depth);
        }
    }

    // ---- scaling actions ----

    private synchronized void addWorker() {
        ConsumerWorker worker = new ConsumerWorker(
                channelManager, new ArrayList<>(ALL_QUEUES), objectMapper,
                roomManager, writeBuffer, prefetch);
        activeWorkers.add(worker);
        scalingExecutor.submit(worker);
        workerCount.incrementAndGet();
    }

    private synchronized void removeWorker() {
        if (activeWorkers.isEmpty()) return;
        ConsumerWorker last = activeWorkers.remove(activeWorkers.size() - 1);
        last.stop();
        workerCount.decrementAndGet();
    }

    public int getWorkerCount() {
        return workerCount.get();
    }

    @Override
    public void destroy() {
        synchronized (this) {
            for (ConsumerWorker w : activeWorkers) {
                w.stop();
            }
            activeWorkers.clear();
        }
        scalingExecutor.shutdownNow();
    }

    // ---- RabbitMQ Management API helper ----

    /**
     * Returns the sum of the {@code messages} field across all queues in the
     * default vhost, or {@code -1} if the API is unreachable.
     */
    private long fetchTotalQueueDepth() {
        try {
            String url = "http://" + mqHost + ":" + managementPort + "/api/queues/%2F";
            String credentials = Base64.getEncoder()
                    .encodeToString((mqUser + ":" + mqPassword).getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return -1;
            }

            JsonNode queues = objectMapper.readTree(response.body());
            long total = 0;
            if (queues.isArray()) {
                for (JsonNode q : queues) {
                    JsonNode msgs = q.get("messages");
                    if (msgs != null && !msgs.isNull()) {
                        total += msgs.asLong(0);
                    }
                }
            }
            return total;
        } catch (Exception e) {
            System.err.println("[ConsumerScaler] Could not fetch queue depth: " + e.getMessage());
            return -1;
        }
    }
}
