package com.blockbattle.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 진행 중 방 상태(ENT-006~008). 진행 중 상태의 원본은 Redis다(POL-011).
 * <ul>
 *   <li>room:{code} — Hash: roomId, hostId, status, roundCount, seriesNo, roundNo, attemptNo, wins:{userId}, currentMatchId</li>
 *   <li>room:{code}:players — Hash: userId → RoomPlayer JSON</li>
 *   <li>user:{userId}:room — String: 활성 방 코드(한 사람 한 방)</li>
 * </ul>
 * 세 키의 TTL은 같고, 활동(입장·퇴장·준비·lock·재대결 수락)마다 {@link #touch(String)}로 30분으로 되돌린다.
 */
@Component
public class RoomStateStore {

    public static final String WINS_FIELD_PREFIX = "wins:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RoomStateStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void putRoomFields(String code, Map<String, String> fields) {
        redis.opsForHash().putAll(RedisKeys.room(code), fields);
        redis.expire(RedisKeys.room(code), RedisKeys.ROOM_TTL);
    }

    public Map<String, String> getRoom(String code) {
        Map<String, String> result = new LinkedHashMap<>();
        redis.<String, String>opsForHash().entries(RedisKeys.room(code)).forEach(result::put);
        return result;
    }

    public long incrementWins(String code, long userId) {
        Long wins = redis.opsForHash().increment(RedisKeys.room(code), WINS_FIELD_PREFIX + userId, 1);
        return wins == null ? 0 : wins;
    }

    public void putPlayer(String code, long userId, RoomPlayer player) {
        redis.opsForHash().put(RedisKeys.roomPlayers(code), Long.toString(userId), write(player));
        redis.expire(RedisKeys.roomPlayers(code), RedisKeys.ROOM_TTL);
    }

    public Map<Long, RoomPlayer> getPlayers(String code) {
        Map<Long, RoomPlayer> result = new LinkedHashMap<>();
        redis.<String, String>opsForHash().entries(RedisKeys.roomPlayers(code))
                .forEach((userId, value) -> result.put(Long.parseLong(userId), read(value)));
        return result;
    }

    public void removePlayer(String code, long userId) {
        redis.opsForHash().delete(RedisKeys.roomPlayers(code), Long.toString(userId));
    }

    /** 이미 다른 활성 방에 있으면 false(한 사람 한 방). */
    public boolean bindUserRoomIfAbsent(long userId, String code) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(RedisKeys.userRoom(userId), code, RedisKeys.ROOM_TTL));
    }

    public Optional<String> findUserRoom(long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.userRoom(userId)));
    }

    public void unbindUserRoom(long userId) {
        redis.delete(RedisKeys.userRoom(userId));
    }

    /** 활동 시각 기준으로 방 키·참가자 키·참가자별 활성 방 키의 TTL을 30분으로 되돌린다(POL-024). */
    public void touch(String code) {
        redis.expire(RedisKeys.room(code), RedisKeys.ROOM_TTL);
        redis.expire(RedisKeys.roomPlayers(code), RedisKeys.ROOM_TTL);
        for (Long userId : getPlayers(code).keySet()) {
            redis.expire(RedisKeys.userRoom(userId), RedisKeys.ROOM_TTL);
        }
    }

    public void deleteRoom(String code) {
        for (Long userId : getPlayers(code).keySet()) {
            redis.delete(RedisKeys.userRoom(userId));
        }
        redis.delete(RedisKeys.roomPlayers(code));
        redis.delete(RedisKeys.room(code));
    }

    private String write(RoomPlayer player) {
        try {
            return json.writeValueAsString(player);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RoomPlayer 직렬화 실패", e);
        }
    }

    private RoomPlayer read(String value) {
        try {
            return json.readValue(value, RoomPlayer.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RoomPlayer 역직렬화 실패", e);
        }
    }
}
