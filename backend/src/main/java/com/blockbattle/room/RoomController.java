package com.blockbattle.room;

import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.common.time.ApiTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 명세 3.12~3.14 (API-011~013) */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/rooms")
public class RoomController {

    public record CreateRequest(Integer roundCount) {
    }

    public record CreateResponse(String roomCode, String inviteUrl, String status, int roundCount, long hostUserId,
                                 OffsetDateTime expiresAt) {
    }

    public record RoomResponse(String roomCode, String status, int roundCount, long hostUserId,
                               List<RoomService.PlayerView> players, OffsetDateTime expiresAt) {
    }

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateResponse>> create(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                                              @RequestBody(required = false) CreateRequest request) {
        var created = rooms.create(userId, request == null ? null : request.roundCount());
        var room = created.room();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new CreateResponse(room.roomCode(),
                created.inviteUrl(), room.status(), room.roundCount(), room.hostUserId(), ApiTime.of(room.expiresAt()))));
    }

    @GetMapping("/{roomCode}")
    public ApiResponse<RoomResponse> get(@PathVariable String roomCode) {
        return ApiResponse.ok(toResponse(rooms.get(roomCode)));
    }

    @PostMapping("/{roomCode}/join")
    public ApiResponse<RoomResponse> join(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                          @PathVariable String roomCode) {
        return ApiResponse.ok(toResponse(rooms.join(userId, roomCode)));
    }

    /** API 명세 3.15 (API-014): 본문 없음, 204 */
    @PostMapping("/{roomCode}/leave")
    public ResponseEntity<Void> leave(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                      @PathVariable String roomCode) {
        rooms.leave(userId, roomCode);
        return ResponseEntity.noContent().build();
    }

    /** 방 생성 본문이 JSON이 아니거나 roundCount가 정수가 아니면 라운드 수 오류로 본다 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable() {
        return GlobalExceptionHandler.toResponse(ErrorCode.INVALID_ROUND_COUNT);
    }

    private static RoomResponse toResponse(RoomService.RoomView v) {
        return new RoomResponse(v.roomCode(), v.status(), v.roundCount(), v.hostUserId(), v.players(), ApiTime.of(v.expiresAt()));
    }
}
