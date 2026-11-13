/**
 * 공용 규칙 시험 데이터(shared/rules-fixtures)를 FE 엔진으로 돌린다(CON-002).
 * 깨지면: FE 엔진이 승인된 게임 규칙(GR-01~GR-08)이나, BE·AI가 같은 JSON으로 확인할 기대값과 어긋났다는 뜻이다.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { bagSequence } from './bag';
import { boardFromBottomRows, boardToString } from './board';
import * as C from './constants';
import { tryRotate, type RotateDirection } from './movement';
import { KICKS_I, KICKS_JLSTZ, SHAPES } from './pieces';
import { resolveLock } from './rules';
import type { GarbageBundle, LastAction, PieceType, Placement, Rotation } from './types';

function fixture<T>(name: string): T {
  const url = new URL(`../../../shared/rules-fixtures/${name}`, import.meta.url);
  return JSON.parse(readFileSync(url, 'utf-8')) as T;
}

interface BagFixture {
  cases: { seed: string; first21: PieceType[] }[];
}

interface PlacementJson {
  piece: PieceType;
  rotation: Rotation;
  x: number;
  y: number;
}

interface SrsFixture {
  shapes: Record<PieceType, [number, number][][]>;
  kicks: { JLSTZ: Record<string, [number, number][]>; I: Record<string, [number, number][]> };
  rotationCases: {
    name: string;
    board: { bottomRows: string[] };
    start: PlacementJson;
    direction: RotateDirection;
    expected: Omit<PlacementJson, 'piece'> | null;
  }[];
}

interface LockFixture {
  cases: {
    name: string;
    state: { bottomRows: string[]; linesTotal: number; combo: number; b2b: boolean; pending: GarbageBundle[] };
    lock: PlacementJson & { lastAction: LastAction; softDropCells?: number; hardDropCells?: number };
    expected: {
      bottomRows: string[];
      linesCleared: number;
      tSpin: boolean;
      perfectClear: boolean;
      backToBack: boolean;
      score: number;
      level: number;
      attack: number;
      cancelled: number;
      sent: number;
      garbageRaised: number;
      state: { linesTotal: number; combo: number; b2b: boolean; pending: GarbageBundle[] };
    };
  }[];
}

describe('GR-01 7-bag: Python 참조 구현과 같은 순서', () => {
  const { cases } = fixture<BagFixture>('bag.json');
  it.each(cases)('seed $seed', ({ seed, first21 }) => {
    expect(bagSequence(seed, 21)).toEqual(first21);
    expect(bagSequence(seed, 21)).toEqual(bagSequence(seed, 21));
  });
});

describe('GR-01~04 상수', () => {
  it('timing.json과 엔진 상수가 같다', () => {
    const t = fixture<Record<string, any>>('timing.json');
    expect([C.BOARD_WIDTH, C.BOARD_HEIGHT, C.HIDDEN_ROWS]).toEqual([t.board.width, t.board.height, t.board.hiddenRows]);
    expect([C.SPAWN_X, C.SPAWN_Y]).toEqual([t.spawn.x, t.spawn.y]);
    expect([C.DAS_MS, C.ARR_MS]).toEqual([t.autoRepeat.dasMs, t.autoRepeat.arrMs]);
    expect([...C.GRAVITY_MS]).toEqual(t.gravityMsByLevel);
    expect([C.SOFT_DROP_MS, C.LINES_PER_LEVEL, C.LOCK_DELAY_MS, C.MAX_LOCK_RESETS, C.PREVIEW_COUNT]).toEqual([
      t.softDropMs, t.linesPerLevel, t.lockDelayMs, t.maxLockResets, t.previewCount,
    ]);
    expect(C.gravityMs(20)).toBe(7);
  });
});

describe('GR-04 SRS', () => {
  const srs = fixture<SrsFixture>('srs.json');

  it('모양·킥 표가 srs.json과 같다', () => {
    expect(JSON.parse(JSON.stringify(SHAPES))).toEqual(srs.shapes);
    expect(JSON.parse(JSON.stringify(KICKS_JLSTZ))).toEqual(srs.kicks.JLSTZ);
    expect(JSON.parse(JSON.stringify(KICKS_I))).toEqual(srs.kicks.I);
  });

  it.each(srs.rotationCases)('$name', ({ board, start, direction, expected }) => {
    const result = tryRotate(boardFromBottomRows(board.bottomRows), start as Placement, direction);
    expect(result).toEqual(expected === null ? null : { piece: start.piece, ...expected });
  });
});

describe('GR-05~08 lock 판정·점수·공격·상쇄', () => {
  const { cases } = fixture<LockFixture>('lock-cases.json');
  it.each(cases)('$name', ({ state, lock, expected }) => {
    const result = resolveLock(
      { board: boardFromBottomRows(state.bottomRows), linesTotal: state.linesTotal, combo: state.combo, b2b: state.b2b, pending: state.pending },
      {
        placement: { piece: lock.piece, rotation: lock.rotation, x: lock.x, y: lock.y },
        lastAction: lock.lastAction,
        softDropCells: lock.softDropCells,
        hardDropCells: lock.hardDropCells,
      },
    );
    const { bottomRows, state: expectedState, ...rest } = expected;
    expect(boardToString(result.board)).toBe(boardToString(boardFromBottomRows(bottomRows)));
    expect({
      linesCleared: result.linesCleared,
      tSpin: result.tSpin,
      perfectClear: result.perfectClear,
      backToBack: result.backToBack,
      score: result.score,
      level: result.level,
      attack: result.attack,
      cancelled: result.cancelled,
      sent: result.sent,
      garbageRaised: result.garbageRaised,
    }).toEqual(rest);
    expect({
      linesTotal: result.state.linesTotal,
      combo: result.state.combo,
      b2b: result.state.b2b,
      pending: result.state.pending,
    }).toEqual(expectedState);
  });
});
