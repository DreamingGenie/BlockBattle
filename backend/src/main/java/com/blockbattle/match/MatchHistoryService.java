package com.blockbattle.match;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.time.ApiTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 끝난 경기 목록·경기 상세 조회(REQ-015, API-007·API-016). 경기 기록 원본은 MySQL이다(POL-011).
 * APM은 저장하지 않고 attack_sent와 duration_ms로 계산한다(POL-012).
 */
@Service
public class MatchHistoryService {

    public record Opponent(String playerType, Long userId, String nickname, String tag, String aiDifficulty) {
    }

    /** opponent는 첫 상대(슬롯 순), playerCount는 참가자 수, myPlacement는 친구 대전 등수(V5 전 경기·싱글·AI는 null) */
    public record MatchItem(long matchId, String mode, String status, String endReason, OffsetDateTime startedAt,
                            Integer durationMs, String myResult, int myScore, Opponent opponent, int playerCount,
                            Integer myPlacement) {
    }

    public record MatchPage(List<MatchItem> items, int page, int size, long totalElements) {
    }

    public record Room(String roomCode, int seriesNo, int roundNo, int attemptNo) {
    }

    public record PlayerDetail(int slot, String playerType, Long userId, String nickname, String tag, String result,
                               int score, int linesCleared, int level, int piecesPlaced, int attackSent,
                               int attackReceived, int maxCombo, double apm, Integer placement) {
    }

    public record MatchDetail(long matchId, String mode, String status, String endReason, String aiDifficulty,
                              boolean ranked, Room room, OffsetDateTime startedAt, OffsetDateTime endedAt,
                              Integer durationMs, Long winnerUserId, List<PlayerDetail> players) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MatchHistoryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 내가 참가한 FINISHED·ABORTED 경기, started_at 내림차순(같으면 id 내림차순) */
    public MatchPage list(long userId, String mode, int page, int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("uid", userId);
        p.put("mode", mode);
        p.put("size", size);
        p.put("offset", (long) page * size);
        String where = """
                FROM match_players me JOIN matches m ON m.id = me.match_id
                WHERE me.user_id = :uid AND m.status IN ('FINISHED', 'ABORTED')
                  AND (:mode IS NULL OR m.mode = :mode)""";
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, p, Long.class);
        List<MatchItem> items = jdbc.query("""
                SELECT m.id, m.mode, m.status, m.end_reason, m.started_at, m.duration_ms, m.ai_difficulty,
                       me.result, me.score, me.placement, op.player_type AS op_type, op.user_id AS op_user, u.nickname, u.tag,
                       (SELECT COUNT(*) FROM match_players c WHERE c.match_id = m.id) AS player_count
                """ + where.replace("WHERE", """
                LEFT JOIN match_players op ON op.match_id = m.id AND op.slot = (
                    SELECT MIN(o.slot) FROM match_players o WHERE o.match_id = m.id AND o.slot <> me.slot)
                LEFT JOIN users u ON u.id = op.user_id
                WHERE""") + " ORDER BY m.started_at DESC, m.id DESC LIMIT :size OFFSET :offset", p, (rs, i) -> {
            Opponent opponent = rs.getString("op_type") == null ? null : new Opponent(rs.getString("op_type"),
                    longOrNull(rs, "op_user"), rs.getString("nickname"), rs.getString("tag"),
                    "AI".equals(rs.getString("op_type")) ? rs.getString("ai_difficulty") : null);
            return new MatchItem(rs.getLong("id"), rs.getString("mode"), rs.getString("status"), rs.getString("end_reason"),
                    time(rs.getTimestamp("started_at")), intOrNull(rs, "duration_ms"), rs.getString("result"),
                    rs.getInt("score"), opponent, rs.getInt("player_count"), intOrNull(rs, "placement"));
        });
        return new MatchPage(items, page, size, total == null ? 0 : total);
    }

    /** 참가자만 볼 수 있다. 없으면 MATCH_NOT_FOUND, 참가자가 아니면 NOT_PARTICIPANT */
    public MatchDetail detail(long userId, long matchId) {
        Map<String, Object> p = Map.of("id", matchId);
        // 시각은 getTimestamp로 읽는다: queryForList가 주는 LocalDateTime을 JVM 시간대로 바꾸면 serverTimezone=UTC 연결에서 9시간 어긋난다(T-23에서 발견)
        List<DetailRow> rows = jdbc.query("""
                SELECT m.id, m.mode, m.status, m.end_reason, m.ai_difficulty, m.ranked, m.series_no, m.round_no,
                       m.attempt_no, m.started_at, m.ended_at, m.duration_ms, m.winner_user_id, r.room_code
                FROM matches m LEFT JOIN game_rooms r ON r.id = m.room_id
                WHERE m.id = :id""", p, (rs, i) -> new DetailRow(rs.getString("mode"), rs.getString("status"),
                rs.getString("end_reason"), rs.getString("ai_difficulty"), rs.getBoolean("ranked"), intOrNull(rs, "series_no"),
                intOrNull(rs, "round_no"), intOrNull(rs, "attempt_no"), rs.getTimestamp("started_at"), rs.getTimestamp("ended_at"),
                intOrNull(rs, "duration_ms"), longOrNull(rs, "winner_user_id"), rs.getString("room_code")));
        if (rows.isEmpty()) throw new ApiException(ErrorCode.MATCH_NOT_FOUND);
        DetailRow m = rows.get(0);
        Integer durationMs = m.durationMs();
        List<PlayerDetail> players = jdbc.query("""
                SELECT p.slot, p.player_type, p.user_id, u.nickname, u.tag, p.result, p.score, p.lines_cleared, p.level,
                       p.pieces_placed, p.attack_sent, p.attack_received, p.max_combo, p.placement
                FROM match_players p LEFT JOIN users u ON u.id = p.user_id
                WHERE p.match_id = :id ORDER BY p.slot""", p, (rs, i) -> new PlayerDetail(rs.getInt("slot"),
                rs.getString("player_type"), longOrNull(rs, "user_id"), rs.getString("nickname"), rs.getString("tag"),
                rs.getString("result"), rs.getInt("score"), rs.getInt("lines_cleared"), rs.getInt("level"),
                rs.getInt("pieces_placed"), rs.getInt("attack_sent"), rs.getInt("attack_received"), rs.getInt("max_combo"),
                Apm.of(rs.getInt("attack_sent"), durationMs), intOrNull(rs, "placement")));
        if (players.stream().noneMatch(pl -> pl.userId() != null && pl.userId() == userId)) {
            throw new ApiException(ErrorCode.NOT_PARTICIPANT);
        }
        Room room = !"FRIEND".equals(m.mode()) ? null : new Room(m.roomCode(), m.seriesNo(), m.roundNo(), m.attemptNo());
        return new MatchDetail(matchId, m.mode(), m.status(), m.endReason(), m.aiDifficulty(), m.ranked(), room,
                time(m.startedAt()), time(m.endedAt()), durationMs, m.winnerUserId(), players);
    }

    private static OffsetDateTime time(Timestamp t) {
        return t == null ? null : ApiTime.of(t.toInstant());
    }

    private record DetailRow(String mode, String status, String endReason, String aiDifficulty, boolean ranked,
                             Integer seriesNo, Integer roundNo, Integer attemptNo, Timestamp startedAt, Timestamp endedAt,
                             Integer durationMs, Long winnerUserId, String roomCode) {
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
