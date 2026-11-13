import { BOARD_HEIGHT, BOARD_WIDTH, type Game } from '../engine';
import {
  AI_INPUTS,
  SERVER_TIMEOUT_MS,
  type Difficulty,
  type MoveOutcome,
  type MoveRequestBody,
  type ServerMove,
} from './types';

/** 엔진 상태로 POST /move 요청 본문(API 명세 4.1)을 만든다. board는 맨 위 행부터 22 × 10, 0 빈칸·1 채움 */
export function buildMoveRequest(game: Game, difficulty: Difficulty): MoveRequestBody {
  const board: number[][] = [];
  for (let r = 0; r < BOARD_HEIGHT; r++) {
    board.push(game.board.slice(r * BOARD_WIDTH, (r + 1) * BOARD_WIDTH).map((cell) => (cell === '0' ? 0 : 1)));
  }
  return {
    difficulty,
    board,
    current: game.current!.piece,
    hold: game.hold,
    canHold: !game.holdUsed,
    next: game.preview(),
    pendingGarbage: game.pending.reduce((sum, bundle) => sum + bundle.lines, 0),
  };
}

export interface ClientOptions {
  fetch: typeof fetch;
  /** AI Base URL (예: https://{host}/ai/v1) */
  baseUrl: string;
  accessToken: string;
  timeoutMs?: number;
  now?: () => number;
}

/**
 * AI 서버에 다음 수를 묻는다(FR-AI-02). 보낸 시각부터 150ms를 넘기거나 네트워크 오류·4xx·5xx·형식 오류면
 * fallback을 돌려준다. 시간 초과 뒤에 도착한 응답은 버린다(이 Promise는 이미 fallback으로 끝났다).
 */
export function requestServerMove(body: MoveRequestBody, options: ClientOptions): Promise<MoveOutcome> {
  const timeoutMs = options.timeoutMs ?? SERVER_TIMEOUT_MS;
  const now = options.now ?? (() => Date.now());
  const started = now();
  const controller = new AbortController();

  return new Promise<MoveOutcome>((resolve) => {
    let settled = false;
    const finish = (outcome: MoveOutcome) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(outcome);
    };
    const timer = setTimeout(() => {
      controller.abort();
      finish({ source: 'fallback', reason: 'timeout' });
    }, timeoutMs);

    options
      .fetch(`${options.baseUrl}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${options.accessToken}` },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      .then(async (res) => {
        if (settled) return;
        if (now() - started > timeoutMs) return finish({ source: 'fallback', reason: 'timeout' });
        if (!res.ok) return finish({ source: 'fallback', reason: 'http' });
        const envelope = await res.json();
        if (now() - started > timeoutMs) return finish({ source: 'fallback', reason: 'timeout' });
        const move = envelope?.success ? (envelope.data as ServerMove) : null;
        finish(isValidMove(move) ? { source: 'server', move } : { source: 'fallback', reason: 'invalid' });
      })
      .catch(() => finish({ source: 'fallback', reason: 'network' }));
  });
}

function isValidMove(move: ServerMove | null): move is ServerMove {
  return (
    !!move &&
    Array.isArray(move.inputs) &&
    move.inputs.length > 0 &&
    move.inputs.every((input) => AI_INPUTS.includes(input)) &&
    move.inputs[move.inputs.length - 1] === 'HARD_DROP'
  );
}
