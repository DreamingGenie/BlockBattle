import { describe, expect, it } from 'vitest';
import { InputStatsRecorder } from './inputStats';

/** T-40 봇 판정 요약값. 깨지면 서버가 사람과 봇을 가를 근거값이 틀어진다 */
describe('InputStatsRecorder', () => {
  it('일정한 간격으로 놓으면 간격 변동계수가 0, 들쭉날쭉하면 커진다', () => {
    const bot = new InputStatsRecorder();
    for (let i = 0; i < 10; i++) bot.lock(i * 250);
    expect(bot.summary(10, 10).intervalCv).toBe(0);

    const human = new InputStatsRecorder();
    [0, 400, 1500, 1900, 3200, 3500, 5100].forEach((t) => human.lock(t));
    expect(human.summary(20, 7).intervalCv!).toBeGreaterThan(0.3);
  });

  it('키 누름 시간의 평균·변동계수와 블록당 입력 수를 낸다', () => {
    const r = new InputStatsRecorder();
    [[0, 80], [200, 320], [500, 600]].forEach(([d, u]) => {
      r.keyDown('Space', d!);
      r.keyUp('Space', u!);
    });
    const s = r.summary(30, 10);
    expect(s.holdMeanMs).toBe(100);
    expect(s.holdCv).toBeGreaterThan(0.1);
    expect(s.inputsPerPiece).toBe(3);
  });

  it('표본이 모자라면 null', () => {
    expect(new InputStatsRecorder().summary(0, 0)).toEqual({ intervalCv: null, holdMeanMs: null, holdCv: null, inputsPerPiece: null });
  });
});
