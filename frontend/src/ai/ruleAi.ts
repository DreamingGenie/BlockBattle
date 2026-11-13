import {
  BOARD_HEIGHT,
  BOARD_WIDTH,
  SPAWN_X,
  SPAWN_Y,
  cellAt,
  cellsOf,
  clearLines,
  fits,
  place,
  tryRotate,
  type Board,
  type PieceType,
  type Placement,
  type Rotation,
} from '../engine';
import type { AiInput, Difficulty } from './types';

/**
 * FE 규칙 AI(GR-09). AI 서버가 늦거나 실패한 수만 대신 둔다(FR-AI-02).
 * 평가 = −0.51 × 열 높이 합 + 0.76 × 지운 줄 수 − 0.36 × 구멍 수 − 0.18 × 울퉁불퉁함
 */
export const MISTAKE_RATE: Record<Difficulty, number> = { EASY: 0.3, NORMAL: 0.1, HARD: 0 };

export interface Candidate {
  rotation: Rotation;
  x: number;
  y: number;
  inputs: AiInput[];
  linesCleared: number;
  score: number;
}

export function evaluateBoard(board: Board, linesCleared: number): number {
  const heights: number[] = [];
  let holes = 0;
  for (let c = 0; c < BOARD_WIDTH; c++) {
    let top = BOARD_HEIGHT;
    for (let r = 0; r < BOARD_HEIGHT; r++) {
      if (cellAt(board, r, c) !== '0') {
        top = r;
        break;
      }
    }
    heights.push(BOARD_HEIGHT - top);
    for (let r = top + 1; r < BOARD_HEIGHT; r++) if (cellAt(board, r, c) === '0') holes++;
  }
  let bumpiness = 0;
  for (let c = 0; c < BOARD_WIDTH - 1; c++) bumpiness += Math.abs(heights[c]! - heights[c + 1]!);
  const heightSum = heights.reduce((a, b) => a + b, 0);
  return -0.51 * heightSum + 0.76 * linesCleared - 0.36 * holes - 0.18 * bumpiness;
}

const ROTATION_INPUTS: Record<Rotation, AiInput[]> = { 0: [], 1: ['CW'], 2: ['CW', 'CW'], 3: ['CCW'] };

/**
 * 생성 자리에서 회전한 뒤 좌우로만 옮겨 닿을 수 있는 모든 회전·열을 하드 드롭해 평가한다.
 * 점수 높은 순, 같으면 회전·열이 작은 순. 같은 칸에 놓이는 수(O의 회전 등)는 하나만 남긴다.
 */
export function ruleAiCandidates(board: Board, piece: PieceType): Candidate[] {
  const spawn: Placement = { piece, rotation: 0, x: SPAWN_X, y: SPAWN_Y };
  if (!fits(board, spawn)) return [];
  const seen = new Set<string>();
  const result: Candidate[] = [];
  for (const rotation of [0, 1, 2, 3] as Rotation[]) {
    let rotated: Placement | null = spawn;
    for (const input of ROTATION_INPUTS[rotation]) {
      rotated = rotated && tryRotate(board, rotated, input === 'CW' ? 'CW' : 'CCW');
    }
    if (!rotated) continue;
    for (let targetX = -3; targetX < BOARD_WIDTH; targetX++) {
      const inputs: AiInput[] = [...ROTATION_INPUTS[rotation]];
      let p: Placement = rotated;
      let reachable = true;
      while (p.x !== targetX) {
        const step = p.x < targetX ? 1 : -1;
        const next = { ...p, x: p.x + step };
        if (!fits(board, next)) {
          reachable = false;
          break;
        }
        p = next;
        inputs.push(step > 0 ? 'RIGHT' : 'LEFT');
      }
      if (!reachable) continue;
      while (fits(board, { ...p, y: p.y + 1 })) p = { ...p, y: p.y + 1 };
      const key = cellsOf(p.piece, p.rotation, p.x, p.y).map(([r, c]) => r * BOARD_WIDTH + c).sort((a, b) => a - b).join(',');
      if (seen.has(key)) continue;
      seen.add(key);
      const { board: after, cleared } = clearLines(place(board, p));
      result.push({ rotation: p.rotation, x: p.x, y: p.y, inputs: [...inputs, 'HARD_DROP'], linesCleared: cleared,
        score: evaluateBoard(after, cleared) });
    }
  }
  return result.sort((a, b) => b.score - a.score || a.rotation - b.rotation || a.x - b.x);
}

/** EASY 30%·NORMAL 10% 확률로 두 번째로 좋은 수를 둔다. HARD는 실수 없음. rand는 [0, 1) */
export function chooseRuleAiMove(board: Board, piece: PieceType, difficulty: Difficulty, rand: () => number): Candidate | null {
  const candidates = ruleAiCandidates(board, piece);
  if (candidates.length === 0) return null;
  const mistake = candidates.length > 1 && rand() < MISTAKE_RATE[difficulty];
  return candidates[mistake ? 1 : 0]!;
}
