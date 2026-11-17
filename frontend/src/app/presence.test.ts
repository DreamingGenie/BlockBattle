import { describe, expect, it } from 'vitest';
import { liveLabel } from './presence';

// T-49 계획의 표시 규칙: 0명·확인 중을 숫자 있는 상태와 구분한다.
// 깨지면: 집계를 못 받았는데 옛 숫자가 지금 값처럼 보이거나, 0명이 '플레이 중'처럼 초록으로 보인다는 뜻이다.
describe('liveLabel', () => {
  it('첫 응답 전과 heartbeat 실패 때는 숫자 없이 확인 중으로 보인다', () => {
    expect(liveLabel(null, false)).toMatchObject({ tone: 'wait', playing: null, sub: null });
    expect(liveLabel({ online: 5, playing: 2 }, true)).toMatchObject({ tone: 'wait', playing: null, sub: null });
  });

  it('플레이 중이 0명이면 회색 상태로 구분하고 접속 수는 보인다', () => {
    expect(liveLabel({ online: 3, playing: 0 }, false)).toEqual({ tone: 'idle', playing: 0, text: '플레이 중인 사람 없음', sub: '접속 3명' });
  });

  it('플레이 중 인원과 접속 수를 함께 보인다', () => {
    expect(liveLabel({ online: 1204, playing: 12 }, false)).toEqual({ tone: 'on', playing: 12, text: '명 플레이 중', sub: '접속 1,204명' });
  });
});
