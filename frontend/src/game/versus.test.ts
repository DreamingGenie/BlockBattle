import { describe, expect, it } from 'vitest';
import { Game, type GameEvent, type LockEvent } from '../engine';
import { aiResultBody, forwardAttacks } from './versus';

/** GR-08 공격 전달과 API 명세 3.9 vs AI 결과 본문. 깨지면 방해 줄이 상대에게 안 가거나 서버가 결과를 거부한다 */
function lockWithSent(sent: number): LockEvent {
  return { type: 'lock', seq: 1, placement: { piece: 'I', rotation: 0, x: 3, y: 20 }, holdUsed: false, lastAction: 'none', result: { sent } } as unknown as LockEvent;
}

describe('forwardAttacks', () => {
  it('상쇄 뒤 남은 공격만 상대 대기열에 한 묶음씩 넣는다', () => {
    const target = new Game(1);
    const events: GameEvent[] = [lockWithSent(0), lockWithSent(4), { type: 'topout' }, lockWithSent(2)];
    expect(forwardAttacks(events, target, () => 0.35)).toBe(6);
    expect(target.pending).toEqual([
      { lines: 4, holeColumn: 3 },
      { lines: 2, holeColumn: 3 },
    ]);
    expect(target.attackReceived).toBe(6);
  });
});

describe('aiResultBody', () => {
  it('승패와 양쪽 성적을 담는다', () => {
    const me = new Game(5);
    const ai = new Game(5);
    me.hardDrop();
    const body = aiResultBody(me, ai, true, 61000.4);
    expect(body.result).toBe('WIN');
    expect(body.durationMs).toBe(61000);
    expect(body.player).toMatchObject({ piecesPlaced: 1, attackSent: 0, attackReceived: 0 });
    expect(body.ai).toMatchObject({ piecesPlaced: 0, score: 0, maxCombo: 0 });
    expect(aiResultBody(me, ai, false, 1).result).toBe('LOSE');
  });
});
