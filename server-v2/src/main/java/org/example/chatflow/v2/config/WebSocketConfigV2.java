package org.example.chatflow.v2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.chatflow.v2.monitoring.ServerMetrics;
import org.example.chatflow.v2.queue.QueuePublisher;
import org.example.chatflow.v2.websocket.ChatWebSocketHandlerV2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfigV2 implements WebSocketConfigurer {

    private final ObjectMapper objectMapper;
    private final QueuePublisher queuePublisher;
    private final ServerMetrics serverMetrics;

    public WebSocketConfigV2(ObjectMapper objectMapper, QueuePublisher queuePublisher, ServerMetrics serverMetrics) {
        this.objectMapper = objectMapper;
        this.queuePublisher = queuePublisher;
        this.serverMetrics = serverMetrics;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandlerV2(), "/chat/{roomId}")
                .setAllowedOrigins("*")
                .addInterceptors(new RoomIdHandshakeInterceptorV2());
    }

    @Bean
    public ChatWebSocketHandlerV2 chatWebSocketHandlerV2() {
        return new ChatWebSocketHandlerV2(objectMapper, queuePublisher, serverMetrics);
    }
}

