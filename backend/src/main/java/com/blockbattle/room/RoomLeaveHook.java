package com.blockbattle.room;

/**
 * 경기 중 퇴장 연결점(T-10 계획 리비전 200). 방이 PLAYING이고 진행 경기가 있을 때 퇴장 처리 전에 불린다.
 * 나간 쪽 패배(BR-04 LEFT)·경기 저장·전적 확정은 T-13이 이 인터페이스를 구현해 완성한다.
 */
public interface RoomLeaveHook {

    void onLeaveDuringMatch(String roomCode, long matchId, long userId);

    /** 시리즈 라운드 사이(방 PLAYING, 진행 경기 없음)에 나갈 때. 남은 쪽이 시리즈 승자다(BR-03, T-14) */
    default void onLeaveBetweenRounds(String roomCode, long userId) {
    }
}
