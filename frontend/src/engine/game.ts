import { PieceSequence } from './bag';
import { boardFromString, boardToString, emptyBoard, fits, isGrounded } from './board';
import {
  LOCK_DELAY_MS,
  MAX_LOCK_RESETS,
  PREVIEW_COUNT,
  SOFT_DROP_MS,
  SPAWN_X,
  SPAWN_Y,
  gravityMs,
  levelForLines,
} from './constants';
import { tryRotate, type RotateDirection } from './movement';
import type { Seed } from './rng';
import { resolveLock, type LockResult, type LockState } from './rules';
import type { GarbageBundle, LastAction, PieceType, Placement } from './types';

/** 서버 검증(BR-05)에 보내는 lock 한 번의 정보와 엔진이 계산한 결과 */
export interface LockEvent {
  type: 'lock';
  seq: number;
  placement: Placement;
  /** 이 블록이 나오기 전에 홀드를 썼는지 */
  holdUsed: boolean;
  lastAction: LastAction;
  result: LockResult;
}

export interface TopoutEvent {
  type: 'topout';
}

export type GameEvent = LockEvent | TopoutEvent;

/**
 * 결정론적 게임 상태 기계(GR-01~GR-08). 시계·DOM에 의존하지 않고 시간은 tick(ms)로만 흐른다.
 * 같은 시드·같은 입력 순서·같은 tick이면 언제 어디서 돌려도 같은 상태가 된다.
 */
export class Game {
  private readonly sequence: PieceSequence;
  private nextIndex = 0;
  private lockState: LockState = { board: emptyBoard(), linesTotal: 0, combo: -1, b2b: false, pending: [] };
  private events: GameEvent[] = [];

  current: Placement | null = null;
  hold: PieceType | null = null;
  holdUsed = false;
  gameOver = false;
  seq = 0;

  score = 0;
  piecesPlaced = 0;
  maxCombo = 0;
  attackSent = 0;
  attackReceived = 0;

  private lastAction: LastAction = 'none';
  private gravityElapsed = 0;
  private lockTimer: number | null = null;
  private lockResets = 0;
  private softDropping = false;
  private softCells = 0;

  constructor(seed: Seed) {
    this.sequence = new PieceSequence(seed);
    this.spawn(this.sequence.at(this.nextIndex++));
  }

  get board() {
    return this.lockState.board;
  }

  get linesCleared() {
    return this.lockState.linesTotal;
  }

  get level() {
    return levelForLines(this.lockState.linesTotal);
  }

  get combo() {
    return this.lockState.combo;
  }

  get pending(): readonly GarbageBundle[] {
    return this.lockState.pending;
  }

  /** 다음 블록 5개(GR-04) */
  preview(): PieceType[] {
    return this.sequence.slice(this.nextIndex, PREVIEW_COUNT);
  }

  drainEvents(): GameEvent[] {
    const drained = this.events;
    this.events = [];
    return drained;
  }

  moveLeft(): boolean {
    return this.translate(-1);
  }

  moveRight(): boolean {
    return this.translate(1);
  }

  rotateCW(): boolean {
    return this.rotate('CW');
  }

  rotateCCW(): boolean {
    return this.rotate('CCW');
  }

  /** 소프트 드롭 한 칸(AI 입력 재생용, GR-07 칸당 1점). 내려갔으면 true */
  softDropStep(): boolean {
    if (!this.current || this.gameOver) return false;
    const moved = { ...this.current, y: this.current.y + 1 };
    if (!fits(this.board, moved)) return false;
    this.current = moved;
    this.lastAction = 'move';
    this.softCells++;
    return true;
  }

  setSoftDrop(on: boolean): void {
    this.softDropping = on;
    this.gravityElapsed = 0;
  }

  /** 하드 드롭: 바닥까지 내리고 즉시 고정(GR-04), 칸당 2점(GR-07) */
  hardDrop(): void {
    if (!this.current || this.gameOver) return;
    let distance = 0;
    while (fits(this.board, { ...this.current, y: this.current.y + 1 })) {
      this.current = { ...this.current, y: this.current.y + 1 };
      distance++;
    }
    if (distance > 0) this.lastAction = 'move';
    this.lockCurrent(distance);
  }

  /** 홀드: 블록 하나가 고정될 때까지 한 번만(GR-04) */
  holdPiece(): boolean {
    if (!this.current || this.gameOver || this.holdUsed) return false;
    const held = this.hold;
    this.hold = this.current.piece;
    this.holdUsed = true;
    this.spawn(held ?? this.sequence.at(this.nextIndex++));
    return true;
  }

  /** 상대가 보낸 공격 한 묶음을 대기열에 넣는다. 구멍 열은 호출자(서버·vs AI)가 정한다(GR-08) */
  receiveGarbage(bundle: GarbageBundle): void {
    this.lockState = { ...this.lockState, pending: [...this.lockState.pending, bundle] };
    this.attackReceived += bundle.lines;
  }

