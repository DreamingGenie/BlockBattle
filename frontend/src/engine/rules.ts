import { levelForLines } from './constants';
import { clearLines, isBlocked, isEmpty, place, raiseGarbage } from './board';
import type { Board, GarbageBundle, LastAction, Placement } from './types';

/** lock 직전 플레이어 상태. combo는 직전 연속 삭제 수(끊긴 상태 = -1) */
export interface LockState {
  board: Board;
  linesTotal: number;
  combo: number;
  /** 직전 줄 삭제가 어려운 삭제(테트리스·줄 지운 T-스핀)였는지 */
  b2b: boolean;
  pending: readonly GarbageBundle[];
}

export interface LockInput {
  placement: Placement;
  lastAction: LastAction;
  /** 이번 블록에서 소프트 드롭으로 내린 칸 수, 하드 드롭으로 내린 칸 수(GR-07 ④) */
  softDropCells?: number;
  hardDropCells?: number;
}

export interface LockResult {
  board: Board;
  linesCleared: number;
  tSpin: boolean;
  perfectClear: boolean;
  /** 이번 고정이 B2B 조건(직전·이번 모두 어려운 삭제)을 만족했는지 */
  backToBack: boolean;
  /** 이번 고정으로 얻은 점수 */
  score: number;
  /** 줄 삭제 직전 레벨(점수 계산에 쓴 레벨) */
  level: number;
  /** 상쇄 전 공격 줄 수 */
  attack: number;
  /** 내 대기 방해 줄에서 상쇄한 줄 수 */
  cancelled: number;
  /** 상쇄 뒤 상대에게 보내는 줄 수 */
  sent: number;
  /** 이번 고정으로 보드에 올라온 방해 줄 수 */
  garbageRaised: number;
  state: LockState;
}

const LINE_SCORE = [0, 100, 300, 500, 800];
const TSPIN_SCORE = [400, 800, 1200, 1600];
const LINE_ATTACK = [0, 0, 1, 2, 4];
const TSPIN_ATTACK = [0, 2, 4, 6];
const PERFECT_CLEAR_ATTACK = 10;

/**
 * T-스핀(GR-05): T 블록, 마지막 성공 동작이 회전, T 중심 기준 대각선 네 칸 중 3칸 이상이 막힘(벽·바닥 포함).
 * 미니 T-스핀은 구분하지 않는다. board는 블록을 놓기 전 보드다.
 */
export function isTSpin(board: Board, p: Placement, lastAction: LastAction): boolean {
  if (p.piece !== 'T' || lastAction !== 'rotate') return false;
  const corners: [number, number][] = [
    [p.y, p.x],
    [p.y, p.x + 2],
    [p.y + 2, p.x],
    [p.y + 2, p.x + 2],
  ];
  return corners.filter(([r, c]) => isBlocked(board, r, c)).length >= 3;
}

/** 콤보 보너스 공격(GR-08): 1~2 +1, 3~4 +2, 5~6 +3, 7 이상 +4 */
export function comboAttack(combo: number): number {
  if (combo <= 0) return 0;
  return Math.min(4, Math.ceil(combo / 2));
}

/**
 * 블록 하나를 고정한 결과(GR-05~GR-08). 친구 대전 서버 재계산(BR-05)과 같은 입력·같은 결과를 내야 하는 단위다.
 * 위치 유효성(겹침·바로 아래 막힘)은 검사하지 않는다 — 호출자가 fits/isGrounded로 확인한다.
 */
export function resolveLock(state: LockState, input: LockInput): LockResult {
  const { placement, lastAction } = input;
  const tSpin = isTSpin(state.board, placement, lastAction);
  const { board: clearedBoard, cleared } = clearLines(place(state.board, placement));
  const level = levelForLines(state.linesTotal);

  const difficult = cleared > 0 && (cleared === 4 || tSpin);
  const backToBack = difficult && state.b2b;
  const combo = cleared > 0 ? state.combo + 1 : -1;
  const b2b = cleared > 0 ? difficult : state.b2b;
  const perfectClear = cleared > 0 && isEmpty(clearedBoard);

  // GR-07 점수: ① 기본 × 레벨 ② B2B × 1.5 ③ 콤보 50 × 콤보 × 레벨 ④ 드롭. 소수점은 끝에서 버린다.
  let base = (tSpin ? TSPIN_SCORE[cleared]! : LINE_SCORE[cleared]!) * level;
  if (backToBack) base *= 1.5;
  const comboScore = combo > 0 ? 50 * combo * level : 0;
  const dropScore = (input.softDropCells ?? 0) + 2 * (input.hardDropCells ?? 0);
  const score = Math.floor(base + comboScore + dropScore);

  // GR-08 공격과 상쇄
  let attack = 0;
  if (cleared > 0) {
    attack = (tSpin ? TSPIN_ATTACK[cleared]! : LINE_ATTACK[cleared]!) + (backToBack ? 1 : 0) + comboAttack(combo);
    if (perfectClear) attack += PERFECT_CLEAR_ATTACK;
  }
  const { remaining: pendingAfterCancel, cancelled } = cancelGarbage(state.pending, attack);
  const sent = attack - cancelled;

  // 줄을 못 지운 고정이면 대기 방해 줄이 전부 올라온다
  let board = clearedBoard;
  let pending = pendingAfterCancel;
  let garbageRaised = 0;
  if (cleared === 0 && pending.length > 0) {
    board = raiseGarbage(board, pending);
    garbageRaised = pending.reduce((sum, b) => sum + b.lines, 0);
    pending = [];
  }

  return {
    board,
    linesCleared: cleared,
    tSpin,
    perfectClear,
    backToBack,
    score,
    level,
    attack,
    cancelled,
    sent,
    garbageRaised,
    state: { board, linesTotal: state.linesTotal + cleared, combo, b2b, pending },
  };
}

/** 보낼 줄 수만큼 먼저 받은 묶음부터 상쇄한다 */
export function cancelGarbage(
  pending: readonly GarbageBundle[],
  attack: number,
): { remaining: GarbageBundle[]; cancelled: number } {
  let left = attack;
  const remaining: GarbageBundle[] = [];
  for (const bundle of pending) {
    if (left >= bundle.lines) {
      left -= bundle.lines;
    } else {
      remaining.push({ ...bundle, lines: bundle.lines - left });
      left = 0;
    }
  }
  return { remaining, cancelled: attack - left };
}
