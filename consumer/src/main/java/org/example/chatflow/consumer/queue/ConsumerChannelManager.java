package org.example.chatflow.consumer.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class ConsumerChannelManager {

    private final Connection connection;

    public ConsumerChannelManager(String host, int port, String username, String password) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        this.connection = factory.newConnection();
    }

    public Channel createChannel() throws IOException {
        return connection.createChannel();
    }

    public void close() throws IOException, TimeoutException {
        if (connection.isOpen()) {
            connection.close();
        }
    }
}

