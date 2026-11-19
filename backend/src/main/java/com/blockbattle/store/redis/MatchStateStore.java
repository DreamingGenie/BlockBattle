package com.blockbattle.store.redis;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * match:{matchId}:state — Hash(ENT-009). 플레이어별 값은 필드 이름 "{userId}:{필드}"로 둔다.
 * TTL 2시간, lock·재접속마다 {@link #touch(long)}로 되돌린다(친구 대전은 활동이 있는 한 지워지지 않는다).
 */
@Component
public class MatchStateStore {

    /** board는 22행 × 10열 = 220자, 맨 위 행부터 왼쪽→오른쪽, 0 빈칸·IOTSZJL 블록·G 방해 줄(CON-001) */
    static final Pattern BOARD = Pattern.compile("[0IOTSZJLG]{220}");

    public enum Field {
        BOARD("board"), SEQ("seq"), PIECE_INDEX("pieceIndex"), HOLD("hold"), HOLD_USED("holdUsed"),
        PENDING_GARBAGE("pendingGarbage"), COMBO("combo"), B2B("b2b"), SCORE("score"),
        LINES_CLEARED("linesCleared"), LEVEL("level"), PIECES_PLACED("piecesPlaced"), ATTACK_SENT("attackSent"),
        ATTACK_RECEIVED("attackReceived"), MAX_COMBO("maxCombo"), TOPOUT_AT("topoutAt");

        private final String name;

        Field(String name) {
            this.name = name;
        }

        public String fieldName() {
            return name;
        }
    }

    private final StringRedisTemplate redis;

    public MatchStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void putPlayerState(long matchId, long userId, Map<Field, String> values) {
        String board = values.get(Field.BOARD);
        if (board != null && !BOARD.matcher(board).matches()) {
            throw new IllegalArgumentException("board는 [0IOTSZJLG] 220자여야 한다");
        }
        Map<String, String> fields = new HashMap<>();
        values.forEach((field, value) -> fields.put(fieldKey(userId, field), value));
        redis.opsForHash().putAll(RedisKeys.matchState(matchId), fields);
        touch(matchId);
    }

    public Map<Field, String> getPlayerState(long matchId, long userId) {
        List<Field> all = List.of(Field.values());
        List<Object> keys = all.stream().<Object>map(field -> fieldKey(userId, field)).toList();
        List<Object> values = redis.opsForHash().multiGet(RedisKeys.matchState(matchId), keys);
        Map<Field, String> result = new EnumMap<>(Field.class);
        for (int i = 0; i < all.size(); i++) {
            if (values.get(i) != null) {
                result.put(all.get(i), (String) values.get(i));
            }
        }
        return result;
    }

    /** 다인 대전 탈락 등수(필드 "{userId}:placement"). 정해지면 바뀌지 않는다 */
    public void putPlacement(long matchId, long userId, int placement) {
        redis.opsForHash().put(RedisKeys.matchState(matchId), userId + ":" + PLACEMENT, Integer.toString(placement));
        touch(matchId);
    }

    /** 등수가 정해진(탈락했거나 경기가 끝난) 참가자만 담는다 */
    public Map<Long, Integer> getPlacements(long matchId, Collection<Long> userIds) {
        List<Long> ids = List.copyOf(userIds);
        List<Object> keys = ids.stream().<Object>map(id -> id + ":" + PLACEMENT).toList();
        List<Object> values = redis.opsForHash().multiGet(RedisKeys.matchState(matchId), keys);
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (values.get(i) != null) result.put(ids.get(i), Integer.parseInt((String) values.get(i)));
        }
        return result;
    }

    static final String PLACEMENT = "placement";

    public void touch(long matchId) {
        redis.expire(RedisKeys.matchState(matchId), RedisKeys.MATCH_STATE_TTL);
    }

    public void delete(long matchId) {
        redis.delete(RedisKeys.matchState(matchId));
    }

    static String fieldKey(long userId, Field field) {
        return userId + ":" + field.fieldName();
    }
}
