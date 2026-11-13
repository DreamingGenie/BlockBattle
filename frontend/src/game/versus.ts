import { BOARD_WIDTH, type Game, type GameEvent } from '../engine';

/**
 * vs AI 공격 교환(GR-08, T-20). 한쪽 lock 결과에서 상쇄 뒤 남은 공격(sent)을 상대 대기열에 한 묶음으로 넣는다.
 * 구멍 열은 묶음마다 무작위(엔진 receiveGarbage 주석: 호출자가 정한다). 보낸 줄 수를 돌려준다.
 */
export function forwardAttacks(events: GameEvent[], target: Game, rand: () => number = Math.random): number {
  let sent = 0;
  for (const event of events) {
    if (event.type !== 'lock' || event.result.sent <= 0) continue;
    target.receiveGarbage({ lines: event.result.sent, holeColumn: Math.floor(rand() * BOARD_WIDTH) });
    sent += event.result.sent;
  }
  return sent;
}

/** POST /matches/{id}/result 본문(API 명세 3.9) — vs AI는 승패와 양쪽 성적(공격 보냄·받음 포함) */
export function aiResultBody(me: Game, ai: Game, win: boolean, durationMs: number) {
  const stats = (g: Game) => ({
    score: g.score,
    linesCleared: g.linesCleared,
    level: g.level,
    piecesPlaced: g.piecesPlaced,
    attackSent: g.attackSent,
    attackReceived: g.attackReceived,
    maxCombo: Math.max(0, g.maxCombo),
  });
  return { result: win ? 'WIN' : 'LOSE', durationMs: Math.round(durationMs), player: stats(me), ai: stats(ai) };
}
