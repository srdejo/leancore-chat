package com.leancore.chat.infrastructure.configuration;

import com.leancore.chat.infrastructure.input.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketMapping(ChatWebSocketHandler chatWebSocketHandler) {
        return new SimpleUrlHandlerMapping(Map.of("/ws/conversations/*", chatWebSocketHandler), -1);
    }
}
