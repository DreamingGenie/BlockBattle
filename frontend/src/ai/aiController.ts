import type { Game } from '../engine';
import { buildMoveRequest } from './aiClient';
import { chooseRuleAiMove } from './ruleAi';
import { REPLAY_INTERVAL_MS, type AiInput, type Difficulty, type MoveOutcome, type MoveRequestBody } from './types';

export interface ControllerDeps {
  /** 보통 requestServerMove를 감싼 함수. 150ms 안에 끝나는 Promise여야 한다 */
  requestMove: (body: MoveRequestBody) => Promise<MoveOutcome>;
  /** 규칙 AI 실수 확률용 난수 [0, 1) */
  rand: () => number;
}

export interface Decision {
  seq: number;
  source: 'server' | 'fallback';
  reason?: string;
}

/**
 * vs AI의 AI 보드 조작기. 새 블록이 나오면 바로 수를 묻고, 받은 입력을 난이도 간격으로 하나씩 엔진에 재생한다.
 * AI 보드에는 중력 tick을 주지 않는다(T-8 계획 unlinked: 서버가 계산한 밀어 넣기 경로를 지키기 위해).
 */
export class AiController {
  readonly decisions: Decision[] = [];
  private stopped = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly game: Game,
    private readonly difficulty: Difficulty,
    private readonly deps: ControllerDeps,
  ) {}

  start(): void {
    this.planNext();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
  }

  private planNext(): void {
    if (this.stopped || this.game.gameOver || !this.game.current) return;
    const seqAtRequest = this.game.seq;
    this.deps.requestMove(buildMoveRequest(this.game, this.difficulty)).then((outcome) => {
      // 그사이 블록이 바뀌었거나 멈췄으면 이 응답은 버린다
      if (this.stopped || this.game.seq !== seqAtRequest) return;
      const inputs = outcome.source === 'server' ? outcome.move.inputs : this.fallbackInputs();
      this.decisions.push(outcome.source === 'server'
        ? { seq: seqAtRequest, source: 'server' }
        : { seq: seqAtRequest, source: 'fallback', reason: outcome.reason });
      this.replay(inputs, seqAtRequest);
    });
  }

  private fallbackInputs(): AiInput[] {
    const current = this.game.current;
    if (!current) return ['HARD_DROP'];
    return chooseRuleAiMove(this.game.board, current.piece, this.difficulty, this.deps.rand)?.inputs ?? ['HARD_DROP'];
  }

  private replay(inputs: AiInput[], seqAtStart: number): void {
    const queue = [...inputs];
    const step = () => {
      if (this.stopped || this.game.gameOver) return;
      const input = queue.shift();
      if (input) this.apply(input);
      if (this.game.seq !== seqAtStart) {
        this.planNext();
        return;
      }
      if (queue.length === 0) {
        // 입력이 끝났는데도 고정되지 않았으면 떨어뜨린다(게임은 멈추지 않는다)
        this.game.hardDrop();
        this.planNext();
        return;
      }
      this.timer = setTimeout(step, REPLAY_INTERVAL_MS[this.difficulty]);
    };
    this.timer = setTimeout(step, REPLAY_INTERVAL_MS[this.difficulty]);
  }

  private apply(input: AiInput): void {
    switch (input) {
      case 'LEFT': this.game.moveLeft(); break;
      case 'RIGHT': this.game.moveRight(); break;
      case 'CW': this.game.rotateCW(); break;
      case 'CCW': this.game.rotateCCW(); break;
      case 'SOFT_DROP': this.game.softDropStep(); break;
      case 'HARD_DROP': this.game.hardDrop(); break;
      case 'HOLD': this.game.holdPiece(); break;
    }
  }
}
