package org.example.chatflow.consumer.dedup;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisMessageDeduplicator implements MessageDeduplicator {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String   KEY_PREFIX = "msg:dedup:";

    private final StringRedisTemplate redis;

    public RedisMessageDeduplicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryClaimFirstProcess(String messageId) {
        if (messageId == null) {
            return true;
        }
        String key = KEY_PREFIX + messageId;
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Availability-first: if Redis is unavailable, do not block message processing.
            System.err.println("[RedisMessageDeduplicator] Redis unavailable; bypassing dedup for messageId="
                    + messageId + ": " + e.getMessage());
            return true;
        }
    }
}

