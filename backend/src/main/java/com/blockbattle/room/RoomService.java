package com.blockbattle.room;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import com.blockbattle.user.UserRepository;
import com.blockbattle.user.UserRepository.UserRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 친구 대전 방(FR-ROM-01·02). 진행 상태의 원본은 Redis 방 키, MySQL game_rooms는 이력이다.
 * 한 사람 한 방은 user:{id}:room SETNX, 코드 중복은 room:{code} 선점, 정원·상태는 Lua로 원자적으로 막는다.
 */
@Service
public class RoomService {

    public record PlayerView(long userId, String nickname, String tag, boolean ready) {
    }

    public record RoomView(String roomCode, String status, int roundCount, long hostUserId, List<PlayerView> players,
                           Instant expiresAt) {
    }

    public record Created(RoomView room, String inviteUrl) {
    }

    static final int CAPACITY = 2;
    private static final int CODE_ATTEMPTS = 20;

    private final RoomStateStore rooms;
    private final RoomRepository roomRepository;
    private final UserRepository users;
    private final RoomCodeGenerator codes;
    private final RoomEventPublisher events;
    private final Clock clock;
    private final String publicBaseUrl;
    private final RoomLeaveHook leaveHook;

    public RoomService(RoomStateStore rooms, RoomRepository roomRepository, UserRepository users, RoomCodeGenerator codes,
                       RoomEventPublisher events, Clock clock, @Value("${app.public-base-url}") String publicBaseUrl,
                       RoomLeaveHook leaveHook) {
        this.leaveHook = leaveHook;
        this.rooms = rooms;
        this.roomRepository = roomRepository;
        this.users = users;
        this.codes = codes;
        this.events = events;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl;
    }

    public Created create(long userId, Integer roundCountOrNull) {
        int roundCount = roundCountOrNull == null ? 1 : roundCountOrNull;
        if (roundCount != 1 && roundCount != 3) throw new ApiException(ErrorCode.INVALID_ROUND_COUNT);
        if (rooms.findUserRoom(userId).isPresent()) throw new ApiException(ErrorCode.ALREADY_IN_ROOM);
        UserRow me = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        String code = reserveCode(userId);
        if (!rooms.bindUserRoomIfAbsent(userId, code)) {
            rooms.deleteRoom(code);
            throw new ApiException(ErrorCode.ALREADY_IN_ROOM);
        }
        try {
            long roomId = roomRepository.insertWaiting(code, userId, roundCount, clock.instant());
            rooms.putRoomFields(code, Map.of("roomId", Long.toString(roomId), "hostId", Long.toString(userId),
                    "status", "WAITING", "roundCount", Integer.toString(roundCount),
                    "seriesNo", "1", "roundNo", "1", "attemptNo", "1"));
            rooms.putPlayer(code, userId, new RoomPlayer(me.nickname(), me.tag(), false, false, false));
            rooms.touch(code);
        } catch (RuntimeException e) {
            rooms.deleteRoom(code);
            rooms.unbindUserRoom(userId);
            throw e;
        }
        return new Created(view(code), publicBaseUrl + "/room/" + code);
    }

    public RoomView get(String code) {
        return view(code);
    }

    /** 이미 그 방 참가자면 입장 없이 같은 응답(API 명세 3.14) */
    public RoomView join(long userId, String code) {
        Map<String, String> room = rooms.getRoom(code);
        if (room.get("status") == null) throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
        if (rooms.getPlayers(code).containsKey(userId)) return view(code);
        String current = rooms.findUserRoom(userId).orElse(null);
        if (current != null && !current.equals(code)) throw new ApiException(ErrorCode.ALREADY_IN_ROOM);
        UserRow me = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        boolean bound = current != null || rooms.bindUserRoomIfAbsent(userId, code);
        if (!bound) throw new ApiException(ErrorCode.ALREADY_IN_ROOM);
        long result = rooms.joinAtomically(code, userId, new RoomPlayer(me.nickname(), me.tag(), false, false, false), CAPACITY);
        if (result < 0) {
            rooms.unbindUserRoom(userId);
            throw new ApiException(result == -1 ? ErrorCode.ROOM_NOT_FOUND
                    : result == -2 ? ErrorCode.ROOM_ALREADY_PLAYING : ErrorCode.ROOM_FULL);
        }
        rooms.touch(code); // 입장은 활동이다(POL-024)
        if (result == 1) {
            events.publish(code, "PLAYER_JOINED", Map.of("userId", userId, "nickname", me.nickname(), "tag", me.tag()));
        }
        return view(code);
    }

