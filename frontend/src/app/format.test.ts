import { describe, expect, it } from 'vitest';
import { formatDuration, formatWinTime, objectParticle } from './format';

/** T-42 화면 표기. 깨지면 결과·랭킹·기록에 '1:54.1만에'·'AI NORMAL를' 같은 어색한 문장이 다시 나온다 */
describe('format', () => {
  it('승리 시간은 분·초로 읽히게 쓴다', () => {
    expect(formatWinTime(46_735)).toBe('46.7초');
    expect(formatWinTime(114_100)).toBe('1분 54.1초');
    expect(formatWinTime(120_400)).toBe('2분 0.4초');
  });

  it('경기 시간은 초·분·시간으로 쓴다', () => {
    expect(formatDuration(null)).toBe('-');
    expect(formatDuration(45_000)).toBe('45초');
    expect(formatDuration(184_000)).toBe('3분 4초');
    expect(formatDuration(180_000)).toBe('3분');
    expect(formatDuration(3_720_000)).toBe('1시간 2분');
  });

  it('난이도 이름의 받침에 맞춰 을·를을 고른다', () => {
    expect(['VERY_EASY', 'EASY', 'NORMAL', 'HARD', 'IMPOSSIBLE'].map(objectParticle)).toEqual(['를', '를', '을', '를', '을']);
  });
});
