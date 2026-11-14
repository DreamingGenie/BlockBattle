/**
 * 보드 그리기(Canvas 2D, T-19 · 연출 T-28). 엔진 상태와 연출 상태를 읽기만 한다.
 * 보이는 영역은 10×20(맨 위 숨김 2행 제외, GR-01). 왼쪽 홀드·문구, 가운데 보드, 오른쪽 다음 5개.
 */
import { BOARD_HEIGHT, BOARD_WIDTH, HIDDEN_ROWS, cellsOf, fits, type Board, type PieceType, type Placement } from '../engine';
import { DURATION, inDanger, type FxState } from './fx';

export const COLORS: Record<string, string> = {
  I: '#3ec5dc',
  O: '#f2c53d',
  T: '#b25fd1',
  S: '#5cc46a',
  Z: '#e5534b',
  J: '#4a86e8',
  L: '#f08a3c',
  G: '#6b6e78',
};

const DEAD_COLOR = '#3d3f45';
const ACCENT = '#f0b429';
const DANGER = '#e5534b';

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
const VISIBLE = BOARD_HEIGHT - HIDDEN_ROWS;

export function sceneSize(cell = CELL, withSides = true) {
  const side = withSides ? SIDE * (cell / CELL) : 0;
  return { width: side * 2 + BOARD_WIDTH * cell + (withSides ? 16 : 0), height: VISIBLE * cell };
}

/** 칸 하나: 바탕색 + 위쪽 밝은 띠 + 아래쪽 어두운 띠로 입체감을 준다 */
function drawCell(ctx: CanvasRenderingContext2D, x: number, y: number, size: number, color: string, alpha = 1) {
  const edge = Math.max(2, Math.round(size * 0.14));
  ctx.globalAlpha = alpha;
  ctx.fillStyle = color;
  ctx.fillRect(x + 1, y + 1, size - 2, size - 2);
  ctx.globalAlpha = alpha * 0.32;
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(x + 1, y + 1, size - 2, edge);
  ctx.globalAlpha = alpha * 0.28;
  ctx.fillStyle = '#000000';
  ctx.fillRect(x + 1, y + size - 1 - edge, size - 2, edge);
  ctx.globalAlpha = 1;
}

