import { describe, expect, it } from 'vitest';
import { listRows, podiumSlots } from './podium';

/** 깨지면 기록이 1~2개인 랭킹에서 1위가 가운데를 벗어나거나, 시상대·목록에 같은 사람이 두 번 나온다 */
describe('podium', () => {
  const rows = ['A', 'B', 'C', 'D', 'E'];
  const layout = (n: number) => podiumSlots(rows.slice(0, n)).map((s) => `${s.place}:${s.row ?? '-'}`);

  it('기록이 없으면 시상대를 그리지 않는다', () => {
    expect(podiumSlots([])).toEqual([]);
    expect(listRows([])).toEqual([]);
  });

  it('1위는 기록 수와 상관없이 가운데 칸이고 모자란 칸은 빈 자리로 남는다', () => {
    expect(layout(1)).toEqual(['2:-', '1:A', '3:-']);
    expect(layout(2)).toEqual(['2:B', '1:A', '3:-']);
    expect(layout(3)).toEqual(['2:B', '1:A', '3:C']);
    expect(layout(5)).toEqual(['2:B', '1:A', '3:C']);
  });

  it('목록은 4위부터만 담는다', () => {
    expect(listRows(rows.slice(0, 3))).toEqual([]);
    expect(listRows(rows.slice(0, 4))).toEqual(['D']);
    expect(listRows(rows)).toEqual(['D', 'E']);
  });
});
