import { afterEach, describe, expect, it, vi } from 'vitest';
import { adoptInvitePath } from './room';

/** 깨지면: 하위 경로(예: /tetris/)에 올렸을 때 초대 링크로 들어온 사람이 방 화면으로 가지 못한다 */
describe('초대 링크 경로 → 해시 라우트', () => {
  afterEach(() => vi.unstubAllGlobals());

  function stubHistory() {
    const replaceState = vi.fn();
    vi.stubGlobal('window', { history: { replaceState } });
    return replaceState;
  }

  it('도메인 루트: /room/k7q2mx → /#/room/K7Q2MX', () => {
    const replaceState = stubHistory();
    expect(adoptInvitePath('/room/k7q2mx', '/')).toBe('/#/room/K7Q2MX');
    expect(replaceState).toHaveBeenCalledWith(null, '', '/#/room/K7Q2MX');
  });

  it('하위 경로: /tetris/room/K7Q2MX/ → /tetris/#/room/K7Q2MX', () => {
    stubHistory();
    expect(adoptInvitePath('/tetris/room/K7Q2MX/', '/tetris/')).toBe('/tetris/#/room/K7Q2MX');
  });

  it('다른 경로나 하위 경로 밖의 초대 주소는 건드리지 않는다', () => {
    const replaceState = stubHistory();
    expect(adoptInvitePath('/room/K7Q2MX', '/tetris/')).toBeNull();
    expect(adoptInvitePath('/tetris/lobby', '/tetris/')).toBeNull();
    expect(replaceState).not.toHaveBeenCalled();
  });
});
