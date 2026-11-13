package com.blockbattle.battle;

import com.blockbattle.engine.Board;
import com.blockbattle.engine.GarbageBundle;
import com.blockbattle.engine.Piece;
import com.blockbattle.store.redis.MatchStateStore;
import com.blockbattle.store.redis.MatchStateStore.Field;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** PlayerState ↔ match:{matchId}:state Hash 필드("{userId}:{필드}", T-1 판단) */
@Component
public class PlayerStateRepository {

    private final MatchStateStore store;
    private final ObjectMapper json;

    public PlayerStateRepository(MatchStateStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    /** 아직 없으면 경기 시작 상태(빈 보드, seq 0, pieceIndex 0) */
    public PlayerState load(long matchId, long userId) {
        Map<Field, String> f = store.getPlayerState(matchId, userId);
        if (f.get(Field.BOARD) == null) return PlayerState.initial();
        return new PlayerState(Board.parse(f.get(Field.BOARD)), i(f, Field.SEQ), i(f, Field.PIECE_INDEX),
                f.getOrDefault(Field.HOLD, "").isEmpty() ? null : Piece.valueOf(f.get(Field.HOLD)),
                Boolean.parseBoolean(f.get(Field.HOLD_USED)), readPending(f.get(Field.PENDING_GARBAGE)), i(f, Field.COMBO),
                Boolean.parseBoolean(f.get(Field.B2B)), i(f, Field.SCORE), i(f, Field.LINES_CLEARED), i(f, Field.LEVEL),
                i(f, Field.PIECES_PLACED), i(f, Field.ATTACK_SENT), i(f, Field.ATTACK_RECEIVED), i(f, Field.MAX_COMBO),
                f.getOrDefault(Field.TOPOUT_AT, "").isEmpty() ? null : Long.parseLong(f.get(Field.TOPOUT_AT)));
    }

    /** 저장하면서 경기 상태 TTL을 2시간으로 되돌린다(lock·재접속마다) */
    public void save(long matchId, long userId, PlayerState s) {
        Map<Field, String> f = new EnumMap<>(Field.class);
        f.put(Field.BOARD, s.board().toString());
        f.put(Field.SEQ, Integer.toString(s.seq()));
        f.put(Field.PIECE_INDEX, Integer.toString(s.pieceIndex()));
        f.put(Field.HOLD, s.hold() == null ? "" : s.hold().name());
        f.put(Field.HOLD_USED, Boolean.toString(s.holdUsed()));
        f.put(Field.PENDING_GARBAGE, writePending(s.pending()));
        f.put(Field.COMBO, Integer.toString(s.combo()));
        f.put(Field.B2B, Boolean.toString(s.b2b()));
        f.put(Field.SCORE, Integer.toString(s.score()));
        f.put(Field.LINES_CLEARED, Integer.toString(s.linesCleared()));
        f.put(Field.LEVEL, Integer.toString(s.level()));
        f.put(Field.PIECES_PLACED, Integer.toString(s.piecesPlaced()));
        f.put(Field.ATTACK_SENT, Integer.toString(s.attackSent()));
        f.put(Field.ATTACK_RECEIVED, Integer.toString(s.attackReceived()));
        f.put(Field.MAX_COMBO, Integer.toString(s.maxCombo()));
        f.put(Field.TOPOUT_AT, s.topoutAt() == null ? "" : Long.toString(s.topoutAt()));
        store.putPlayerState(matchId, userId, f);
    }

    private static int i(Map<Field, String> f, Field field) {
        return Integer.parseInt(f.getOrDefault(field, "0"));
    }

    private List<GarbageBundle> readPending(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<GarbageBundle>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("pendingGarbage 형식 오류", e);
        }
    }

    private String writePending(List<GarbageBundle> pending) {
        try {
            return json.writeValueAsString(pending);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
