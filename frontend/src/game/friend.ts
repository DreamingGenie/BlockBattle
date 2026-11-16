import type { GarbageBundle, LockEvent, PieceType } from '../engine';

/** SEND /app/rooms/{code}/lock 본문(API 명세 5.2). lastMove는 회전이면 ROTATE, 아니면 MOVE(서버는 T-스핀 판정에만 쓴다) */
export function lockMessage(event: LockEvent, keyPresses?: number) {
  return {
    keyPresses, // 그 라운드 누적 실제 키 입력 수(T-41 봇 판정용)
    seq: event.seq,
    piece: event.placement.piece,
    rotation: event.placement.rotation,
    x: event.placement.x,
    y: event.placement.y,
    holdUsed: event.holdUsed,
    lastMove: event.lastAction === 'rotate' ? 'ROTATE' : 'MOVE',
    linesCleared: event.result.linesCleared,
    softDropCells: event.softDropCells,
    hardDropCells: event.hardDropCells,
  };
}

/** /user/queue/state RESYNC payload(API 명세 5.7) */
export interface ResyncPayload {
  matchId: number;
  seq: number;
  pieceIndex: number;
  hold: PieceType | null;
  holdUsed: boolean;
  board: string;
  pendingGarbage: GarbageBundle[];
  opponent?: { userId: number; board: string; score: number };
  /** 경기 시드와 서버 기준 내 성적(POL-006 v2). 새로 연 화면은 seed와 pieceIndex로 블록 순서를 다시 만든다 */
  seed?: number;
  me?: { score: number; linesCleared: number; level: number; combo: number; b2b: boolean };
}

/** RESYNC를 엔진 restore() 인자로 */
export function restoreArgs(p: ResyncPayload) {
  return {
    board: p.board,
    seq: p.seq,
    pieceIndex: p.pieceIndex,
    hold: p.hold,
    holdUsed: p.holdUsed,
    pending: p.pendingGarbage ?? [],
    stats: p.me ? { score: p.me.score, linesCleared: p.me.linesCleared, combo: p.me.combo, b2b: p.me.b2b } : undefined,
  };
}

/** GAME_OVER payload(API 명세 5.5) */
export interface GameOverPayload {
  matchId: number;
  winnerUserId: number | null;
  reason: 'TOP_OUT' | 'DRAW' | 'LEFT' | 'DISCONNECT';
  seriesWins: Record<string, number>;
  seriesWinnerUserId: number | null;
  nextRoundStartsAt: number | null;
  stats: { userId: number; score: number; attackSent: number; attackReceived: number; maxCombo: number; apm: number }[];
  /** 봇 의심으로 이번 라운드를 통계에 넣지 않은 사용자(T-41). 옛 서버는 보내지 않는다 */
  botSuspectedUserIds?: number[];
}

export const REASON_TEXT: Record<GameOverPayload['reason'], string> = {
  TOP_OUT: '블록이 천장에 닿음',
  DRAW: '동시 topout — 무승부',
  LEFT: '경기 중 퇴장',
  DISCONNECT: '연결이 끊긴 뒤 10초 안에 돌아오지 않음',
};
