import type { PieceType } from '../engine';

export type Difficulty = 'EASY' | 'NORMAL' | 'HARD';
export type AiInput = 'LEFT' | 'RIGHT' | 'CW' | 'CCW' | 'SOFT_DROP' | 'HARD_DROP' | 'HOLD';
export const AI_INPUTS: readonly AiInput[] = ['LEFT', 'RIGHT', 'CW', 'CCW', 'SOFT_DROP', 'HARD_DROP', 'HOLD'];

/** FE 입력 재생 간격(FR-AI-01·GR-09) */
export const REPLAY_INTERVAL_MS: Record<Difficulty, number> = { EASY: 300, NORMAL: 150, HARD: 60 };

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
