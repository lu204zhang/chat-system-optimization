package org.example.chatflow.v2.monitoring;

import java.util.concurrent.atomic.AtomicLong;

public class ServerMetrics {

    private final long startTimeMillis = System.currentTimeMillis();

    private final AtomicLong messagesEnqueued = new AtomicLong();
    private final AtomicLong queuePublishFailures = new AtomicLong();
    private final AtomicLong activeConnections = new AtomicLong();

    public void incrementEnqueued() {
        messagesEnqueued.incrementAndGet();
    }

    public void incrementPublishFailures() {
        queuePublishFailures.incrementAndGet();
    }

    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public long getMessagesEnqueued() {
        return messagesEnqueued.get();
    }

    public long getQueuePublishFailures() {
        return queuePublishFailures.get();
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }
}

