/**
 * 보드 그리기(Canvas 2D, T-19). 엔진 상태를 읽기만 한다.
 * 보이는 영역은 10×20(맨 위 숨김 2행 제외, GR-01). 왼쪽 홀드, 가운데 보드, 오른쪽 다음 5개.
 */
import { BOARD_HEIGHT, BOARD_WIDTH, HIDDEN_ROWS, cellsOf, fits, type Board, type PieceType, type Placement } from '../engine';

export const COLORS: Record<string, string> = {
  I: '#4dd0e1',
  O: '#ffd54f',
  T: '#ba68c8',
  S: '#81c784',
  Z: '#e57373',
  J: '#64b5f6',
  L: '#ffb74d',
  G: '#7d84a8',
};

export interface BoardScene {
  board: Board;
  current: Placement | null;
  hold?: PieceType | null;
  holdUsed?: boolean;
  next?: PieceType[];
  pendingLines?: number;
  /** 상대 보드처럼 작게 그릴 때 떨어질 자리 미리 보기를 뺀다 */
  ghost?: boolean;
}

export const CELL = 28;
const SIDE = 5 * CELL; // 홀드·다음 블록 칸 너비

export function sceneSize(cell = CELL, withSides = true) {
  const side = withSides ? SIDE * (cell / CELL) : 0;
  return { width: side * 2 + BOARD_WIDTH * cell + (withSides ? 16 : 0), height: (BOARD_HEIGHT - HIDDEN_ROWS) * cell };
}

function drawCell(ctx: CanvasRenderingContext2D, x: number, y: number, size: number, color: string, alpha = 1) {
  ctx.globalAlpha = alpha;
  ctx.fillStyle = color;
  ctx.fillRect(x + 1, y + 1, size - 2, size - 2);
  ctx.globalAlpha = alpha * 0.35;
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(x + 1, y + 1, size - 2, Math.max(2, size * 0.15));
  ctx.globalAlpha = 1;
}

function drawMini(ctx: CanvasRenderingContext2D, piece: PieceType, x: number, y: number, size: number, alpha = 1) {
  const cells = cellsOf(piece, 0, 0, 0);
  const cols = cells.map(([, c]) => c);
  const rows = cells.map(([r]) => r);
  const w = Math.max(...cols) - Math.min(...cols) + 1;
  const h = Math.max(...rows) - Math.min(...rows) + 1;
  const ox = x + (4 - w) * size * 0.5 - Math.min(...cols) * size;
  const oy = y + (2 - h) * size * 0.5 - Math.min(...rows) * size;
  for (const [r, c] of cells) drawCell(ctx, ox + c * size, oy + r * size, size, COLORS[piece]!, alpha);
}

export function drawScene(ctx: CanvasRenderingContext2D, scene: BoardScene, cell = CELL, withSides = true) {
  const { width, height } = sceneSize(cell, withSides);
  const side = withSides ? SIDE * (cell / CELL) : 0;
  const bx = withSides ? side + 8 : 0;
  ctx.clearRect(0, 0, width, height);

  // 보드 바탕과 격자
  ctx.fillStyle = '#0e0e10';
  ctx.fillRect(bx, 0, BOARD_WIDTH * cell, height);
  ctx.strokeStyle = '#222326';
  ctx.lineWidth = 1;
  for (let c = 1; c < BOARD_WIDTH; c++) {
    ctx.beginPath();
    ctx.moveTo(bx + c * cell + 0.5, 0);
    ctx.lineTo(bx + c * cell + 0.5, height);
    ctx.stroke();
  }
  for (let r = 1; r < BOARD_HEIGHT - HIDDEN_ROWS; r++) {
    ctx.beginPath();
    ctx.moveTo(bx, r * cell + 0.5);
    ctx.lineTo(bx + BOARD_WIDTH * cell, r * cell + 0.5);
    ctx.stroke();
  }

  // 쌓인 칸
  for (let r = HIDDEN_ROWS; r < BOARD_HEIGHT; r++) {
    for (let c = 0; c < BOARD_WIDTH; c++) {
      const v = scene.board[r * BOARD_WIDTH + c];
      if (v && v !== '0') drawCell(ctx, bx + c * cell, (r - HIDDEN_ROWS) * cell, cell, COLORS[v] ?? '#999');
    }
  }

  // 떨어질 자리와 현재 블록
  const cur = scene.current;
  if (cur) {
    if (scene.ghost !== false) {
      let ghost = cur;
      while (fits(scene.board, { ...ghost, y: ghost.y + 1 })) ghost = { ...ghost, y: ghost.y + 1 };
      for (const [r, c] of cellsOf(ghost.piece, ghost.rotation, ghost.x, ghost.y)) {
        if (r >= HIDDEN_ROWS) drawCell(ctx, bx + c * cell, (r - HIDDEN_ROWS) * cell, cell, COLORS[cur.piece]!, 0.22);
      }
    }
    for (const [r, c] of cellsOf(cur.piece, cur.rotation, cur.x, cur.y)) {
      if (r >= HIDDEN_ROWS) drawCell(ctx, bx + c * cell, (r - HIDDEN_ROWS) * cell, cell, COLORS[cur.piece]!);
    }
  }

  // 대기 방해 줄 막대(보드 왼쪽 가장자리)
  const pending = Math.min(scene.pendingLines ?? 0, BOARD_HEIGHT - HIDDEN_ROWS);
  if (pending > 0) {
    ctx.fillStyle = '#e5534b';
    ctx.fillRect(bx - 5, height - pending * cell, 4, pending * cell);
  }

  if (!withSides) return;
  const mini = cell * 0.7;
  ctx.fillStyle = '#9b9ca1';
  ctx.font = '13px sans-serif';
  ctx.fillText('HOLD', 4, 16);
  if (scene.hold) drawMini(ctx, scene.hold, 4, 28, mini, scene.holdUsed ? 0.35 : 1);
  const nx = bx + BOARD_WIDTH * cell + 12;
  ctx.fillText('NEXT', nx, 16);
  (scene.next ?? []).slice(0, 5).forEach((piece, i) => drawMini(ctx, piece, nx, 28 + i * mini * 2.8, mini));
}
