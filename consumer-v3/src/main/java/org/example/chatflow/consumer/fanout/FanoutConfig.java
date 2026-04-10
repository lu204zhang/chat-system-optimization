package org.example.chatflow.consumer.fanout;

import org.example.chatflow.consumer.monitoring.FanoutMetrics;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class FanoutConfig {

    @Bean
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "false", matchIfMissing = true)
    public RoomFanoutPublisher directRoomFanoutPublisher(RoomManager roomManager, FanoutMetrics metrics) {
        return new DirectRoomFanoutPublisher(roomManager, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "true")
    public RoomFanoutPublisher redisRoomFanoutPublisher(StringRedisTemplate redis,
                                                        RoomManager roomManager,
                                                        FanoutMetrics metrics) {
        return new RedisRoomFanoutPublisher(redis, roomManager, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "true")
    public RoomFanoutSubscriber roomFanoutSubscriber(RoomManager roomManager, FanoutMetrics metrics) {
        return new RoomFanoutSubscriber(roomManager, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "true")
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                       RoomFanoutSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("redis-fanout-");
        executor.setVirtualThreads(false);
        container.setTaskExecutor(executor);

        MessageListenerAdapter adapter = new MessageListenerAdapter(subscriber, "onMessage");

        // Fixed room channels for Assignment (1..20)
        for (int i = 1; i <= 20; i++) {
            container.addMessageListener(adapter, new ChannelTopic("chat:room:" + i));
        }

        // Avoid failing the whole Spring context when Redis is down at boot (local dev).
        // Subscriber is started explicitly after refresh; see {@link #redisFanoutListenerStarter}.
        container.setAutoStartup(false);
        return container;
    }

    /**
     * Starts the Redis pub/sub listener after the app is up. If Redis is unavailable,
     * logs a warning and continues — same as {@link RedisRoomFanoutPublisher} publish fallback;
     * restart consumer after Redis is running to attach subscribers, or use Docker Redis first.
     */
    @Bean
    @ConditionalOnProperty(name = "chat.fanout.redis.enabled", havingValue = "true")
    ApplicationRunner redisFanoutListenerStarter(RedisMessageListenerContainer container) {
        return args -> {
            try {
                if (!container.isRunning()) {
                    container.start();
                }
                System.out.println("[FanoutConfig] Redis pub/sub listener container started (chat:room:1..20).");
            } catch (Exception e) {
                System.err.println("[FanoutConfig] Redis subscriber not started (is Redis running on "
                        + "spring.data.redis.host? e.g. docker compose -f database/docker-compose.redis.yml up -d): "
                        + e.getMessage());
            }
        };
    }
}

