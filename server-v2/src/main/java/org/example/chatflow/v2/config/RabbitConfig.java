package org.example.chatflow.v2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.v2.monitoring.ServerMetrics;
import org.example.chatflow.v2.queue.QueuePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@Configuration
public class RabbitConfig {

    @Value("${chat.rabbitmq.host:localhost}")
    private String host;

    @Value("${chat.rabbitmq.port:5672}")
    private int port;

    @Value("${chat.rabbitmq.username:guest}")
    private String username;

    @Value("${chat.rabbitmq.password:guest}")
    private String password;

    @Value("${chat.rabbitmq.exchange:chat.exchange}")
    private String exchange;

    @Value("${chat.rabbitmq.channelPoolSize:16}")
    private int channelPoolSize;

    @Value("${chat.serverId:server-v2}")
    private String serverId;

    @Value("${chat.circuitBreaker.failureThreshold:3}")
    private int failureThreshold;

    @Value("${chat.circuitBreaker.cooldownMillis:10000}")
    private long cooldownMillis;

    @Bean
    public QueuePublisher queuePublisher(ObjectMapper objectMapper, ServerMetrics serverMetrics) throws IOException, TimeoutException {
        return new QueuePublisher(
                host,
                port,
                username,
                password,
                exchange,
                channelPoolSize,
                objectMapper,
                serverId,
                serverMetrics,
                failureThreshold,
                cooldownMillis
        );
    }
}

