package com.securitysystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${cors.allowed-origin:http://localhost:8080}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigin)
                .withSockJS();
    }

    /**
     * Raise STOMP transport limits to accommodate Base64-encoded MP3 audio
     * in START_DETERRANCE messages.
     *
     * Sizing:
     *   A 5-second deterrence phrase at 128 kbps ≈ 80 KB MP3
     *   → ≈107 KB after Base64 encoding (+33% overhead)
     *   → 256 KB message limit gives comfortable headroom for longer phrases
     *      and the JSON envelope around the audio field.
     */
    @Override
    public void configureWebSocketTransport(@NonNull WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(256 * 1024);     // 256 KB per STOMP frame
        registration.setSendBufferSizeLimit(512 * 1024);  // 512 KB outbound buffer per session
        registration.setSendTimeLimit(20 * 1000);         // 20 s send timeout (TTS latency headroom)
    }
}
