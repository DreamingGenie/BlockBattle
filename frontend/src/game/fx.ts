/**
 * 게임 연출 상태(REQ-023). 엔진 이벤트를 받아 섬광·파편·잔상·흔들림·문구를 시간(ms)에 따라 계산한다.
 * 엔진 상태는 읽기만 하고 바꾸지 않는다. 시계·DOM에 의존하지 않아 시험할 수 있다(시각은 인자로 받는다).
 * 좌표는 보드 칸 단위(열 c, 보이는 행 r = 절대 행 − HIDDEN_ROWS)이고, 그리는 쪽에서 픽셀로 바꾼다.
 */
import { BOARD_HEIGHT, BOARD_WIDTH, HIDDEN_ROWS, cellsOf, place, type Board, type LockResult, type Placement } from '../engine';

/** 파편 상한. 넘으면 오래된 것부터 버린다(NFR-06 프레임 보호) */
export const MAX_PARTICLES = 240;
const GRAVITY = 38; // 칸/초²

export const DURATION = {
  lockFlash: 140,
  trail: 200,
  rowFlash: 280,
  popup: 1200,
  garbageBlink: 700,
  dead: 700,
} as const;

export interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  born: number;
  life: number;
  color: string;
  size: number;
}

export interface Popup {
  title: string | null;
  lines: string[];
  sent: number;
  /** 강조할 만한 삭제(QUAD·T-스핀·퍼펙트 클리어) */
  strong: boolean;
  born: number;
}

export interface Shake {
  born: number;
  duration: number;
  amplitude: number;
  /** down: 하드 드롭, up: 방해 줄 상승, both: 큰 삭제 */
  kind: 'down' | 'up' | 'both';
}

export interface FxState {
  particles: Particle[];
  rowFlashes: { row: number; born: number; strong: boolean }[];
  lockFlash: { cells: [number, number][]; born: number } | null;
  trails: { col: number; from: number; to: number; color: string; born: number }[];
  popup: Popup | null;
  shake: Shake | null;
  garbageBlink: number | null;
  deadAt: number | null;
}

export function createFx(): FxState {
  return { particles: [], rowFlashes: [], lockFlash: null, trails: [], popup: null, shake: null, garbageBlink: null, deadAt: null };
}

/** 고정 직전 보드에 블록을 놓았을 때 꽉 차는 행(절대 행 번호, 위→아래) */
export function clearedRows(boardBefore: Board, placement: Placement): number[] {
  const placed = place(boardBefore, placement);
  const rows: number[] = [];
  for (let r = 0; r < BOARD_HEIGHT; r++) {
    let full = true;
    for (let c = 0; c < BOARD_WIDTH; c++) {
      if (placed[r * BOARD_WIDTH + c] === '0') {
        full = false;
        break;
      }
    }
    if (full) rows.push(r);
  }
  return rows;
}

const LINE_NAMES = ['', 'SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD'];

/** 보드 옆에 띄울 문구. 4줄은 상표 주의에 맞춰 QUAD로 쓴다. 콤보는 연속 삭제 두 번째부터(combo ≥ 1) */
export function clearLabel(result: Pick<LockResult, 'linesCleared' | 'tSpin' | 'backToBack' | 'perfectClear' | 'sent'>, combo: number): Popup | null {
  const n = result.linesCleared;
  let title: string | null = null;
  if (result.tSpin) title = n > 0 ? `T-SPIN ${LINE_NAMES[n]}` : 'T-SPIN';
  else if (n > 0) title = LINE_NAMES[n] ?? null;
  const lines: string[] = [];
  if (result.backToBack) lines.push('B2B');
  if (combo >= 1 && n > 0) lines.push(`${combo} COMBO`);
  if (result.perfectClear) lines.unshift('PERFECT CLEAR');
  if (!title && lines.length === 0) return null;
  return { title, lines, sent: result.sent, strong: n >= 4 || (result.tSpin && n > 0) || result.perfectClear, born: 0 };
}

/** 보이는 맨 위 4행에 쌓인 칸이 있으면 위험(REQ-023) */
export function inDanger(board: Board): boolean {
  for (let r = HIDDEN_ROWS; r < HIDDEN_ROWS + 4; r++) {
    for (let c = 0; c < BOARD_WIDTH; c++) if (board[r * BOARD_WIDTH + c] !== '0') return true;
  }
  return false;
}

function pushParticle(fx: FxState, p: Particle) {
  fx.particles.push(p);
  if (fx.particles.length > MAX_PARTICLES) fx.particles.splice(0, fx.particles.length - MAX_PARTICLES);
}

function startShake(fx: FxState, shake: Shake) {
  // 진행 중인 흔들림보다 약하면 덮지 않는다
  if (fx.shake && fx.shake.amplitude > shake.amplitude && shake.born - fx.shake.born < fx.shake.duration) return;
  fx.shake = shake;
}

export interface LockFxInput {
  placement: Placement;
  hardDropCells: number;
  result: LockResult;
  /** 고정 직전(직전 프레임에 그린) 보드 */
  boardBefore: Board;
  /** 고정 뒤 콤보(Game.combo) */
  combo: number;
  colors: Record<string, string>;
}

