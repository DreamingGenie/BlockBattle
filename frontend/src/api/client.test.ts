import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError } from './client';

/** API 명세 1장 봉투·인증 규칙과 REQ-001 재발급 흐름. 깨지면 로그인 유지·만료 처리가 화면에서 어긋난다 */
function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const ok = (data: unknown, status = 200) => json(status, { success: true, data, error: null });
const fail = (status: number, code: string) => json(status, { success: false, data: null, error: { code, message: code } });

describe('ApiClient', () => {
  it('봉투의 data를 돌려주고 Access 토큰을 Bearer로 붙인다', async () => {
    const fetchFn = vi.fn().mockResolvedValueOnce(ok({ userId: 3 }));
    const api = new ApiClient(fetchFn);
    api.setToken('t1');
    expect(await api.get('/users/me')).toEqual({ userId: 3 });
    expect(fetchFn.mock.calls[0]![0]).toBe('/api/v1/users/me');
    expect(fetchFn.mock.calls[0]![1].headers.Authorization).toBe('Bearer t1');
  });

  it('실패 봉투는 오류 코드를 담은 ApiError가 된다', async () => {
    const api = new ApiClient(vi.fn().mockResolvedValueOnce(fail(400, 'INVALID_NICKNAME')));
    await expect(api.guest('x')).rejects.toMatchObject({ status: 400, code: 'INVALID_NICKNAME' });
  });

  it('401이면 Refresh 쿠키로 한 번 재발급하고 새 토큰으로 다시 보낸다', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(fail(401, 'TOKEN_EXPIRED'))
      .mockResolvedValueOnce(ok({ accessToken: 't2', expiresIn: 3600 }))
      .mockResolvedValueOnce(ok({ userId: 3 }));
    const api = new ApiClient(fetchFn);
    api.setToken('t1');
    expect(await api.get('/users/me')).toEqual({ userId: 3 });
    expect(fetchFn.mock.calls[1]![0]).toBe('/api/v1/auth/refresh');
    expect(fetchFn.mock.calls[2]![1].headers.Authorization).toBe('Bearer t2');
  });

  it('재발급도 실패하면 세션을 잃은 것으로 알리고 토큰을 지운다', async () => {
    const lost = vi.fn();
    const fetchFn = vi.fn().mockResolvedValueOnce(fail(401, 'TOKEN_EXPIRED')).mockResolvedValueOnce(fail(401, 'UNAUTHORIZED'));
    const api = new ApiClient(fetchFn);
    api.onSessionLost = lost;
    api.setToken('t1');
    await expect(api.get('/users/me')).rejects.toBeInstanceOf(ApiError);
    expect(lost).toHaveBeenCalledOnce();
    expect(api.token).toBeNull();
    expect(fetchFn).toHaveBeenCalledTimes(2); // 재시도를 반복하지 않는다
  });

  it('동시에 여러 요청이 401을 받아도 재발급은 한 번만 보낸다', async () => {
    let refreshCalls = 0;
    const fetchFn = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/auth/refresh')) {
        refreshCalls++;
        return ok({ accessToken: 't2', expiresIn: 3600 });
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer t2' ? ok({ fine: true }) : fail(401, 'TOKEN_EXPIRED');
    });
    const api = new ApiClient(fetchFn);
    api.setToken('t1');
    await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')]);
    expect(refreshCalls).toBe(1);
  });

  it('204 응답은 본문 없이 끝난다', async () => {
    const api = new ApiClient(vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })));
    api.setToken('t1');
    await expect(api.logout()).resolves.toBeUndefined();
    expect(api.token).toBeNull();
  });
});
