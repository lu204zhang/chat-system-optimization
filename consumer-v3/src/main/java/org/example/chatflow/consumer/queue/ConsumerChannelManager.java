package org.example.chatflow.consumer.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class ConsumerChannelManager {

    private final ConnectionFactory factory;
    private volatile Connection connection;

    public ConsumerChannelManager(String host, int port, String username, String password)
            throws IOException, TimeoutException {
        this.factory = new ConnectionFactory();
        this.factory.setHost(host);
        this.factory.setPort(port);
        this.factory.setUsername(username);
        this.factory.setPassword(password);
        // Lazy-connect: do not block Spring startup if RabbitMQ is unavailable or credentials are wrong.
    }

    public Channel createChannel() throws IOException {
        try {
            return getOrCreateConnection().createChannel();
        } catch (TimeoutException e) {
            throw new IOException("RabbitMQ connection timeout", e);
        }
    }

    public void close() throws IOException, TimeoutException {
        Connection c = this.connection;
        if (c != null && c.isOpen()) {
            c.close();
        }
    }

    private Connection getOrCreateConnection() throws IOException, TimeoutException {
        Connection c = this.connection;
        if (c != null && c.isOpen()) {
            return c;
        }
        synchronized (this) {
            c = this.connection;
            if (c != null && c.isOpen()) {
                return c;
            }
            this.connection = factory.newConnection();
            return this.connection;
        }
    }
}
