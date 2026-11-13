package com.blockbattle.battle;

import com.blockbattle.engine.Board;
import com.blockbattle.engine.GarbageBundle;
import com.blockbattle.engine.Piece;
import java.util.List;

/**
 * match:{matchId}:state 안의 플레이어 한 명 상태(ENT-009).
 * pieceIndex = 이번 차례 현재 블록의 시드 순번(홀드를 안 쓰면 이 블록을 놓아야 한다). 첫 블록은 0.
 */
public record PlayerState(Board board, int seq, int pieceIndex, Piece hold, boolean holdUsed, List<GarbageBundle> pending,
                          int combo, boolean b2b, int score, int linesCleared, int level, int piecesPlaced, int attackSent,
                          int attackReceived, int maxCombo, Long topoutAt) {

    public static PlayerState initial() {
        return new PlayerState(Board.empty(), 0, 0, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null);
    }

    public int pendingLines() {
        return pending.stream().mapToInt(GarbageBundle::lines).sum();
    }
}
