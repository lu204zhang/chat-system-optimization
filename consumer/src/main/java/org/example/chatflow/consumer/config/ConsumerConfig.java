package org.example.chatflow.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.consumer.queue.ConsumerChannelManager;
import org.example.chatflow.consumer.queue.ConsumerWorker;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

@Configuration
public class ConsumerConfig implements DisposableBean {

    @Value("${chat.rabbitmq.host:localhost}")
    private String host;

    @Value("${chat.rabbitmq.port:5672}")
    private int port;

    @Value("${chat.rabbitmq.username:guest}")
    private String username;

    @Value("${chat.rabbitmq.password:guest}")
    private String password;

    @Value("${chat.consumer.threadCount:10}")
    private int threadCount;

    @Value("${chat.consumer.prefetch:50}")
    private int prefetch;

    private final List<ConsumerWorker> workers = new ArrayList<>();
    private ExecutorService executorService;
    private ConsumerChannelManager channelManager;

    @Bean
    public RoomManager roomManager() {
        return new RoomManager();
    }

    @Bean
    public ConsumerChannelManager consumerChannelManager() throws IOException, TimeoutException {
        this.channelManager = new ConsumerChannelManager(host, port, username, password);
        return this.channelManager;
    }

    @Bean
    public ExecutorService consumerExecutor(ConsumerChannelManager manager,
                                            ObjectMapper objectMapper,
                                            RoomManager roomManager) {
        executorService = Executors.newFixedThreadPool(threadCount);

        List<String> allQueues = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            allQueues.add("room." + i);
        }

        List<List<String>> assignments = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            assignments.add(new ArrayList<>());
        }
        for (int i = 0; i < allQueues.size(); i++) {
            assignments.get(i % threadCount).add(allQueues.get(i));
        }

        for (int i = 0; i < threadCount; i++) {
            List<String> queues = assignments.get(i);
            if (queues.isEmpty()) {
                continue;
            }
            ConsumerWorker worker = new ConsumerWorker(manager, queues, objectMapper, roomManager, prefetch);
            workers.add(worker);
            executorService.submit(worker);
        }

        return executorService;
    }

    @Override
    public void destroy() throws Exception {
        for (ConsumerWorker worker : workers) {
            worker.stop();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (channelManager != null) {
            channelManager.close();
        }
    }
}

