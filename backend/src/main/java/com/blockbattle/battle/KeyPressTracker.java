package com.blockbattle.battle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 친구 대전 봇 판정용 실제 키 입력 수(T-41). lock 메시지마다 클라이언트가 그 라운드의 누적 키 입력 수를 보내면 마지막 값을 둔다.
 * 라운드가 끝나면 MatchOutcomeService가 읽고 지운다. 서버 메모리에만 두므로 재시작하면 사라지고, 그때는 키 조건을 건너뛴다(단일 서버 기준).
 */
@Component
public class KeyPressTracker {

    private final Map<String, Integer> latest = new ConcurrentHashMap<>();

    private static String key(long matchId, long userId) {
        return matchId + ":" + userId;
    }

    public void record(long matchId, long userId, Integer keyPresses) {
        if (keyPresses != null && keyPresses >= 0) latest.merge(key(matchId, userId), keyPresses, Math::max);
    }

    /** 보낸 적이 없으면 null */
    public Integer get(long matchId, long userId) {
        return latest.get(key(matchId, userId));
    }

    public void clear(long matchId, Iterable<Long> userIds) {
        userIds.forEach(id -> latest.remove(key(matchId, id)));
    }
}
