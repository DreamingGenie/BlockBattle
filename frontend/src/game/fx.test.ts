import { describe, expect, it } from 'vitest';
import { boardFromBottomRows, boardToString, Game, HIDDEN_ROWS, type LockEvent } from '../engine';
import { clearLabel, clearedRows, createFx, MAX_PARTICLES, onLock, stepFx } from './fx';
import { COLORS } from './renderer';

const base = { linesCleared: 0, tSpin: false, backToBack: false, perfectClear: false, sent: 0 };

/** REQ-023 문구 목록. 깨지면: 화면에 틀린 문구가 뜨거나, 4줄 삭제에 상표 이름이 나간다 */
describe('clearLabel', () => {
  it('줄 수·T-스핀·B2B·콤보·퍼펙트 클리어·보낸 줄을 문구로 만든다', () => {
    expect(clearLabel({ ...base, linesCleared: 4, sent: 4 }, 0)).toMatchObject({ title: 'QUAD', lines: [], sent: 4, strong: true });
    expect(clearLabel({ ...base, linesCleared: 2, tSpin: true, backToBack: true, sent: 5 }, 2)).toMatchObject({
      title: 'T-SPIN DOUBLE',
      lines: ['B2B', '2 COMBO'],
      strong: true,
    });
    expect(clearLabel({ ...base, linesCleared: 1, perfectClear: true, sent: 10 }, 0)?.lines).toEqual(['PERFECT CLEAR']);
    expect(clearLabel({ ...base, linesCleared: 1 }, 0)).toMatchObject({ title: 'SINGLE', strong: false });
  });

  it('줄을 못 지운 고정은 문구가 없다(T-스핀 제외), 첫 삭제(combo 0)는 콤보를 띄우지 않는다', () => {
    expect(clearLabel(base, -1)).toBeNull();
    expect(clearLabel({ ...base, tSpin: true }, -1)?.title).toBe('T-SPIN');
    expect(clearLabel({ ...base, linesCleared: 1 }, 0)?.lines).toEqual([]);
  });
});

/** 깨지면: 섬광·파편이 지운 줄이 아닌 엉뚱한 행에서 나온다 */
describe('clearedRows', () => {
  it('고정 직전 보드에 블록을 놓았을 때 꽉 차는 행을 돌려준다', () => {
    // 맨 아래 2행이 0~5열만 차 있고 6~9열이 빈 보드에 O(6~7열)를 놓으면 아무 행도 안 찬다, I(가로, 6~9열)는 맨 아래 행을 채운다
    const board = boardFromBottomRows(['XXXXXX0000', 'XXXXXX0000'].map((r) => r.replace(/X/g, 'G')));
    expect(clearedRows(board, { piece: 'O', rotation: 0, x: 5, y: 20 })).toEqual([]);
    expect(clearedRows(board, { piece: 'I', rotation: 0, x: 6, y: 20 })).toEqual([21]);
  });
});

/** 깨지면: 연출이 엔진 상태를 바꿔 친구 대전 서버 검증과 어긋나거나(REQ-023·BR-05), 파편이 쌓여 프레임이 떨어진다(NFR-06) */
describe('onLock', () => {
  function lockEventWithClear(): { event: LockEvent; before: ReturnType<typeof boardFromBottomRows> } {
    const game = new Game(1);
    const before = boardFromBottomRows(['GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0']);
    const event: LockEvent = {
      type: 'lock',
      seq: 1,
      placement: { piece: 'I', rotation: 1, x: 7, y: 18 },
      holdUsed: false,
      lastAction: 'none',
      softDropCells: 0,
      hardDropCells: 16,
      result: {
        board: game.board,
        linesCleared: 4,
        tSpin: false,
        perfectClear: true,
        backToBack: false,
        score: 0,
        level: 1,
        attack: 14,
        cancelled: 0,
        sent: 14,
        garbageRaised: 0,
        state: { board: game.board, linesTotal: 4, combo: 0, b2b: true, pending: [] },
      },
    };
    return { event, before };
  }

  it('보드·결과를 바꾸지 않고 섬광·잔상·문구를 만든다', () => {
    const { event, before } = lockEventWithClear();
    const beforeText = boardToString(before);
    const resultText = JSON.stringify(event.result);
    const fx = createFx();
    const rows = onLock(fx, { ...event, boardBefore: before, combo: 0, colors: COLORS }, 1000, () => 0.5);
    expect(rows).toEqual([18, 19, 20, 21]);
    expect(fx.rowFlashes.map((f) => f.row)).toEqual([18 - HIDDEN_ROWS, 19 - HIDDEN_ROWS, 20 - HIDDEN_ROWS, 21 - HIDDEN_ROWS]);
    expect(fx.trails).toHaveLength(1);
    expect(fx.popup).toMatchObject({ title: 'QUAD', lines: ['PERFECT CLEAR'], sent: 14 });
    expect(boardToString(before)).toBe(beforeText);
    expect(JSON.stringify(event.result)).toBe(resultText);
  });

  it(`파편은 ${MAX_PARTICLES}개를 넘지 않고, 수명이 지나면 모두 사라진다`, () => {
    const { event, before } = lockEventWithClear();
    const fx = createFx();
    for (let i = 0; i < 10; i++) onLock(fx, { ...event, boardBefore: before, combo: i, colors: COLORS }, 1000 + i, () => 0.5);
    expect(fx.particles.length).toBeLessThanOrEqual(MAX_PARTICLES);
    stepFx(fx, 5000, 16);
    expect(fx.particles).toHaveLength(0);
    expect(fx.rowFlashes).toHaveLength(0);
    expect(fx.popup).toBeNull();
  });
});
