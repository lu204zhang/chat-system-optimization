package org.example.chatflow.v2.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Declares the RabbitMQ topology (exchange + room queues) at application
 * startup.  Each queue is configured with:
 *   x-message-ttl  – messages older than this (ms) are dropped automatically
 *   x-max-length   – oldest messages are discarded (drop-head) once the queue
 *                    exceeds this depth, keeping the queue stable under load
 *
 * Fix 1: missing TTL and queue-limit configurations from Assignment 2.
 */
@Component
public class QueueSetup implements InitializingBean {

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

    /** How long (ms) a message may sit in the queue before being discarded. */
    @Value("${chat.queue.ttlMillis:300000}")
    private int ttlMillis;

    /** Maximum number of messages per room queue before old ones are dropped. */
    @Value("${chat.queue.maxLength:10000}")
    private int maxLength;

    @Override
    public void afterPropertiesSet() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        try (Connection conn = factory.newConnection();
             Channel ch = conn.createChannel()) {

            // Durable topic exchange
            ch.exchangeDeclare(exchange, "topic", true);

            for (int i = 1; i <= 20; i++) {
                String queueName  = "room." + i;
                String routingKey = "room." + i;

                Map<String, Object> args = new HashMap<>();
                args.put("x-message-ttl", ttlMillis);   // e.g. 300 000 ms = 5 min
                args.put("x-max-length", maxLength);     // e.g. 10 000 messages
                args.put("x-overflow", "drop-head");     // discard oldest on overflow

                ch.queueDeclare(queueName, true, false, false, args);
                ch.queueBind(queueName, exchange, routingKey);
            }
        }
    }
}
