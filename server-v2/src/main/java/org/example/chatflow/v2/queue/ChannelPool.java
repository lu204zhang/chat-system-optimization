package org.example.chatflow.v2.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

public class ChannelPool {

    private final BlockingQueue<Channel> pool;
    private final Connection connection;

    public ChannelPool(ConnectionFactory factory, int poolSize) throws IOException, TimeoutException {
        this.connection = factory.newConnection();
        this.pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            Channel channel = connection.createChannel();
            channel.confirmSelect();
            pool.add(channel);
        }
    }

    public Channel borrowChannel() throws InterruptedException {
        return pool.take();
    }

    public void returnChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        pool.offer(channel);
    }

    public void shutdown() throws IOException, TimeoutException {
        for (Channel channel : pool) {
            if (channel.isOpen()) {
                channel.close();
            }
        }
        if (connection.isOpen()) {
            connection.close();
        }
    }
}

