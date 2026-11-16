package com.blockbattle.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blockbattle.support.ContainersConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway V1 결과를 ERD 명세(ENT-001~005)·보존 정책(POL-023)과 대조한다.
 * 깨지면: 승인된 데이터 계약과 실제 스키마가 어긋났다는 뜻이다.
 */
@SpringBootTest
@Import(ContainersConfig.class)
class SchemaContractTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
    }

    /** "컬럼 타입|NULL 여부|기본값" — ERD 명세 표 그대로 */
    static final Map<String, Map<String, String>> COLUMNS = Map.of(
            "users", Map.of(
                    "id", "bigint|NO|null",
                    "nickname", "varchar(20)|NO|null",
                    "tag", "char(4)|NO|null",
                    "user_type", "enum('GUEST','MEMBER')|NO|GUEST",
                    "provider", "varchar(20)|YES|null",
                    "provider_id", "varchar(100)|YES|null",
                    "created_at", "datetime(6)|NO|null",
                    "last_login_at", "datetime(6)|YES|null"),
            "user_stats", Map.of(
                    "user_id", "bigint|NO|null",
                    "solo_games", "int|NO|0",
                    "solo_best_score", "int|NO|0",
                    "solo_best_achieved_at", "datetime(6)|YES|null",
                    "ai_wins", "int|NO|0",
                    "ai_losses", "int|NO|0",
                    "friend_wins", "int|NO|0",
                    "friend_losses", "int|NO|0",
                    "updated_at", "datetime(6)|NO|null"),
            "game_rooms", Map.of(
                    "id", "bigint|NO|null",
                    "room_code", "char(6)|NO|null",
                    "host_user_id", "bigint|NO|null",
                    "status", "enum('WAITING','PLAYING','FINISHED','CLOSED')|NO|null",
                    "round_count", "tinyint|NO|1",
                    "close_reason", "enum('HOST_LEFT','EMPTY','EXPIRED','UNRECOVERABLE')|YES|null",
                    "created_at", "datetime(6)|NO|null",
                    "closed_at", "datetime(6)|YES|null"),
            "matches", Map.ofEntries(
                    Map.entry("id", "bigint|NO|null"),
                    Map.entry("mode", "enum('SOLO','AI','FRIEND')|NO|null"),
                    Map.entry("room_id", "bigint|YES|null"),
                    Map.entry("series_no", "int|YES|null"),
                    Map.entry("round_no", "tinyint|YES|null"),
                    Map.entry("attempt_no", "tinyint|YES|null"),
                    Map.entry("ai_difficulty", "enum('EASY','NORMAL','HARD','IMPOSSIBLE')|YES|null"),
                    Map.entry("seed", "bigint|NO|null"),
                    Map.entry("status", "enum('PLAYING','FINISHED','ABORTED')|NO|null"),
                    Map.entry("end_reason", "enum('TOP_OUT','DRAW','LEFT','DISCONNECT','EXPIRED','COMPLETED')|YES|null"),
                    Map.entry("winner_user_id", "bigint|YES|null"),
                    Map.entry("series_winner_user_id", "bigint|YES|null"),
                    Map.entry("ranked", "tinyint(1)|NO|1"),
                    Map.entry("started_at", "datetime(6)|NO|null"),
                    Map.entry("ended_at", "datetime(6)|YES|null"),
                    Map.entry("duration_ms", "int|YES|null"),
                    Map.entry("key_presses", "int|YES|null"),
                    Map.entry("input_stats", "json|YES|null")),
            "match_players", Map.ofEntries(
                    Map.entry("id", "bigint|NO|null"),
                    Map.entry("match_id", "bigint|NO|null"),
                    Map.entry("slot", "tinyint|NO|null"),
                    Map.entry("user_id", "bigint|YES|null"),
                    Map.entry("player_type", "enum('USER','AI')|NO|null"),
                    Map.entry("result", "enum('WIN','LOSE','NONE')|NO|null"),
                    Map.entry("score", "int|NO|0"),
                    Map.entry("lines_cleared", "int|NO|0"),
                    Map.entry("level", "int|NO|1"),
                    Map.entry("pieces_placed", "int|NO|0"),
                    Map.entry("attack_sent", "int|NO|0"),
                    Map.entry("attack_received", "int|NO|0"),
                    Map.entry("max_combo", "int|NO|0")));

    @Test
    void 테이블별_컬럼_타입_NULL_기본값이_ERD와_같다() {
        COLUMNS.forEach((table, expected) -> {
            Map<String, String> actual = jdbc.queryForList("""
                    SELECT column_name AS c, column_type AS t, is_nullable AS n, column_default AS d
                    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?""", table)
                    .stream().collect(Collectors.toMap(
                            r -> (String) r.get("c"),
                            r -> r.get("t") + "|" + r.get("n") + "|" + r.get("d")));
            assertThat(actual).as(table).isEqualTo(expected);
        });
    }

    @Test
    void 기본키_유니크_인덱스가_ERD와_같다() {
        assertThat(indexColumns("users")).containsEntry("PRIMARY", "id")
                .containsEntry("uq_users_nickname_tag", "nickname,tag")
                .containsEntry("uq_users_provider", "provider,provider_id");
        assertThat(indexColumns("user_stats")).containsEntry("PRIMARY", "user_id")
                .containsEntry("idx_user_stats_ranking", "solo_best_score D,solo_best_achieved_at");
        assertThat(indexColumns("game_rooms")).containsEntry("idx_game_rooms_room_code", "room_code");
        assertThat(nonUnique("game_rooms", "idx_game_rooms_room_code")).as("room_code는 UNIQUE가 아니다").isTrue();
        assertThat(indexColumns("matches"))
                .containsEntry("idx_matches_mode_started", "mode,started_at")
                .containsEntry("idx_matches_room", "room_id")
                .containsEntry("idx_matches_status_started", "status,started_at")
                .containsEntry("uq_matches_room_round", "room_id,series_no,round_no,attempt_no");
        assertThat(nonUnique("matches", "uq_matches_room_round")).isFalse();
        assertThat(indexColumns("match_players"))
                .containsEntry("uq_match_players_slot", "match_id,slot")
                .containsEntry("idx_match_players_user_match", "user_id,match_id");
    }

    @Test
    void 모든_FK는_CASCADE_없이_RESTRICT다() {
        List<Map<String, Object>> fks = jdbc.queryForList("""
                SELECT table_name AS t, referenced_table_name AS r, delete_rule AS d, update_rule AS u
                FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()""");
        // user_stats·game_rooms 각 1, matches 3(room·winner·series_winner), match_players 2(match·user)
        assertThat(fks).hasSize(7);
        assertThat(fks).allSatisfy(fk -> {
            assertThat(fk.get("d")).isEqualTo("RESTRICT");
            assertThat(fk.get("u")).isEqualTo("RESTRICT");
        });
    }

    @Test
    void 참조된_회원과_방은_지울_수_없다() {
        long user = insertUser("하나", "0001");
        long room = insertRoom(user);
        insertMatch("FRIEND", room, 1, 1, 1, null);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM game_rooms WHERE id = ?", room))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 모드별_필수_NULL_조건을_CHECK로_막는다() {
        long room = insertRoom(insertUser("둘", "0002"));
        // 허용
        insertMatch("SOLO", null, null, null, null, null);
        insertMatch("AI", null, null, null, null, "HARD");
        insertMatch("FRIEND", room, 1, 1, 1, null);
        // 거부
        assertCheckRejects("FRIEND", null, 1, 1, 1, null);
        assertCheckRejects("FRIEND", room, 1, 1, null, null);
        assertCheckRejects("SOLO", room, 1, 1, 1, null);
        assertCheckRejects("SOLO", null, 1, null, null, null);
        assertCheckRejects("AI", null, null, null, null, null);
        assertCheckRejects("SOLO", null, null, null, null, "EASY");
        assertCheckRejects("FRIEND", room, 1, 1, 2, "NORMAL");
    }

    @Test
    void 같은_방_시리즈_라운드_시도는_한_번만_있다() {
        long room = insertRoom(insertUser("셋", "0003"));
        insertMatch("FRIEND", room, 1, 1, 1, null);
        assertThatThrownBy(() -> insertMatch("FRIEND", room, 1, 1, 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertMatch("FRIEND", room, 1, 1, 2, null);
    }

    private void assertCheckRejects(String mode, Long room, Integer series, Integer round, Integer attempt, String difficulty) {
        assertThatThrownBy(() -> insertMatch(mode, room, series, round, attempt, difficulty))
                .as("%s room=%s series=%s round=%s attempt=%s difficulty=%s", mode, room, series, round, attempt, difficulty)
                // MySQL CHECK 위반(3819)은 Spring이 무결성 예외로 분류하지 않아 상위 DataAccessException으로 받는다
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_matches");
    }

    private long insertUser(String nickname, String tag) {
        jdbc.update("INSERT INTO users (nickname, tag, created_at) VALUES (?, ?, NOW(6))", nickname, tag);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRoom(long host) {
        jdbc.update("INSERT INTO game_rooms (room_code, host_user_id, status, created_at) VALUES ('ABC234', ?, 'WAITING', NOW(6))", host);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertMatch(String mode, Long room, Integer series, Integer round, Integer attempt, String difficulty) {
        jdbc.update("""
                INSERT INTO matches (mode, room_id, series_no, round_no, attempt_no, ai_difficulty, seed, status, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 42, 'PLAYING', NOW(6))""", mode, room, series, round, attempt, difficulty);
    }

    private Map<String, String> indexColumns(String table) {
        return jdbc.queryForList("""
                SELECT index_name AS i, GROUP_CONCAT(CONCAT(column_name, IF(collation = 'D', ' D', ''))
                    ORDER BY seq_in_index SEPARATOR ',') AS cols
                FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ?
                GROUP BY index_name""", table)
                .stream().collect(Collectors.toMap(r -> (String) r.get("i"), r -> (String) r.get("cols")));
    }

    private boolean nonUnique(String table, String index) {
        Integer value = jdbc.queryForObject("""
                SELECT MAX(non_unique) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?""", Integer.class, table, index);
        return value != null && value == 1;
    }
}
