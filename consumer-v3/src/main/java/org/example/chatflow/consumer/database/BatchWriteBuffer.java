package org.example.chatflow.consumer.database;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory buffer between RabbitMQ consumer threads and database
 * writer threads (write-behind pattern).
 *
 * <p>Producers ({@link org.example.chatflow.consumer.queue.ConsumerWorker}) call
 * {@link #offer(ChatMessage)} — non-blocking; messages are dropped (and counted)
 * when the buffer is full.
 *
 * <p>Consumers ({@link DatabaseWriter}) call {@link #drain(List, int, long)} which
 * blocks until at least one message is available or the timeout expires, then
 * drains up to {@code maxElements} in a single call.
 */
public class BatchWriteBuffer {

    private final BlockingQueue<ChatMessage> queue;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public BatchWriteBuffer(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking enqueue.
     *
     * @return {@code true} if accepted; {@code false} if the buffer was full (message dropped).
     */
    public boolean offer(ChatMessage msg) {
        boolean accepted = queue.offer(msg);
        if (!accepted) {
            droppedCount.incrementAndGet();
        }
        return accepted;
    }

    /**
     * Drains up to {@code maxElements} messages into {@code buffer}.
     *
     * <p>If {@code timeoutMs > 0}: blocks up to {@code timeoutMs} ms waiting for the
     * first element, then immediately drains the rest without further blocking.
     *
     * <p>If {@code timeoutMs <= 0}: non-blocking drain (returns 0 if queue is empty).
     *
     * @return number of elements added to {@code buffer}
     */
    public int drain(List<ChatMessage> buffer, int maxElements, long timeoutMs)
            throws InterruptedException {
        if (timeoutMs <= 0) {
            return queue.drainTo(buffer, maxElements);
        }
        ChatMessage first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) return 0;
        buffer.add(first);
        return 1 + queue.drainTo(buffer, maxElements - 1);
    }

    public int  size()         { return queue.size(); }
    public long droppedCount() { return droppedCount.get(); }
}
