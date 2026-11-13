package com.blockbattle.match;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** matches·match_players 저장(ENT-004·005) */
@Repository
public class MatchRepository {

    public record MatchRow(long id, String mode, String status, long ownerUserId) {
    }

    public record PlayerStats(int score, int linesCleared, int level, int piecesPlaced, int attackSent, int attackReceived,
                              int maxCombo) {
    }

    private final JdbcTemplate jdbc;

    public MatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertMatch(String mode, String aiDifficulty, long seed, Instant startedAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO matches (mode, ai_difficulty, seed, status, started_at) VALUES (?, ?, ?, 'PLAYING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, mode);
            ps.setString(2, aiDifficulty);
            ps.setLong(3, seed);
            ps.setTimestamp(4, Timestamp.from(startedAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 친구 대전 경기 행(BR-02: COUNTDOWN 때 PLAYING으로). started_at은 COUNTDOWN의 startsAt이다 */
    public long insertFriendMatch(long roomId, int seriesNo, int roundNo, int attemptNo, long seed, Instant startsAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO matches (mode, room_id, series_no, round_no, attempt_no, seed, status, started_at)
                    VALUES ('FRIEND', ?, ?, ?, ?, ?, 'PLAYING', ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, roomId);
            ps.setInt(2, seriesNo);
            ps.setInt(3, roundNo);
            ps.setInt(4, attemptNo);
            ps.setLong(5, seed);
            ps.setTimestamp(6, Timestamp.from(startsAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 참가자 행은 경기 행과 함께 성적 0, result NONE으로 만든다(ENT-005) */
    public void insertPlayer(long matchId, int slot, Long userId, String playerType) {
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, ?, ?, ?, 'NONE')",
                matchId, slot, userId, playerType);
    }

    /** 경기 행을 잠그고 1번 자리(사용자) 주인을 함께 읽는다 */
    public Optional<MatchRow> findForUpdate(long matchId) {
        return jdbc.query("""
                SELECT m.id, m.mode, m.status, p.user_id
                FROM matches m LEFT JOIN match_players p ON p.match_id = m.id AND p.slot = 1
                WHERE m.id = ? FOR UPDATE""",
                (rs, i) -> new MatchRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), matchId)
                .stream().findFirst();
    }

    public void finish(long matchId, Instant endedAt, int durationMs, boolean ranked, Long winnerUserId) {
        jdbc.update("""
                UPDATE matches SET status = 'FINISHED', end_reason = 'COMPLETED', ended_at = ?, duration_ms = ?, ranked = ?,
                    winner_user_id = ?
                WHERE id = ?""", Timestamp.from(endedAt), durationMs, ranked, winnerUserId, matchId);
    }

    public void updatePlayer(long matchId, int slot, String result, PlayerStats s) {
        jdbc.update("""
                UPDATE match_players SET result = ?, score = ?, lines_cleared = ?, level = ?, pieces_placed = ?,
                    attack_sent = ?, attack_received = ?, max_combo = ?
                WHERE match_id = ? AND slot = ?""",
                result, s.score(), s.linesCleared(), s.level(), s.piecesPlaced(), s.attackSent(), s.attackReceived(),
                s.maxCombo(), matchId, slot);
    }

    public record FriendMatch(long id, long roomId, Instant startedAt, Map<Long, Integer> slots) {
    }

    public Optional<FriendMatch> findFriendMatch(long matchId) {
        var rows = jdbc.queryForList("""
                SELECT m.id, m.room_id, m.started_at, p.slot, p.user_id
                FROM matches m JOIN match_players p ON p.match_id = m.id
                WHERE m.id = ? AND m.mode = 'FRIEND'""", matchId);
        if (rows.isEmpty()) return Optional.empty();
        Map<Long, Integer> slots = new java.util.LinkedHashMap<>();
        for (var r : rows) slots.put(((Number) r.get("user_id")).longValue(), ((Number) r.get("slot")).intValue());
        Instant startedAt = jdbc.queryForObject("SELECT started_at FROM matches WHERE id = ?", Timestamp.class, matchId).toInstant();
        return Optional.of(new FriendMatch(matchId, ((Number) rows.get(0).get("room_id")).longValue(), startedAt, slots));
    }

    /** 친구 대전 종료(BR-02·BR-04). 아직 PLAYING일 때만 바꾼다 — 동시에 여러 사유가 와도 한 번만 확정된다 */
    public boolean finishFriend(long matchId, String status, String endReason, Long winnerUserId, Long seriesWinnerUserId,
                                Instant endedAt, int durationMs) {
        return jdbc.update("""
                UPDATE matches SET status = ?, end_reason = ?, winner_user_id = ?, series_winner_user_id = ?,
                    ended_at = ?, duration_ms = ?
                WHERE id = ? AND status = 'PLAYING'""",
                status, endReason, winnerUserId, seriesWinnerUserId, Timestamp.from(endedAt), durationMs, matchId) == 1;
    }

    /** BR-02: 결과가 저장되지 않은 SOLO·AI 경기를 시작 2시간 뒤 ABORTED(EXPIRED)로 닫는다 */
    public int expireUnsaved(Instant startedBefore, Instant now) {
        return jdbc.update("""
                UPDATE matches SET status = 'ABORTED', end_reason = 'EXPIRED', ended_at = ?
                WHERE status = 'PLAYING' AND mode IN ('SOLO', 'AI') AND started_at <= ?""",
                Timestamp.from(now), Timestamp.from(startedBefore));
    }
}
