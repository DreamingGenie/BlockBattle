package com.blockbattle.user;

import com.blockbattle.room.RoomService;
import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 명세 3.6 GET /users/me (API-006, FR-USR-03). 원본 MySQL users·user_stats에서 읽는다(POL-011). */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/users")
public class MeController {

    public record Stats(int soloGames, int soloBestScore, int aiWins, int aiLosses, int friendWins, int friendLosses) {
    }

    /** currentRoomCode: 지금 참가 중인 친구 대전 방(없으면 null). 창을 닫아도 방에서 나간 것이 아니라 남아 있을 수 있다 */
    public record Me(long userId, String nickname, String tag, String userType, Stats stats, String currentRoomCode) {
    }

    private final JdbcTemplate jdbc;
    private final RoomService rooms;

    public MeController(JdbcTemplate jdbc, RoomService rooms) {
        this.jdbc = jdbc;
        this.rooms = rooms;
    }

    @GetMapping("/me")
    public ApiResponse<Me> me(@RequestAttribute(AccessTokenFilter.USER_ID) long userId) {
        Me me = jdbc.query("""
                SELECT u.id, u.nickname, u.tag, u.user_type, s.solo_games, s.solo_best_score, s.ai_wins, s.ai_losses,
                       s.friend_wins, s.friend_losses
                FROM users u JOIN user_stats s ON s.user_id = u.id
                WHERE u.id = ?""",
                (rs, i) -> new Me(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        new Stats(rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10)),
                        rooms.currentRoom(userId).orElse(null)),
                userId).stream().findFirst()
                // 서명은 맞지만 사용자가 없는 토큰(삭제된 계정 등)은 인증 실패로 본다
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return ApiResponse.ok(me);
    }
}
