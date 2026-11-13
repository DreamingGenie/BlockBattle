package com.blockbattle.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.blockbattle.support.ContainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * POL-010·POL-023 보존 기간 정리를 실제 MySQL(FK ON DELETE RESTRICT)에서 확인한다.
 * 깨지면: 보존 기간 안의 기록·진행 중 경기·회원 전적이 지워지거나, 삭제 순서가 FK에 막히거나, 실행 시각이 04:00 KST가 아니라는 뜻이다.
 */
@SpringBootTest
@Import(ContainersConfig.class)
class RetentionTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    final Instant now = ZonedDateTime.of(2026, 11, 13, 4, 0, 0, 0, KST).toInstant();
    final Instant cutoff = ZonedDateTime.of(2025, 11, 13, 4, 0, 0, 0, KST).toInstant();

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionService retention;

    long user;
    long other;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        user = insertUser("오션", "0001");
        other = insertUser("민수", "0002");
    }

    @Test
    void 기준_시각은_실행_시각에서_KST_달력으로_1년_전이다() {
        assertThat(RetentionService.cutoff(now)).isEqualTo(cutoff);
    }

    @Test
    void 일년_넘게_지난_끝난_경기와_닫힌_방만_순서대로_지우고_나머지는_남긴다() {
        long closedOld = room("CLOSED", cutoff.minusSeconds(1));
        long oldInClosed = friendMatch(closedOld, "FINISHED", cutoff.minusSeconds(1));
        long closedWithRecent = room("CLOSED", cutoff.minusSeconds(1));
        long recentInClosed = friendMatch(closedWithRecent, "FINISHED", cutoff); // 정확히 1년은 남긴다
        long finishedRoom = room("FINISHED", null);
        long oldInOpenRoom = friendMatch(finishedRoom, "FINISHED", cutoff.minusSeconds(3600));
        long closedRecently = room("CLOSED", cutoff.plusSeconds(3600));
        long oldSolo = soloMatch("ABORTED", cutoff.minusSeconds(86_400 * 30));
        long playing = soloMatch("PLAYING", null);
        long abortedNoEnd = soloMatch("ABORTED", null);

        RetentionService.Purged purged = retention.purge(now);

        assertThat(purged.matches()).isEqualTo(3);
        assertThat(purged.players()).isEqualTo(5); // 친구 2판 × 2명 + 싱글 1명
        assertThat(purged.rooms()).isEqualTo(1);
        assertThat(matchIds()).containsExactlyInAnyOrder(recentInClosed, playing, abortedNoEnd)
                .doesNotContain(oldInClosed, oldInOpenRoom, oldSolo);
        assertThat(roomIds()).as("경기가 남은 방·닫히지 않은 방·최근에 닫힌 방은 남긴다")
                .containsExactlyInAnyOrder(closedWithRecent, finishedRoom, closedRecently);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_players WHERE match_id IN (?, ?, ?)", Integer.class,
                recentInClosed, playing, abortedNoEnd)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_stats", Integer.class)).isEqualTo(2);
    }

    @Test
    void 대상이_한_묶음보다_많아도_모두_지운다() {
        Timestamp old = Timestamp.from(cutoff.minusSeconds(60));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < RetentionService.BATCH + 5; i++) rows.add(new Object[] {old, old});
        jdbc.batchUpdate("""
                INSERT INTO matches (mode, seed, status, end_reason, started_at, ended_at, duration_ms)
                VALUES ('SOLO', 1, 'FINISHED', 'COMPLETED', ?, ?, 0)""", rows);
        jdbc.update("""
                INSERT INTO match_players (match_id, slot, user_id, player_type, result)
                SELECT id, 1, ?, 'USER', 'NONE' FROM matches""", user);

        RetentionService.Purged purged = retention.purge(now);
        assertThat(purged.matches()).isEqualTo(RetentionService.BATCH + 5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matches", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_players", Integer.class)).isZero();
    }

    @Test
    void 매일_04시_KST에_실행된다() throws Exception {
        Scheduled scheduled = RetentionJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        ZonedDateTime next = CronExpression.parse(scheduled.cron())
                .next(ZonedDateTime.of(LocalDateTime.of(2026, 11, 13, 4, 0, 1), KST));
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 14, 4, 0, 0, 0, KST));
    }

    private long insertUser(String nickname, String tag) {
        jdbc.update("INSERT INTO users (nickname, tag, user_type, created_at, last_login_at) VALUES (?, ?, 'GUEST', ?, ?)",
                nickname, tag, Timestamp.from(now), Timestamp.from(now));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbc.update("INSERT INTO user_stats (user_id, updated_at) VALUES (?, ?)", id, Timestamp.from(now));
        return id;
    }

    private long room(String status, Instant closedAt) {
        jdbc.update("""
                INSERT INTO game_rooms (room_code, host_user_id, status, round_count, close_reason, created_at, closed_at)
                VALUES ('ABCDEF', ?, ?, 1, ?, ?, ?)""", user, status, closedAt == null ? null : "EMPTY",
                Timestamp.from(cutoff.minusSeconds(86_400 * 400)), closedAt == null ? null : Timestamp.from(closedAt));
        return jdbc.queryForObject("SELECT MAX(id) FROM game_rooms", Long.class);
    }

    private long friendMatch(long roomId, String status, Instant endedAt) {
        Integer next = jdbc.queryForObject("SELECT COUNT(*) + 1 FROM matches WHERE room_id = ?", Integer.class, roomId);
        jdbc.update("""
                INSERT INTO matches (mode, room_id, series_no, round_no, attempt_no, seed, status, end_reason, started_at, ended_at, duration_ms)
                VALUES ('FRIEND', ?, 1, 1, ?, 1, ?, 'TOP_OUT', ?, ?, 1000)""", roomId, next, status,
                Timestamp.from(endedAt.minusSeconds(60)), Timestamp.from(endedAt));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM matches", Long.class);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, 1, ?, 'USER', 'WIN')", id, user);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, 2, ?, 'USER', 'LOSE')", id, other);
        return id;
    }

    private long soloMatch(String status, Instant endedAt) {
        Instant started = endedAt == null ? cutoff.minusSeconds(86_400 * 60) : endedAt.minusSeconds(60);
        jdbc.update("""
                INSERT INTO matches (mode, seed, status, end_reason, started_at, ended_at) VALUES ('SOLO', 1, ?, ?, ?, ?)""",
                status, status.equals("PLAYING") ? null : "EXPIRED", Timestamp.from(started),
                endedAt == null ? null : Timestamp.from(endedAt));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM matches", Long.class);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, 1, ?, 'USER', 'NONE')", id, user);
        return id;
    }

    private List<Long> matchIds() {
        return jdbc.queryForList("SELECT id FROM matches", Long.class);
    }

    private List<Long> roomIds() {
        return jdbc.queryForList("SELECT id FROM game_rooms", Long.class);
    }
}
