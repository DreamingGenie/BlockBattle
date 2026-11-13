package com.blockbattle.room;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-13이 RoomLeaveHook을 구현하기 전까지 쓰는 기본값(아무것도 하지 않음).
 * T-13은 실제 구현을 빈으로 등록하면서 이 클래스를 지운다.
 */
@Configuration
public class NoopRoomLeaveHook {

    @Bean
    RoomLeaveHook defaultRoomLeaveHook() {
        return (roomCode, matchId, userId) -> { };
    }
}
