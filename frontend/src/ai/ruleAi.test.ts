/**
 * GR-09 FE 규칙 AI. 깨지면: AI 서버가 늦을 때 대신 두는 수가 승인된 평가식·실수 확률과 달라진다.
 */
import { describe, expect, it } from 'vitest';
import { boardFromBottomRows, fits, tryRotate, SPAWN_X, SPAWN_Y, type Placement, type PieceType } from '../engine';
import { chooseRuleAiMove, evaluateBoard, ruleAiCandidates } from './ruleAi';

/** 생성 자리에서 입력을 재생해 최종 위치를 구한다(FE 엔진 규칙) */
function replay(rows: string[], piece: PieceType, inputs: string[]): Placement {
  const board = boardFromBottomRows(rows);
  let p: Placement = { piece, rotation: 0, x: SPAWN_X, y: SPAWN_Y };
  for (const input of inputs) {
    if (input === 'CW' || input === 'CCW') p = tryRotate(board, p, input)!;
    else if (input === 'LEFT' && fits(board, { ...p, x: p.x - 1 })) p = { ...p, x: p.x - 1 };
    else if (input === 'RIGHT' && fits(board, { ...p, x: p.x + 1 })) p = { ...p, x: p.x + 1 };
    else if (input === 'HARD_DROP') while (fits(board, { ...p, y: p.y + 1 })) p = { ...p, y: p.y + 1 };
  }
  return p;
}

describe('GR-09 평가식', () => {
  it('−0.51 × 높이 합 − 0.36 × 구멍 − 0.18 × 울퉁불퉁함 + 0.76 × 지운 줄', () => {
    // 높이 [2,1,0,…,0,1] 합 4, 구멍 0, 울퉁불퉁함 1+1+1 = 3
    expect(evaluateBoard(boardFromBottomRows(['G000000000', 'GG0000000G']), 0)).toBeCloseTo(-0.51 * 4 - 0.18 * 3);
    // 0열 높이 2에 구멍 1, 울퉁불퉁함 2
    expect(evaluateBoard(boardFromBottomRows(['G000000000', '0000000000']), 0)).toBeCloseTo(-0.51 * 2 - 0.36 - 0.18 * 2);
    expect(evaluateBoard(boardFromBottomRows([]), 4)).toBeCloseTo(0.76 * 4);
  });
});

describe('후보와 최선 수', () => {
  const well = ['GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0'];

  it('I는 오른쪽 우물에 세워 넣어 4줄을 지운다', () => {
    const best = ruleAiCandidates(boardFromBottomRows(well), 'I')[0]!;
    expect(best.linesCleared).toBe(4);
    expect([best.rotation, best.x]).toEqual([1, 7]);
    expect(best.inputs.at(-1)).toBe('HARD_DROP');
    expect(replay(well, 'I', best.inputs)).toMatchObject({ rotation: 1, x: 7, y: best.y });
  });

  it('모든 후보는 생성 자리에서 입력을 재생하면 그 자리에 놓이고 점수 순으로 정렬된다', () => {
    const rows = ['G000000000', 'GG0G000GGG'];
    const candidates = ruleAiCandidates(boardFromBottomRows(rows), 'T');
    expect(candidates.length).toBeGreaterThan(20);
    for (const c of candidates) expect(replay(rows, 'T', c.inputs)).toMatchObject({ rotation: c.rotation, x: c.x, y: c.y });
    for (let i = 1; i < candidates.length; i++) expect(candidates[i - 1]!.score).toBeGreaterThanOrEqual(candidates[i]!.score);
  });

  it('O는 회전해도 같은 칸이라 한 번만 센다', () => {
    expect(ruleAiCandidates(boardFromBottomRows([]), 'O')).toHaveLength(9);
  });
});

describe('난이도별 실수 확률(EASY 30%, NORMAL 10%, HARD 0%)', () => {
  const board = boardFromBottomRows(['GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0', 'GGGGGGGGG0']);
  const [best, second] = ruleAiCandidates(board, 'I');
  const pick = (difficulty: 'EASY' | 'NORMAL' | 'HARD', r: number) => chooseRuleAiMove(board, 'I', difficulty, () => r);

  it.each([
    ['EASY', 0.29, second], ['EASY', 0.3, best],
    ['NORMAL', 0.09, second], ['NORMAL', 0.1, best],
    ['HARD', 0, best],
  ] as const)('%s, 난수 %s', (difficulty, r, expected) => {
    expect(pick(difficulty, r)).toEqual(expected);
  });
});
