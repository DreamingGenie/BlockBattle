import type { Game } from '../engine';
import type { InputStats } from './inputStats';

/** POST /matches/{id}/result 본문(API 명세 3.9). 싱글은 result·ai·공격 수치를 보내지 않는다 */
export function soloResultBody(game: Game, durationMs: number, keyPresses?: number, inputStats?: InputStats) {
  return {
    durationMs: Math.round(durationMs),
    keyPresses,
    inputStats,
    player: {
      score: game.score,
      linesCleared: game.linesCleared,
      level: game.level,
      piecesPlaced: game.piecesPlaced,
      maxCombo: Math.max(0, game.maxCombo),
    },
  };
}
