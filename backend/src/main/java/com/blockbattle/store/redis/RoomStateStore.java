package com.blockbattle.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

    /** 결과: 1 입장, 0 이미 참가자, -1 방 없음, -2 WAITING 아님, -3 정원 초과 */
    private static final RedisScript<Long> JOIN = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then return 0 end
            if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then return -2 end
            if redis.call('HLEN', KEYS[2]) >= tonumber(ARGV[3]) then return -3 end
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private static final RedisScript<Long> CAS_STATUS = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'status') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'status', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /** 방 상태를 expected일 때만 next로 바꾼다. 동시에 여러 번 불려도 한 번만 성공한다 */
    public boolean compareAndSetStatus(String code, String expected, String next) {
        Long changed = redis.execute(CAS_STATUS, List.of(RedisKeys.room(code)), expected, next);
        return changed != null && changed == 1L;
    }

    /** 방 코드 선점: 활성 방(room:{code} 키가 있는 방)끼리 코드가 겹치지 않게 한다. 선점했으면 true */
    public boolean reserveRoom(String code, long hostId) {
        boolean reserved = Boolean.TRUE.equals(
                redis.opsForHash().putIfAbsent(RedisKeys.room(code), "hostId", Long.toString(hostId)));
        if (reserved) redis.expire(RedisKeys.room(code), RedisKeys.ROOM_TTL);
        return reserved;
    }

    /** 정원·상태 검사와 참가자 추가를 한 번에(Lua) */
    public long joinAtomically(String code, long userId, RoomPlayer player, int capacity) {
        Long result = redis.execute(JOIN, List.of(RedisKeys.room(code), RedisKeys.roomPlayers(code)),
                Long.toString(userId), write(player), Integer.toString(capacity));
        return result == null ? -1 : result;
    }

    /** 방 키의 남은 TTL(초). 키가 없으면 -2 */
    public long ttlSeconds(String code) {
        Long ttl = redis.getExpire(RedisKeys.room(code));
        return ttl == null ? -2 : ttl;
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
