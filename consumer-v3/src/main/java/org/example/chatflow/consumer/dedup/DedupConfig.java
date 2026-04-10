package org.example.chatflow.consumer.dedup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class DedupConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "true")
    public MessageDeduplicator redisMessageDeduplicator(StringRedisTemplate redis) {
        return new RedisMessageDeduplicator(redis);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "false", matchIfMissing = true)
    public MessageDeduplicator localMemoryMessageDeduplicator() {
        return new LocalMemoryMessageDeduplicator();
    }
}

