/**
 * FR-AI-02 AI 서버 호출과 대체 조건, API 명세 4.1 요청 본문.
 * 깨지면: 늦거나 실패한 응답에서 게임이 멈추거나, 서버가 받는 요청 형식이 계약과 어긋난다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Game } from '../engine';
import { buildMoveRequest, requestServerMove } from './aiClient';
import type { MoveRequestBody } from './types';

const MOVE = { useHold: false, piece: 'T', rotation: 2, x: 4, inputs: ['CW', 'CW', 'RIGHT', 'HARD_DROP'], tSpin: false, elapsedMs: 12 };

function respond(status: number, body: unknown, delayMs: number) {
  return vi.fn((_url: string, init: RequestInit) =>
    new Promise<Response>((resolve, reject) => {
      const t = setTimeout(() => resolve(new Response(JSON.stringify(body), { status })), delayMs);
      init.signal?.addEventListener('abort', () => {
        clearTimeout(t);
        reject(new DOMException('aborted', 'AbortError'));
      });
    }));
}

const body: MoveRequestBody = buildMoveRequest(new Game(7), 'HARD');
const options = (fetchFn: unknown) => ({ fetch: fetchFn as typeof fetch, baseUrl: 'https://h/ai/v1', accessToken: 'tok', now: () => Date.now() });

describe('요청 본문(API 명세 4.1)', () => {
  it('엔진 상태를 보드 0·1, 현재·홀드·홀드 가능 여부·다음 5개·받을 방해 줄 수로 담는다', () => {
    const game = new Game(7);
    game.receiveGarbage({ lines: 2, holeColumn: 1 });
    game.receiveGarbage({ lines: 1, holeColumn: 5 });
    let req = buildMoveRequest(game, 'EASY');
    expect(req.difficulty).toBe('EASY');
    expect(req.board).toHaveLength(22);
    expect(req.board.every((row) => row.length === 10 && row.every((v) => v === 0))).toBe(true);
    expect(req).toMatchObject({ current: game.current!.piece, hold: null, canHold: true, next: game.preview(), pendingGarbage: 3 });

    const first = game.current!.piece;
    game.holdPiece();
    game.hardDrop();
    game.holdPiece();
    req = buildMoveRequest(game, 'EASY');
    expect(req.canHold).toBe(false);
    expect(req.hold).not.toBeNull();
    expect(req.board.flat().filter((v) => v === 1).length).toBe(4 + 3 * 9);
    expect(first).toBeTypeOf('string');
  });
});

describe('대체 조건(FR-AI-02)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('150ms 안에 오면 서버 수를 쓰고 POST /move에 토큰과 본문을 보낸다', async () => {
    const fetchFn = respond(200, { success: true, data: MOVE, error: null }, 50);
    const pending = requestServerMove(body, options(fetchFn));
    await vi.advanceTimersByTimeAsync(50);
    expect(await pending).toEqual({ source: 'server', move: MOVE });
    const [url, init] = fetchFn.mock.calls[0]!;
    expect(url).toBe('https://h/ai/v1/move');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  it('150ms를 넘기면 그때 대체하고 요청을 끊는다', async () => {
    const fetchFn = respond(200, { success: true, data: MOVE, error: null }, 200);
    let outcome: unknown;
    requestServerMove(body, options(fetchFn)).then((o) => (outcome = o));
    await vi.advanceTimersByTimeAsync(149);
    expect(outcome).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    expect(outcome).toEqual({ source: 'fallback', reason: 'timeout' });
    expect((fetchFn.mock.calls[0]![1].signal as AbortSignal).aborted).toBe(true);
  });

  it.each([
    [500, { success: false, data: null, error: { code: 'INTERNAL_ERROR' } }, 'http'],
    [401, { success: false, data: null, error: { code: 'UNAUTHORIZED' } }, 'http'],
    [400, { success: false, data: null, error: { code: 'INVALID_BOARD' } }, 'http'],
    [200, { success: true, data: { ...MOVE, inputs: ['CW'] }, error: null }, 'invalid'],
  ])('상태 %s면 대체(%s)', async (status, envelope, reason) => {
    const pending = requestServerMove(body, options(respond(status, envelope, 10)));
    await vi.advanceTimersByTimeAsync(10);
    expect(await pending).toEqual({ source: 'fallback', reason });
  });

  it('네트워크 오류면 대체', async () => {
    const fetchFn = vi.fn(() => Promise.reject(new TypeError('Failed to fetch')));
    expect(await requestServerMove(body, options(fetchFn))).toEqual({ source: 'fallback', reason: 'network' });
  });
});
