package org.example.chatflow.consumer.websocket;

import org.example.chatflow.consumer.fanout.RoomFanoutPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ConsumerWebSocketConfig implements WebSocketConfigurer {

    private final RoomManager         roomManager;
    private final RoomFanoutPublisher fanoutPublisher;

    public ConsumerWebSocketConfig(RoomManager roomManager, RoomFanoutPublisher fanoutPublisher) {
        this.roomManager    = roomManager;
        this.fanoutPublisher = fanoutPublisher;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ConsumerWebSocketHandler(roomManager, fanoutPublisher),
                        "/chat/{roomId}")
                .setAllowedOrigins("*");
    }
}

