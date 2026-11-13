import { describe, expect, it } from 'vitest';
import { Game } from '../engine';
import { soloResultBody } from './result';

/** API 명세 3.9 싱글 결과 본문. 깨지면 서버가 INVALID_RESULT로 거부한다(싱글은 result·ai·공격 수치를 보내면 안 된다) */
describe('soloResultBody', () => {
  it('엔진 성적과 플레이 시간만 담는다', () => {
    const game = new Game(7);
    game.hardDrop();
    game.hardDrop();
    const body = soloResultBody(game, 1234.6);
    expect(body).toEqual({
      durationMs: 1235,
      player: { score: game.score, linesCleared: 0, level: 1, piecesPlaced: 2, maxCombo: 0 },
    });
    expect(body).not.toHaveProperty('result');
    expect(body.player).not.toHaveProperty('attackSent');
  });
});
