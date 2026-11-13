package com.blockbattle.room;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** game_rooms 이력(ENT-003). 진행 중 상태의 원본은 Redis다. */
@Repository
public class RoomRepository {

    private final JdbcTemplate jdbc;

    public RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OpenRoom(long id, String code) {
    }

    /** BR-01: 닫힐 때 status = CLOSED, close_reason, closed_at = 확인 시각. 이미 닫혔으면 false */
    public boolean close(long roomId, String reason, Instant now) {
        return jdbc.update("UPDATE game_rooms SET status = 'CLOSED', close_reason = ?, closed_at = ? WHERE id = ? AND status <> 'CLOSED'",
                reason, Timestamp.from(now), roomId) == 1;
    }

    public java.util.List<OpenRoom> findOpen() {
        return jdbc.query("SELECT id, room_code FROM game_rooms WHERE status <> 'CLOSED'",
                (rs, i) -> new OpenRoom(rs.getLong(1), rs.getString(2)));
    }

    /** 만료·복구 불가로 닫힐 때 진행 중인 그 방의 경기를 ABORTED로 끝낸다(BR-01). 끝낸 경기 수 */
    public int abortPlayingMatches(long roomId, String endReason, Instant now) {
        return jdbc.update("UPDATE matches SET status = 'ABORTED', end_reason = ?, ended_at = ? WHERE room_id = ? AND status = 'PLAYING'",
                endReason, Timestamp.from(now), roomId);
    }

    public long insertWaiting(String code, long hostUserId, int roundCount, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO game_rooms (room_code, host_user_id, status, round_count, created_at) VALUES (?, ?, 'WAITING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setLong(2, hostUserId);
            ps.setInt(3, roundCount);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
