package org.example.chatflow.consumer.database;

import java.util.ArrayList;
import java.util.List;

/**
 * Write-behind database writer — one instance per writer thread.
 *
 * <p>Architecture role: drains the shared {@link BatchWriteBuffer} in configurable
 * batch sizes and persists them to PostgreSQL via
 * {@link MessageRepository#batchSave(List)}.
 *
 * <p>Failure handling:
 * <ol>
 *   <li>If the {@link DbCircuitBreaker} is OPEN the batch is immediately
 *       dead-lettered (no DB attempt).</li>
 *   <li>Otherwise the write is retried up to {@code maxRetries} times with
 *       exponential back-off (100 ms → 200 ms → 400 ms … capped at 5 s).</li>
 *   <li>If all retries fail the batch is saved to the {@code messages_dlq}
 *       table via {@link MessageRepository#saveToDlq(ChatMessage, String)}.</li>
 * </ol>
 *
 * <p>Thread pools:
 * <ul>
 *   <li>Message consumers  – existing {@code consumerExecutor} (RabbitMQ workers)</li>
 *   <li>Database writers   – new {@code dbWriterExecutor} (this class)</li>
 *   <li>Statistics aggregators – Spring {@code @Scheduled} in {@link StatsAggregator}</li>
 * </ul>
 */
public class DatabaseWriter implements Runnable {

    private final BatchWriteBuffer  buffer;
    private final MessageRepository repository;
    private final DbCircuitBreaker  circuitBreaker;
    private final int               batchSize;
    private final long              flushIntervalMs;
    private final int               maxRetries;
    private volatile boolean        running = true;

    public DatabaseWriter(BatchWriteBuffer buffer,
                          MessageRepository repository,
                          DbCircuitBreaker circuitBreaker,
                          int batchSize,
                          long flushIntervalMs,
                          int maxRetries) {
        this.buffer          = buffer;
        this.repository      = repository;
        this.circuitBreaker  = circuitBreaker;
        this.batchSize       = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.maxRetries      = maxRetries;
    }

    @Override
    public void run() {
        List<ChatMessage> batch = new ArrayList<>(batchSize);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                batch.clear();
                // Block up to flushIntervalMs waiting for messages, then flush whatever arrived
                int drained = buffer.drain(batch, batchSize, flushIntervalMs);
                if (drained > 0) {
                    writeBatchWithRetry(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Graceful shutdown: flush whatever is still in the buffer
        List<ChatMessage> remaining = new ArrayList<>();
        try {
            buffer.drain(remaining, Integer.MAX_VALUE, 0); // non-blocking
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (!remaining.isEmpty()) {
            writeBatchWithRetry(remaining);
        }
    }

    private void writeBatchWithRetry(List<ChatMessage> batch) {
        if (circuitBreaker.isOpen()) {
            sendToDlq(batch, "circuit breaker open");
            return;
        }

        long delayMs = 100;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                repository.batchSave(batch);
                circuitBreaker.onSuccess();
                return;
            } catch (Exception e) {
                circuitBreaker.onFailure();
                if (attempt == maxRetries) {
                    sendToDlq(batch, e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sendToDlq(batch, "writer interrupted during retry");
                    return;
                }
                delayMs = Math.min(delayMs * 2, 5_000); // exponential back-off, cap 5 s
            }
        }
    }

    private void sendToDlq(List<ChatMessage> batch, String reason) {
        System.err.printf("[DatabaseWriter] %d messages → DLQ (%s)%n", batch.size(), reason);
        for (ChatMessage msg : batch) {
            try {
                repository.saveToDlq(msg, reason);
            } catch (Exception e) {
                // Absolute last resort: stderr so the message_id is not silently lost
                System.err.printf("[DatabaseWriter] DLQ write failed for message_id=%s: %s%n",
                        msg.getMessageId(), e.getMessage());
            }
        }
    }

    public void stop() { running = false; }
}