    /**
     * 퇴장(API 명세 3.15). 방장이 나가면 방을 닫고(HOST_LEFT), 마지막 사람이 나가면 EMPTY로 닫는다.
     * 경기 중이면 RoomLeaveHook으로 승패 처리를 먼저 넘긴다(T-13).
     */
    public void leave(long userId, String code) {
        Map<String, String> room = rooms.getRoom(code);
        if (room.get("status") == null) throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        if (!players.containsKey(userId)) throw new ApiException(ErrorCode.NOT_PARTICIPANT);

        String currentMatch = room.get("currentMatchId");
        if ("PLAYING".equals(room.get("status")) && currentMatch != null && !currentMatch.isBlank()) {
            leaveHook.onLeaveDuringMatch(code, Long.parseLong(currentMatch), userId);
        }
        long roomId = Long.parseLong(room.get("roomId"));
        boolean host = Long.toString(userId).equals(room.get("hostId"));
        if (host) {
            closeRoom(code, roomId, "HOST_LEFT");
            return;
        }
        rooms.removePlayer(code, userId);
        rooms.unbindUserRoom(userId);
        if (players.size() <= 1) {
            closeRoom(code, roomId, "EMPTY");
            return;
        }
        rooms.touch(code); // 퇴장도 활동이다(POL-024)
        events.publish(code, "PLAYER_LEFT", Map.of("userId", userId, "reason", "LEFT"));
    }

    /**
     * 비활성 만료(POL-024·BR-01): 열린 game_rooms 중 Redis 방 키가 없는(또는 같은 코드를 새 방이 쓰는) 방은 EXPIRED,
     * 키는 있으나 필수 필드가 깨진 방은 UNRECOVERABLE로 닫고, 진행 중 경기는 ABORTED(같은 사유)로 끝낸다.
     */
    public int expireRooms() {
        int closed = 0;
        for (RoomRepository.OpenRoom open : roomRepository.findOpen()) {
            Map<String, String> room = rooms.getRoom(open.code());
            String reason;
            if (room.isEmpty() || !Long.toString(open.id()).equals(room.get("roomId"))) {
                reason = "EXPIRED";
            } else if (room.get("status") == null || room.get("hostId") == null) {
                reason = "UNRECOVERABLE";
            } else {
                continue;
            }
            var now = clock.instant();
            if (!roomRepository.close(open.id(), reason, now)) continue;
            roomRepository.abortPlayingMatches(open.id(), reason, now);
            if (!room.isEmpty() && reason.equals("UNRECOVERABLE")) rooms.deleteRoom(open.code());
            events.publish(open.code(), "ROOM_CLOSED", Map.of("reason", reason));
            closed++;
        }
        return closed;
    }

    /** MySQL 이력을 CLOSED로 바꾸고 Redis 진행 상태를 지운 뒤 ROOM_CLOSED를 방송한다(BR-01) */
    void closeRoom(String code, long roomId, String reason) {
        roomRepository.close(roomId, reason, clock.instant());
        rooms.deleteRoom(code);
        events.publish(code, "ROOM_CLOSED", Map.of("reason", reason));
    }

    private String reserveCode(long hostId) {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = codes.next();
            if (rooms.reserveRoom(code, hostId)) return code;
        }
        throw new IllegalStateException("방 코드를 만들지 못했습니다");
    }

    private RoomView view(String code) {
        Map<String, String> room = rooms.getRoom(code);
        if (room.get("status") == null || room.get("hostId") == null) throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
        long hostId = Long.parseLong(room.get("hostId"));
        List<PlayerView> players = new ArrayList<>();
        rooms.getPlayers(code).forEach((id, p) -> players.add(new PlayerView(id, p.nickname(), p.tag(), p.ready())));
        players.sort(Comparator.comparing((PlayerView p) -> p.userId() != hostId).thenComparing(PlayerView::userId));
        long ttl = rooms.ttlSeconds(code);
        Instant expiresAt = clock.instant().plusSeconds(ttl > 0 ? ttl : RedisKeys.ROOM_TTL.toSeconds());
        return new RoomView(code, room.get("status"), Integer.parseInt(room.getOrDefault("roundCount", "1")), hostId,
                players, expiresAt);
    }
}
