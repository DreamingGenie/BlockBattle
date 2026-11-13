package com.blockbattle.battle;

import com.blockbattle.room.RoomLeaveHook;
import org.springframework.stereotype.Component;

/** 경기 중 퇴장 = 나간 쪽 패배(BR-04 LEFT), 시리즈 도중이면 남은 쪽 시리즈 승자(BR-03). T-10의 RoomLeaveHook 연결점을 구현한다. */
@Component
public class FriendLeaveHook implements RoomLeaveHook {

    private final MatchOutcomeService outcomes;

    public FriendLeaveHook(MatchOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @Override
    public void onLeaveDuringMatch(String roomCode, long matchId, long userId) {
        outcomes.finish(roomCode, matchId, MatchOutcomeService.Reason.LEFT, userId);
    }

    /** 라운드 사이 퇴장 = 남은 쪽 시리즈 승자(BR-03, T-14) */
    @Override
    public void onLeaveBetweenRounds(String roomCode, long userId) {
        outcomes.onLeaveBetweenRounds(roomCode, userId);
    }
}
