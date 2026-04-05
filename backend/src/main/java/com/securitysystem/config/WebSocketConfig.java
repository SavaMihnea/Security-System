package com.securitysystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${cors.allowed-origin:http://localhost:8080}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        // Clients subscribe to /topic/... destinations
        config.enableSimpleBroker("/topic");
        // Messages from clients start with /app
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        // SockJS fallback for browsers that don't support native WebSocket
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigin)
                .withSockJS();
    }
}
