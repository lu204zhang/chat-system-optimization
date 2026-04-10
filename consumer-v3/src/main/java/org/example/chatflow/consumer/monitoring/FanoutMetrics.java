package org.example.chatflow.consumer.monitoring;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class FanoutMetrics {

    private final AtomicLong rabbitMessagesReceived = new AtomicLong();
    private final AtomicLong dedupHits              = new AtomicLong();

    private final AtomicLong redisPublishSuccess    = new AtomicLong();
    private final AtomicLong redisPublishFailure    = new AtomicLong();
    private final AtomicLong redisSubscribeReceived = new AtomicLong();

    private final AtomicLong localBroadcastSuccess  = new AtomicLong();
    private final AtomicLong localBroadcastFailure  = new AtomicLong();

    public void incRabbitMessagesReceived() {
        rabbitMessagesReceived.incrementAndGet();
    }

    public void incDedupHits() {
        dedupHits.incrementAndGet();
    }

    public void incRedisPublishSuccess() {
        redisPublishSuccess.incrementAndGet();
    }

    public void incRedisPublishFailure() {
        redisPublishFailure.incrementAndGet();
    }

    public void incRedisSubscribeReceived() {
        redisSubscribeReceived.incrementAndGet();
    }

    public void incLocalBroadcastSuccess() {
        localBroadcastSuccess.incrementAndGet();
    }

    public void incLocalBroadcastFailure() {
        localBroadcastFailure.incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("rabbit_messages_received", rabbitMessagesReceived.get());
        out.put("dedup_hits",              dedupHits.get());
        out.put("redis_publish_success",   redisPublishSuccess.get());
        out.put("redis_publish_failure",   redisPublishFailure.get());
        out.put("redis_subscribe_received", redisSubscribeReceived.get());
        out.put("local_broadcast_success", localBroadcastSuccess.get());
        out.put("local_broadcast_failure", localBroadcastFailure.get());
        return out;
    }
}

