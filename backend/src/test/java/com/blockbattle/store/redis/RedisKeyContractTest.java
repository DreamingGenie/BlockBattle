package com.blockbattle.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blockbattle.store.redis.MatchStateStore.Field;
import com.blockbattle.support.ContainersConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 키별 자료형·필드·TTL을 ERD 명세 6장(ENT-006~013)과 대조한다.
 * 깨지면: 진행 상태 저장 계약(키 이름·타입·만료)이 다른 작업의 기대와 어긋났다는 뜻이다.
 */
@SpringBootTest
@Import(ContainersConfig.class)
class RedisKeyContractTest {

    @Autowired StringRedisTemplate redis;
    @Autowired RoomStateStore rooms;
    @Autowired MatchStateStore matches;
    @Autowired DisconnectTimerStore disconnects;
    @Autowired RankingStore ranking;
    @Autowired RefreshTokenStore refresh;

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 방_세_키는_같은_30분_TTL이고_활동하면_30분으로_돌아간다() {
        rooms.putRoomFields("ABC234", Map.of("roomId", "1", "hostId", "7", "status", "WAITING", "roundCount", "1"));
        rooms.putPlayer("ABC234", 7, new RoomPlayer("하나", "0001", false, true, false));
        assertThat(rooms.bindUserRoomIfAbsent(7, "ABC234")).isTrue();

        assertThat(redis.type(RedisKeys.room("ABC234"))).isEqualTo(DataType.HASH);
        assertThat(redis.type(RedisKeys.roomPlayers("ABC234"))).isEqualTo(DataType.HASH);
        assertThat(redis.type(RedisKeys.userRoom(7))).isEqualTo(DataType.STRING);

        for (String key : new String[] {RedisKeys.room("ABC234"), RedisKeys.roomPlayers("ABC234"), RedisKeys.userRoom(7)}) {
            redis.expire(key, Duration.ofMinutes(5));
        }
        rooms.touch("ABC234");
        for (String key : new String[] {RedisKeys.room("ABC234"), RedisKeys.roomPlayers("ABC234"), RedisKeys.userRoom(7)}) {
            assertThat(ttlSeconds(key)).as(key).isBetween(29 * 60L, 30 * 60L);
        }
        assertThat(rooms.getPlayers("ABC234")).containsEntry(7L, new RoomPlayer("하나", "0001", false, true, false));
        assertThat(rooms.incrementWins("ABC234", 7)).isEqualTo(1);
        assertThat(rooms.getRoom("ABC234")).containsEntry("wins:7", "1");
    }

    @Test
    void 한_사람은_활성_방_하나에만_묶인다() {
        assertThat(rooms.bindUserRoomIfAbsent(7, "ABC234")).isTrue();
        assertThat(rooms.bindUserRoomIfAbsent(7, "XYZ789")).isFalse();
        assertThat(rooms.findUserRoom(7)).contains("ABC234");
    }

    @Test
    void 경기_상태는_Hash이고_2시간_TTL이며_board는_220자_규칙을_따른다() {
        String board = "0".repeat(210) + "GGGGGGGGG0";
        matches.putPlayerState(11, 7, Map.of(Field.BOARD, board, Field.SEQ, "3", Field.HOLD_USED, "false"));
        assertThat(redis.type(RedisKeys.matchState(11))).isEqualTo(DataType.HASH);
        assertThat(ttlSeconds(RedisKeys.matchState(11))).isBetween(2 * 3600L - 60, 2 * 3600L);
        assertThat(matches.getPlayerState(11, 7))
                .containsEntry(Field.BOARD, board).containsEntry(Field.SEQ, "3").containsEntry(Field.HOLD_USED, "false");

        assertThatThrownBy(() -> matches.putPlayerState(11, 7, Map.of(Field.BOARD, "0".repeat(219))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matches.putPlayerState(11, 7, Map.of(Field.BOARD, "X".repeat(220))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 재접속_유예_타이머는_10초_String이고_취소할_수_있다() {
        disconnects.start(11, 7);
        assertThat(redis.type(RedisKeys.disconnect(11, 7))).isEqualTo(DataType.STRING);
        assertThat(ttlSeconds(RedisKeys.disconnect(11, 7))).isBetween(9L, 10L);
        assertThat(disconnects.cancel(11, 7)).isTrue();
        assertThat(disconnects.isRunning(11, 7)).isFalse();
    }

    @Test
    void 랭킹은_TTL_없는_Sorted_Set과_카운터다() {
        ranking.put(7, 5000 * 10_000_000d + (9_999_999 - ranking.nextAchievementSeq()));
        ranking.put(8, 5000 * 10_000_000d + (9_999_999 - ranking.nextAchievementSeq()));
        assertThat(redis.type(RedisKeys.RANKING_SOLO)).isEqualTo(DataType.ZSET);
        assertThat(redis.type(RedisKeys.RANKING_SOLO_SEQ)).isEqualTo(DataType.STRING);
        assertThat(redis.getExpire(RedisKeys.RANKING_SOLO)).isEqualTo(-1L);
        assertThat(redis.getExpire(RedisKeys.RANKING_SOLO_SEQ)).isEqualTo(-1L);
        assertThat(ranking.top(10)).as("동점이면 먼저 달성한 사람이 위(BR-08)").containsExactly(7L, 8L);
    }

    @Test
    void Refresh_해시는_7일_String이고_회전하면_교체된다() {
        refresh.replace(7, "a".repeat(64));
        refresh.replace(7, "b".repeat(64));
        assertThat(redis.type(RedisKeys.refresh(7))).isEqualTo(DataType.STRING);
        assertThat(refresh.find(7)).contains("b".repeat(64));
        assertThat(ttlSeconds(RedisKeys.refresh(7))).isBetween(7 * 86400L - 60, 7 * 86400L);
        refresh.revoke(7);
        assertThat(refresh.find(7)).isEmpty();
    }

    private long ttlSeconds(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null ? -2 : ttl;
    }
}