/** 블록 고정 한 번의 연출을 시작한다. 반환값은 지운 줄(절대 행) — 효과음 세기에 쓴다 */
export function onLock(fx: FxState, input: LockFxInput, now: number, rand: () => number = Math.random): number[] {
  const { placement, result, colors } = input;
  const color = colors[placement.piece] ?? '#fff';
  const cells = cellsOf(placement.piece, placement.rotation, placement.x, placement.y);
  fx.lockFlash = { cells: cells.filter(([r]) => r >= HIDDEN_ROWS).map(([r, c]) => [r - HIDDEN_ROWS, c]), born: now };

  if (input.hardDropCells > 0) {
    const tops = new Map<number, number>();
    for (const [r, c] of cells) tops.set(c, Math.min(tops.get(c) ?? r, r));
    for (const [c, top] of tops) {
      const to = top - HIDDEN_ROWS;
      const from = Math.max(0, to - input.hardDropCells);
      if (to > from) fx.trails.push({ col: c, from, to, color, born: now });
    }
    startShake(fx, { born: now, duration: 150, amplitude: 2 + Math.min(input.hardDropCells, 18) * 0.18, kind: 'down' });
  }

  const rows = result.linesCleared > 0 ? clearedRows(input.boardBefore, placement) : [];
  const label = clearLabel(result, input.combo);
  const strong = label?.strong ?? false;
  if (rows.length) {
    const placed = place(input.boardBefore, placement);
    const perCell = strong ? 3 : rows.length >= 2 ? 2 : 1;
    for (const r of rows) {
      const vr = r - HIDDEN_ROWS;
      fx.rowFlashes.push({ row: vr, born: now, strong });
      for (let c = 0; c < BOARD_WIDTH; c++) {
        const cellColor = colors[placed[r * BOARD_WIDTH + c]!] ?? color;
        for (let i = 0; i < perCell; i++) {
          const angle = rand() * Math.PI * 2;
          const speed = 3 + rand() * (strong ? 12 : 7);
          pushParticle(fx, {
            x: c + 0.5,
            y: vr + 0.5,
            vx: Math.cos(angle) * speed,
            vy: Math.sin(angle) * speed - 4,
            born: now,
            life: 450 + rand() * 450,
            color: cellColor,
            size: 0.12 + rand() * 0.16,
          });
        }
      }
    }
    if (strong || rows.length >= 3) startShake(fx, { born: now, duration: 280, amplitude: strong ? 7 : 4, kind: 'both' });
  }
  if (label) fx.popup = { ...label, born: now };

  if (result.garbageRaised > 0) {
    startShake(fx, { born: now, duration: 220, amplitude: 3 + Math.min(result.garbageRaised, 10) * 0.5, kind: 'up' });
    fx.garbageBlink = now;
  }
  return rows;
}

/** 방해 줄이 대기열에 들어왔다(받은 공격) */
export function onGarbageQueued(fx: FxState, now: number) {
  fx.garbageBlink = now;
}

export function onTopout(fx: FxState, now: number) {
  if (fx.deadAt === null) fx.deadAt = now;
}

/** 배열을 새로 만들지 않고 남길 원소만 앞으로 당긴다(매 프레임 할당을 줄여 GC 멈춤을 피한다) */
function keepInPlace<T>(list: T[], keep: (item: T) => boolean) {
  let n = 0;
  for (let i = 0; i < list.length; i++) if (keep(list[i]!)) list[n++] = list[i]!;
  list.length = n;
}

/** 시간을 dt(ms)만큼 흘린다. 수명이 끝난 연출은 지운다 */
export function stepFx(fx: FxState, now: number, dt: number) {
  const s = Math.min(dt, 50) / 1000;
  keepInPlace(fx.particles, (p) => now - p.born < p.life);
  for (const p of fx.particles) {
    p.vy += GRAVITY * s;
    p.x += p.vx * s;
    p.y += p.vy * s;
  }
  if (fx.rowFlashes.length) keepInPlace(fx.rowFlashes, (f) => now - f.born < DURATION.rowFlash);
  if (fx.trails.length) keepInPlace(fx.trails, (t) => now - t.born < DURATION.trail);
  if (fx.lockFlash && now - fx.lockFlash.born >= DURATION.lockFlash) fx.lockFlash = null;
  if (fx.popup && now - fx.popup.born >= DURATION.popup) fx.popup = null;
  if (fx.shake && now - fx.shake.born >= fx.shake.duration) fx.shake = null;
  if (fx.garbageBlink !== null && now - fx.garbageBlink >= DURATION.garbageBlink) fx.garbageBlink = null;
}

/** 지금 흔들림 위치(px). 끝날수록 줄어든다 */
export function shakeOffset(fx: FxState, now: number, rand: () => number = Math.random): { x: number; y: number } {
  const sh = fx.shake;
  if (!sh) return { x: 0, y: 0 };
  const t = (now - sh.born) / sh.duration;
  if (t >= 1 || t < 0) return { x: 0, y: 0 };
  const a = sh.amplitude * (1 - t) * (1 - t);
  if (sh.kind === 'down') return { x: 0, y: a * Math.cos(t * Math.PI * 3) };
  if (sh.kind === 'up') return { x: 0, y: -a * Math.cos(t * Math.PI * 3) };
  return { x: (rand() * 2 - 1) * a, y: (rand() * 2 - 1) * a };
}