  /** 시간을 ms만큼 흘린다: 낙하(GR-03)와 고정 지연(GR-04) */
  tick(ms: number): void {
    let remaining = ms;
    while (remaining > 0 && this.current && !this.gameOver) {
      if (isGrounded(this.board, this.current)) {
        this.lockTimer ??= LOCK_DELAY_MS;
        const used = Math.min(remaining, this.lockTimer);
        this.lockTimer -= used;
        remaining -= used;
        if (this.lockTimer <= 0) this.lockCurrent(0);
      } else {
        this.lockTimer = null;
        const interval = this.softDropping ? Math.min(SOFT_DROP_MS, gravityMs(this.level)) : gravityMs(this.level);
        const needed = interval - this.gravityElapsed;
        if (remaining >= needed) {
          remaining -= needed;
          this.gravityElapsed = 0;
          this.current = { ...this.current, y: this.current.y + 1 };
          this.lastAction = 'move';
          if (this.softDropping) this.softCells++;
        } else {
          this.gravityElapsed += remaining;
          remaining = 0;
        }
      }
    }
  }

  /**
   * 서버 상태로 되돌린다(친구 대전 RESYNC, API 명세 5.7, T-22). 규칙은 바꾸지 않고 값만 맞춘다.
   * pieceIndex는 지금 놓을 블록의 시드 순번(서버 기준)이다. 누적 줄·콤보·B2B·점수는 RESYNC에 없어 그대로 둔다.
   */
  restore(state: {
    board: string;
    seq: number;
    pieceIndex: number;
    hold: PieceType | null;
    holdUsed: boolean;
    pending: readonly GarbageBundle[];
  }): void {
    this.lockState = { ...this.lockState, board: boardFromString(state.board), pending: [...state.pending] };
    this.seq = state.seq;
    this.hold = state.hold;
    this.holdUsed = state.holdUsed;
    this.gameOver = false;
    this.events = [];
    this.nextIndex = state.pieceIndex;
    this.spawn(this.sequence.at(this.nextIndex++));
  }

  snapshot() {
    return {
      board: boardToString(this.board),
      current: this.current,
      hold: this.hold,
      holdUsed: this.holdUsed,
      nextIndex: this.nextIndex,
      seq: this.seq,
      score: this.score,
      lines: this.linesCleared,
      level: this.level,
      combo: this.combo,
      b2b: this.lockState.b2b,
      pending: this.pending,
      piecesPlaced: this.piecesPlaced,
      maxCombo: this.maxCombo,
      attackSent: this.attackSent,
      attackReceived: this.attackReceived,
      gameOver: this.gameOver,
    };
  }

  private translate(dx: number): boolean {
    if (!this.current || this.gameOver) return false;
    const moved = { ...this.current, x: this.current.x + dx };
    if (!fits(this.board, moved)) return false;
    this.afterSuccessfulAction(moved, 'move');
    return true;
  }

  private rotate(direction: RotateDirection): boolean {
    if (!this.current || this.gameOver) return false;
    const rotated = tryRotate(this.board, this.current, direction);
    if (!rotated) return false;
    this.afterSuccessfulAction(rotated, 'rotate');
    return true;
  }

  /** 이동·회전에 성공하면 고정 지연을 다시 500ms로(블록당 최대 15번) */
  private afterSuccessfulAction(next: Placement, action: LastAction): void {
    this.current = next;
    this.lastAction = action;
    if (this.lockTimer !== null && this.lockResets < MAX_LOCK_RESETS) {
      this.lockTimer = LOCK_DELAY_MS;
      this.lockResets++;
    }
    if (!isGrounded(this.board, next)) this.lockTimer = null;
  }

  private lockCurrent(hardDropCells: number): void {
    if (!this.current) return;
    const result = resolveLock(this.lockState, {
      placement: this.current,
      lastAction: this.lastAction,
      softDropCells: this.softCells,
      hardDropCells,
    });
    this.lockState = result.state;
    this.score += result.score;
    this.piecesPlaced++;
    this.maxCombo = Math.max(this.maxCombo, result.state.combo);
    this.attackSent += result.sent;
    this.seq++;
    this.events.push({
      type: 'lock',
      seq: this.seq,
      placement: this.current,
      holdUsed: this.holdUsed,
      lastAction: this.lastAction,
      result,
    });
    this.holdUsed = false;
    this.spawn(this.sequence.at(this.nextIndex++));
  }

  /** 새 블록은 숨김 2행 중앙에 나오고, 그 자리가 막히면 topout(GR-04) */
  private spawn(piece: PieceType): void {
    const placement: Placement = { piece, rotation: 0, x: SPAWN_X, y: SPAWN_Y };
    this.lastAction = 'none';
    this.gravityElapsed = 0;
    this.lockTimer = null;
    this.lockResets = 0;
    this.softCells = 0;
    if (!fits(this.board, placement)) {
      this.current = null;
      this.gameOver = true;
      this.events.push({ type: 'topout' });
      return;
    }
    this.current = placement;
  }
}
