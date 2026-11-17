package com.blockbattle.presence;

import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.store.redis.PresenceStore;
import com.blockbattle.store.redis.PresenceStore.Counts;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /presence/heartbeat (T-49). 로그인한 클라이언트가 20초마다 보내고, 응답으로 지금 접속·플레이 중 인원을 받는다.
 * 싱글·vs AI는 서버가 진행을 모르므로 플레이 중 여부는 클라이언트가 알린다(표시용 수치라 위조돼도 그 사람 1명분만 달라진다).
 */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/presence")
public class PresenceController {

    public record Heartbeat(Boolean playing) {
    }

    private final PresenceStore store;

    public PresenceController(PresenceStore store) {
        this.store = store;
    }

    @PostMapping("/heartbeat")
    public ApiResponse<Counts> heartbeat(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                         @RequestBody(required = false) Heartbeat body) {
        boolean playing = body != null && Boolean.TRUE.equals(body.playing());
        return ApiResponse.ok(store.heartbeat(userId, playing));
    }
}
