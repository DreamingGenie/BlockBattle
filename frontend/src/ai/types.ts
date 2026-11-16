import type { PieceType } from '../engine';

export type Difficulty = 'EASY' | 'NORMAL' | 'HARD' | 'IMPOSSIBLE';
export type AiInput = 'LEFT' | 'RIGHT' | 'CW' | 'CCW' | 'SOFT_DROP' | 'HARD_DROP' | 'HOLD';
export const AI_INPUTS: readonly AiInput[] = ['LEFT', 'RIGHT', 'CW', 'CCW', 'SOFT_DROP', 'HARD_DROP', 'HOLD'];

/** FE 입력 재생 간격(FR-AI-01·GR-09). T-35: 난이도를 한 단계씩 쉽게 옮겼다(옛 EASY=NORMAL, 옛 NORMAL=HARD, 옛 HARD=IMPOSSIBLE) */
export const REPLAY_INTERVAL_MS: Record<Difficulty, number> = { EASY: 300, NORMAL: 300, HARD: 150, IMPOSSIBLE: 60 };

/** 블록이 나온 뒤 고정까지 걸리는 최소 시간(T-35). 사람이 따라갈 수 있게 AI가 바로바로 놓지 않게 한다. 0이면 제한 없음 */
export const MIN_PIECE_MS: Record<Difficulty, number> = { EASY: 2000, NORMAL: 1500, HARD: 1000, IMPOSSIBLE: 0 };

/** AI 서버 응답 대기 한도(FR-AI-02) */
export const SERVER_TIMEOUT_MS = 150;

/** API 명세 4.1 요청 본문 */
export interface MoveRequestBody {
  difficulty: Difficulty;
  board: number[][];
  current: PieceType;
  hold: PieceType | null;
  canHold: boolean;
  next: PieceType[];
  pendingGarbage: number;
}

/** API 명세 4.1 응답 data */
export interface ServerMove {
  useHold: boolean;
  piece: PieceType;
  rotation: number;
  x: number;
  inputs: AiInput[];
  tSpin: boolean;
  elapsedMs: number;
}

export type FallbackReason = 'timeout' | 'network' | 'http' | 'invalid';

export type MoveOutcome = { source: 'server'; move: ServerMove } | { source: 'fallback'; reason: FallbackReason };
