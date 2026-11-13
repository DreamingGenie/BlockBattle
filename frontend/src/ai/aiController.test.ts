/**
 * FR-AI-01·02 vs AI 조작: 입력 재생 간격, 대체 수 실행, 늦은 응답 폐기.
 * 깨지면: AI가 정해진 속도로 두지 않거나, 서버가 늦을 때 게임이 멈추거나, 늦게 온 응답이 보드를 바꾼다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Game } from '../engine';
import { requestServerMove } from './aiClient';
import { AiController } from './aiController';
import { chooseRuleAiMove } from './ruleAi';
import type { MoveOutcome } from './types';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const serverMove = (inputs: string[]): MoveOutcome => ({
  source: 'server',
  move: { useHold: false, piece: 'T', rotation: 0, x: 2, inputs: inputs as never, tSpin: false, elapsedMs: 1 },
});

describe('입력 재생 간격(EASY 300 · NORMAL 150 · HARD 60ms)', () => {
  it.each([['EASY', 300], ['NORMAL', 150], ['HARD', 60]] as const)('%s는 %sms마다 입력 하나', async (difficulty, interval) => {
    const game = new Game(3);
    const requestMove = vi.fn(() => Promise.resolve(serverMove(['LEFT', 'HARD_DROP'])));
    new AiController(game, difficulty, { requestMove, rand: () => 0.99 }).start();
    await vi.advanceTimersByTimeAsync(0);
    const x0 = game.current!.x;
    await vi.advanceTimersByTimeAsync(interval - 1);
    expect(game.current!.x).toBe(x0);
    await vi.advanceTimersByTimeAsync(1);
    expect(game.current!.x).toBe(x0 - 1);
    await vi.advanceTimersByTimeAsync(interval);
    expect(game.seq).toBe(1);
    expect(requestMove).toHaveBeenCalledTimes(2); // 고정되면 다음 수를 바로 묻는다
  });
});

describe('서버가 늦어도 게임은 멈추지 않는다', () => {
  it('매번 150ms를 넘기는 서버면 규칙 AI 수로 계속 두고, 늦게 온 응답은 보드를 바꾸지 않는다', async () => {
    const game = new Game(11);
    const slowFetch = vi.fn((_u: string, init: RequestInit) => new Promise<Response>((resolve) => {
      // 끊긴 요청도 늦게 응답이 온다고 가정한다(abort를 무시하는 서버·프록시)
      void init;
      setTimeout(() => resolve(new Response(JSON.stringify({ success: true, error: null,
        data: { useHold: false, piece: 'I', rotation: 0, x: 0, inputs: ['LEFT', 'LEFT', 'LEFT', 'HARD_DROP'], tSpin: false, elapsedMs: 1 } }))), 400);
    }));
    const expectedFirst = chooseRuleAiMove(game.board, game.current!.piece, 'HARD', () => 0.5)!;
    const controller = new AiController(game, 'HARD', {
      requestMove: (body) => requestServerMove(body, { fetch: slowFetch as typeof fetch, baseUrl: 'https://h/ai/v1', accessToken: 't' }),
      rand: () => 0.5,
    });
    controller.start();

    // 비교군: 같은 시드에서 응답이 아예 오지 않고 150ms에 대체되는 경우(늦은 응답 없음)
    const reference = new Game(11);
    const referenceController = new AiController(reference, 'HARD', {
      requestMove: () => new Promise((resolve) => setTimeout(() => resolve({ source: 'fallback', reason: 'timeout' }), 150)),
      rand: () => 0.5,
    });
    referenceController.start();

    await vi.advanceTimersByTimeAsync(150 + 60 * expectedFirst.inputs.length);
    expect(game.seq).toBe(1);
    expect(controller.decisions[0]).toEqual({ seq: 0, source: 'fallback', reason: 'timeout' });
    expect(game.snapshot().board).toBe(reference.snapshot().board);

    // 늦은 응답(보낸 뒤 400ms)이 계속 도착해도 보드는 비교군과 똑같이 흘러간다
    await vi.advanceTimersByTimeAsync(10_000);
    expect(game.seq).toBeGreaterThan(5);
    expect(controller.decisions.every((d) => d.source === 'fallback')).toBe(true);
    expect(game.snapshot()).toEqual(reference.snapshot());
    controller.stop();
    referenceController.stop();
  });

  it('조작 중 블록이 바뀐 뒤 도착한 응답은 버린다', async () => {
    const game = new Game(5);
    let resolveLate!: (o: MoveOutcome) => void;
    const requestMove = vi.fn(() => new Promise<MoveOutcome>((resolve) => (resolveLate = resolve)));
    new AiController(game, 'EASY', { requestMove, rand: () => 0.99 }).start();
    game.hardDrop(); // 응답 전에 블록이 고정됨(seq 0 → 1)
    const before = game.snapshot();
    resolveLate(serverMove(['RIGHT', 'RIGHT', 'HARD_DROP']));
    await vi.advanceTimersByTimeAsync(2_000);
    expect(game.snapshot()).toEqual(before);
  });
});
