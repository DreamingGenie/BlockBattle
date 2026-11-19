package com.blockbattle.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
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

    /**
     * STOMP 하트비트 ms(보안 점검 M-2). 서버는 10초마다 보내고, 클라이언트에게는 25초 간격을 기대한다.
     * Spring 브로커는 기대 간격의 3배(75초) 동안 신호가 없으면 세션을 끊으므로, TCP close 없이 사라진 연결도
     * 그 안에 끊김 유예(BR-06)로 넘어간다. 기대 간격을 10초로 두지 않은 이유: Chrome은 5분 넘게 숨긴 탭의 타이머를
     * 1분에 한 번으로 늦추므로, 30초 기준이면 대기실을 띄워 두고 다른 탭에 있는 사용자가 1분마다 끊긴다.
     * 클라이언트(frontend/src/realtime/stomp.ts)는 10초 간격을 제안하고, 협상 결과대로 25초마다 보낸다.
     */
    static final long SERVER_SEND_MS = 10_000;
    static final long CLIENT_SEND_MS = 25_000;

    private final StompAuthInterceptor authInterceptor;
    private final ObjectMapper json;
    private final TaskScheduler brokerScheduler;

    /** messageBrokerTaskScheduler는 이 설정이 만드는 빈이라 @Lazy로 순환 참조를 피한다(Spring 문서의 하트비트 예와 같음) */
    public WebSocketConfig(StompAuthInterceptor authInterceptor, ObjectMapper json,
                           @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler) {
        this.authInterceptor = authInterceptor;
        this.json = json;
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        registry.setErrorHandler(new StompErrorHandler(json));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {SERVER_SEND_MS, CLIENT_SEND_MS})
                .setTaskScheduler(brokerScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
