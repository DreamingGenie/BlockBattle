import { BOARD_HEIGHT, BOARD_WIDTH } from './constants';
import { cellsOf } from './pieces';
import type { Board, Cell, GarbageBundle, Placement } from './types';

const BOARD_PATTERN = /^[0IOTSZJLG]{220}$/;

export function emptyBoard(): Board {
  return new Array<Cell>(BOARD_WIDTH * BOARD_HEIGHT).fill('0');
}

/** 220자 board 문자열(CON-001)을 보드로 바꾼다 */
export function boardFromString(value: string): Board {
  if (!BOARD_PATTERN.test(value)) throw new Error('board는 [0IOTSZJLG] 220자여야 한다');
  return value.split('') as Cell[];
}

export function boardToString(board: Board): string {
  return board.join('');
}

/** 아래 행부터 채운 보드. rows는 위→아래 순서, 마지막 원소가 맨 아래 행 */
export function boardFromBottomRows(rows: readonly string[]): Board {
  const padded = [...new Array<string>(BOARD_HEIGHT - rows.length).fill('0'.repeat(BOARD_WIDTH)), ...rows];
  return boardFromString(padded.join(''));
}

export function cellAt(board: Board, row: number, col: number): Cell | null {
  if (row < 0 || row >= BOARD_HEIGHT || col < 0 || col >= BOARD_WIDTH) return null;
  return board[row * BOARD_WIDTH + col]!;
}

/** 벽·바닥·천장 밖이거나 채워진 칸이면 막힘 */
export function isBlocked(board: Board, row: number, col: number): boolean {
  const cell = cellAt(board, row, col);
  return cell === null || cell !== '0';
}

export function fits(board: Board, p: Placement): boolean {
  return cellsOf(p.piece, p.rotation, p.x, p.y).every(([r, c]) => !isBlocked(board, r, c));
}

/** 바로 아래가 막혀 있는지(BR-05 ③과 고정 지연에 쓴다) */
export function isGrounded(board: Board, p: Placement): boolean {
  return !fits(board, { ...p, y: p.y + 1 });
}

export function place(board: Board, p: Placement): Board {
  const next = [...board];
  for (const [r, c] of cellsOf(p.piece, p.rotation, p.x, p.y)) {
    next[r * BOARD_WIDTH + c] = p.piece;
  }
  return next;
}

export function clearLines(board: Board): { board: Board; cleared: number } {
  const kept: Cell[][] = [];
  for (let r = 0; r < BOARD_HEIGHT; r++) {
    const row = board.slice(r * BOARD_WIDTH, (r + 1) * BOARD_WIDTH);
    if (row.some((cell) => cell === '0')) kept.push(row);
  }
  const cleared = BOARD_HEIGHT - kept.length;
  const empty = new Array<Cell[]>(cleared).fill([]).map(() => new Array<Cell>(BOARD_WIDTH).fill('0'));
  return { board: [...empty, ...kept].flat(), cleared };
}

export function isEmpty(board: Board): boolean {
  return board.every((cell) => cell === '0');
}

/**
 * 방해 줄을 아래에서 올린다(GR-08). 묶음 순서대로 올리므로 먼저 받은 묶음이 위에 놓인다.
 * 천장 위로 밀려난 칸은 사라진다(다음 생성이 막히면 topout).
 */
export function raiseGarbage(board: Board, bundles: readonly GarbageBundle[]): Board {
  let current = board;
  for (const bundle of bundles) {
    const row = (hole: number): Cell[] =>
      Array.from({ length: BOARD_WIDTH }, (_, c) => (c === hole ? '0' : 'G'));
    const added = Array.from({ length: bundle.lines }, () => row(bundle.holeColumn)).flat();
    current = [...current.slice(bundle.lines * BOARD_WIDTH), ...added];
  }
  return current;
}