function drawGhost(ctx: CanvasRenderingContext2D, x: number, y: number, size: number, color: string) {
  ctx.globalAlpha = 0.12;
  ctx.fillStyle = color;
  ctx.fillRect(x + 1, y + 1, size - 2, size - 2);
  ctx.globalAlpha = 0.6;
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.5;
  ctx.strokeRect(x + 2, y + 2, size - 4, size - 4);
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

/** 게임 오버 뒤 아래 행부터 회색으로 바뀌는 진행도(0~1) */
function deadProgress(fx: FxState | undefined, now: number): number {
  if (!fx || fx.deadAt === null) return 0;
  return Math.min(1, (now - fx.deadAt) / DURATION.dead);
}

export function drawScene(ctx: CanvasRenderingContext2D, scene: BoardScene, cell = CELL, withSides = true, fx?: FxState, now = 0) {
  const { width, height } = sceneSize(cell, withSides);
  const side = withSides ? SIDE * (cell / CELL) : 0;
  const bx = withSides ? side + 8 : 0;
  const bw = BOARD_WIDTH * cell;
  ctx.clearRect(0, 0, width, height);

  // 보드 바탕과 격자
  ctx.fillStyle = '#0b0b0d';
  ctx.fillRect(bx, 0, bw, height);
  ctx.strokeStyle = '#1c1d21';
  ctx.lineWidth = 1;
  ctx.beginPath();
  for (let c = 1; c < BOARD_WIDTH; c++) {
    ctx.moveTo(bx + c * cell + 0.5, 0);
    ctx.lineTo(bx + c * cell + 0.5, height);
  }
  for (let r = 1; r < VISIBLE; r++) {
    ctx.moveTo(bx, r * cell + 0.5);
    ctx.lineTo(bx + bw, r * cell + 0.5);
  }
  ctx.stroke();

  // 쌓인 칸. 게임 오버면 아래 행부터 회색으로
  const dead = deadProgress(fx, now);
  for (let r = HIDDEN_ROWS; r < BOARD_HEIGHT; r++) {
    const vr = r - HIDDEN_ROWS;
    const gray = dead > 0 && (VISIBLE - 1 - vr) / VISIBLE < dead;
    for (let c = 0; c < BOARD_WIDTH; c++) {
      const v = scene.board[r * BOARD_WIDTH + c];
      if (v && v !== '0') drawCell(ctx, bx + c * cell, vr * cell, cell, gray ? DEAD_COLOR : (COLORS[v] ?? '#999'));
    }
  }

  // 떨어질 자리와 현재 블록
  const cur = dead > 0 ? null : scene.current;
  if (cur) {
    const color = COLORS[cur.piece]!;
    if (scene.ghost !== false) {
      let ghost = cur;
      while (fits(scene.board, { ...ghost, y: ghost.y + 1 })) ghost = { ...ghost, y: ghost.y + 1 };
      for (const [r, c] of cellsOf(ghost.piece, ghost.rotation, ghost.x, ghost.y)) {
        if (r >= HIDDEN_ROWS) drawGhost(ctx, bx + c * cell, (r - HIDDEN_ROWS) * cell, cell, color);
      }
    }
    for (const [r, c] of cellsOf(cur.piece, cur.rotation, cur.x, cur.y)) {
      if (r >= HIDDEN_ROWS) drawCell(ctx, bx + c * cell, (r - HIDDEN_ROWS) * cell, cell, color);
    }
  }

  if (fx) drawFx(ctx, fx, now, bx, cell, height);

  // 위험 높이: 보드 테두리를 붉게 깜박인다
  const danger = dead === 0 && inDanger(scene.board);
  ctx.lineWidth = 2;
  ctx.strokeStyle = danger ? DANGER : '#2f3035';
  ctx.globalAlpha = danger ? 0.55 + 0.45 * Math.sin(now / 110) : 1;
  ctx.strokeRect(bx + 1, 1, bw - 2, height - 2);
  ctx.globalAlpha = 1;

  // 대기 방해 줄 막대(보드 왼쪽 가장자리). 공격을 받은 직후에는 깜박인다
  const pending = Math.min(scene.pendingLines ?? 0, VISIBLE);
  if (pending > 0) {
    const blinking = fx?.garbageBlink != null && Math.floor((now - fx.garbageBlink) / 90) % 2 === 0;
    ctx.fillStyle = blinking ? '#ffffff' : DANGER;
    ctx.fillRect(bx - 6, height - pending * cell, 5, pending * cell);
  }

  if (!withSides) return;
  const mini = cell * 0.7;
  ctx.fillStyle = '#9b9ca1';
  ctx.font = `600 ${Math.round(12 * (cell / CELL))}px system-ui, sans-serif`;
  ctx.textAlign = 'left';
  ctx.fillText('HOLD', 4, 16);
  if (scene.hold) drawMini(ctx, scene.hold, 4, 28, mini, scene.holdUsed ? 0.35 : 1);
  const nx = bx + bw + 12;
  ctx.fillText('NEXT', nx, 16);
  (scene.next ?? []).slice(0, 5).forEach((piece, i) => drawMini(ctx, piece, nx, 28 + i * mini * 2.8, mini));
  if (fx?.popup) drawPopup(ctx, fx, now, side, cell);
}

function drawFx(ctx: CanvasRenderingContext2D, fx: FxState, now: number, bx: number, cell: number, height: number) {
  // 하드 드롭 잔상: 내려온 경로에 위로 갈수록 옅어지는 기둥
  for (const t of fx.trails) {
    const k = 1 - (now - t.born) / DURATION.trail;
    const y0 = t.from * cell;
    const y1 = t.to * cell;
    const grad = ctx.createLinearGradient(0, y0, 0, y1);
    grad.addColorStop(0, 'rgba(255,255,255,0)');
    grad.addColorStop(1, t.color);
    ctx.globalAlpha = 0.45 * k;
    ctx.fillStyle = grad;
    ctx.fillRect(bx + t.col * cell + cell * 0.15, y0, cell * 0.7, y1 - y0);
  }
  ctx.globalAlpha = 1;

  // 고정 섬광
  if (fx.lockFlash) {
    const k = 1 - (now - fx.lockFlash.born) / DURATION.lockFlash;
    ctx.globalAlpha = 0.65 * k;
    ctx.fillStyle = '#ffffff';
    for (const [r, c] of fx.lockFlash.cells) ctx.fillRect(bx + c * cell + 1, r * cell + 1, cell - 2, cell - 2);
    ctx.globalAlpha = 1;
  }

  // 지운 줄 섬광: 밝게 번쩍인 뒤 위아래로 얇아지며 사라진다
  for (const f of fx.rowFlashes) {
    const t = (now - f.born) / DURATION.rowFlash;
    const h = cell * (1 - t * 0.8);
    ctx.globalAlpha = (f.strong ? 1 : 0.85) * (1 - t);
    ctx.fillStyle = f.strong ? '#fff4c2' : '#ffffff';
    ctx.fillRect(bx, f.row * cell + (cell - h) / 2, BOARD_WIDTH * cell, h);
  }
  ctx.globalAlpha = 1;

  // 파편
  for (const p of fx.particles) {
    const k = 1 - (now - p.born) / p.life;
    const s = p.size * cell;
    const x = bx + p.x * cell - s / 2;
    const y = p.y * cell - s / 2;
    if (y > height || x < bx - cell * 2 || x > bx + BOARD_WIDTH * cell + cell * 2) continue;
    ctx.globalAlpha = Math.max(0, k);
    ctx.fillStyle = p.color;
    ctx.fillRect(x, y, s, s);
  }
  ctx.globalAlpha = 1;
}

/** 왼쪽 칸(홀드 아래)에 삭제 문구를 띄운다. 처음 0.12초는 크게 튀어나오고 마지막 30%에서 사라진다 */
function drawPopup(ctx: CanvasRenderingContext2D, fx: FxState, now: number, side: number, cell: number) {
  const p = fx.popup!;
  const t = (now - p.born) / DURATION.popup;
  if (t < 0 || t >= 1) return;
  const k = cell / CELL;
  const pop = t < 0.1 ? 1.35 - 3.5 * t : 1;
  ctx.save();
  ctx.globalAlpha = t > 0.7 ? (1 - t) / 0.3 : 1;
  ctx.textAlign = 'center';
  const cx = side / 2;
  let y = 150 * k;
  if (p.title) {
    let size = (p.strong ? 22 : 19) * k * pop;
    ctx.font = `800 ${size}px system-ui, sans-serif`;
    const w = ctx.measureText(p.title).width;
    if (w > side - 8) {
      size *= (side - 8) / w;
      ctx.font = `800 ${size}px system-ui, sans-serif`;
    }
    ctx.fillStyle = p.strong ? ACCENT : '#ececec';
    ctx.fillText(p.title, cx, y);
    y += size + 6 * k;
  }
  for (const line of p.lines) {
    const special = line === 'PERFECT CLEAR';
    ctx.font = `700 ${(special ? 15 : 14) * k}px system-ui, sans-serif`;
    ctx.fillStyle = special ? ACCENT : line === 'B2B' ? '#7fd1ff' : '#c9cad0';
    ctx.fillText(line, cx, y);
    y += 20 * k;
  }
  if (p.sent > 0) {
    ctx.font = `800 ${18 * k * pop}px system-ui, sans-serif`;
    ctx.fillStyle = DANGER;
    ctx.fillText(`+${p.sent}`, cx, y + 4 * k);
  }
  ctx.restore();
}
