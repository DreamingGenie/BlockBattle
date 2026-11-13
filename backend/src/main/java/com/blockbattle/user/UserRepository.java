package com.blockbattle.user;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** users·user_stats 저장(ENT-001·002) */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** UQ(nickname, tag)를 어기면 DuplicateKeyException */
    public long insertGuest(String nickname, String tag, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (nickname, tag, user_type, created_at, last_login_at) VALUES (?, ?, 'GUEST', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nickname);
            ps.setString(2, tag);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 게스트 로그인 때 user_stats 행도 함께 만든다(ERD 관계 users → user_stats 1:1) */
    public void insertStats(long userId, Instant now) {
        jdbc.update("INSERT INTO user_stats (user_id, updated_at) VALUES (?, ?)", userId, Timestamp.from(now));
    }

    public Set<String> findUsedTags(String nickname) {
        return new HashSet<>(jdbc.queryForList("SELECT tag FROM users WHERE nickname = ?", String.class, nickname));
    }

    public record UserRow(long id, String nickname, String tag, String userType, String provider) {
    }

    private static final String USER_COLUMNS = "SELECT id, nickname, tag, user_type, provider FROM users ";

    private UserRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("nickname"), rs.getString("tag"), rs.getString("user_type"),
                rs.getString("provider"));
    }

    /** 트랜잭션 안에서 행을 잠근다(SELECT … FOR UPDATE) */
    public Optional<UserRow> findForUpdate(long userId) {
        return jdbc.query(USER_COLUMNS + "WHERE id = ? FOR UPDATE", (rs, i) -> map(rs), userId).stream().findFirst();
    }

    public Optional<UserRow> findByProvider(String provider, String providerId) {
        return jdbc.query(USER_COLUMNS + "WHERE provider = ? AND provider_id = ?", (rs, i) -> map(rs), provider, providerId)
                .stream().findFirst();
    }

    /** UQ(provider, provider_id)를 어기면 DuplicateKeyException */
    public void linkProvider(long userId, String provider, String providerId) {
        jdbc.update("UPDATE users SET provider = ?, provider_id = ?, user_type = 'MEMBER' WHERE id = ?",
                provider, providerId, userId);
    }

    /** BR-09 싱글: 판 수 + 1 */
    public void incrementSoloGames(long userId, Instant now) {
        jdbc.update("UPDATE user_stats SET solo_games = solo_games + 1, updated_at = ? WHERE user_id = ?",
                Timestamp.from(now), userId);
    }

    /** BR-09 싱글: 기존 최고 점수를 넘을 때만 점수·달성 시각을 바꾼다. 바꿨으면 true */
    public boolean raiseSoloBest(long userId, int score, Instant now) {
        return jdbc.update("""
                UPDATE user_stats SET solo_best_score = ?, solo_best_achieved_at = ?, updated_at = ?
                WHERE user_id = ? AND solo_best_score < ?""",
                score, Timestamp.from(now), Timestamp.from(now), userId, score) == 1;
    }

    /** BR-09 AI: 승이면 ai_wins, 패면 ai_losses + 1 */
    public void recordAiResult(long userId, boolean win, Instant now) {
        String column = win ? "ai_wins" : "ai_losses";
        jdbc.update("UPDATE user_stats SET " + column + " = " + column + " + 1, updated_at = ? WHERE user_id = ?",
                Timestamp.from(now), userId);
    }

    public void touchLastLogin(long userId, Instant now) {
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", Timestamp.from(now), userId);
    }
}
