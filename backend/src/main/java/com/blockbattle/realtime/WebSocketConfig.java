package com.blockbattle.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP 1.2 over WebSocket(API 명세 1장·5장): 엔드포인트 /ws, 클라이언트 발행 /app, 방 방송 /topic, 개인 메시지 /user/queue.
 * CONNECT 인증·방 참가자 권한은 StompAuthInterceptor, 거부 ERROR 프레임 형식은 StompErrorHandler가 맡는다(T-11).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;
    private final ObjectMapper json;

    public WebSocketConfig(StompAuthInterceptor authInterceptor, ObjectMapper json) {
        this.authInterceptor = authInterceptor;
        this.json = json;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        registry.setErrorHandler(new StompErrorHandler(json));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
