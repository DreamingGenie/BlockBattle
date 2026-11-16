/**
 * 키 조작(GR-02, POL-015, T-19).
 * 순수 로직: 눌린 키와 경과 시간으로 이번 프레임에 할 동작 목록을 만든다. 시계를 쓰지 않아 시험으로 DAS·ARR을 확인할 수 있다.
 * ←/→: 누르면 바로 1칸, 167ms(DAS) 뒤부터 33ms(ARR)마다 1칸. 두 방향이 함께 눌리면 나중에 누른 쪽.
 * 어떤 키가 어떤 동작인지는 키 설정(keybindings, T-38)을 따른다.
 */
import { ARR_MS, DAS_MS } from '../engine';
import { InputStatsRecorder } from './inputStats';
import { DEFAULT_BINDINGS, loadBindings, type BindAction, type KeyBindings } from './keybindings';

export type Action = 'left' | 'right' | 'rotateCW' | 'rotateCCW' | 'hardDrop' | 'hold' | 'softDropOn' | 'softDropOff' | 'pause';

const SIMPLE: Partial<Record<BindAction, Action>> = {
  rotateCW: 'rotateCW',
  rotateCCW: 'rotateCCW',
  hardDrop: 'hardDrop',
  hold: 'hold',
  pause: 'pause',
};

export class InputState {
  private horizontal: { dir: 'left' | 'right'; held: number; repeated: number } | null = null;
  private heldDirs: ('left' | 'right')[] = [];
  private queue: Action[] = [];
  private readonly byCode = new Map<string, BindAction>();
  /** 게임 동작으로 이어진 실제 키 입력 수(T-39 봇 판정용, 결과와 함께 서버로 보낸다) */
  presses = 0;
  /** 봇 판정용 입력 요약값 기록(T-40) */
  readonly stats = new InputStatsRecorder();

  /** 새 판을 시작할 때 키 입력 수와 요약값 기록을 비운다 */
  resetCounters(): void {
    this.presses = 0;
    this.stats.reset();
  }

  /** bindings를 주지 않으면 저장된 키 설정을 읽는다 */
  constructor(bindings: KeyBindings = typeof localStorage === 'undefined' ? DEFAULT_BINDINGS : loadBindings()) {
    for (const [action, codes] of Object.entries(bindings) as [BindAction, string[]][]) {
      for (const code of codes) this.byCode.set(code, action);
    }
  }

  /** 게임 화면이 잡아 둘 키인가(브라우저 스크롤 등 기본 동작을 막는다) */
  handles(code: string): boolean {
    return this.byCode.has(code);
  }

  /** keydown(자동 반복 이벤트는 무시해야 한다 — DAS·ARR은 여기서 직접 센다) */
  press(code: string): void {
    const bound = this.byCode.get(code);
    if (bound) this.presses++;
    if (bound === 'left' || bound === 'right') {
      const dir = bound;
      this.heldDirs = this.heldDirs.filter((d) => d !== dir).concat(dir);
      this.horizontal = { dir, held: 0, repeated: 0 };
      this.queue.push(dir);
      return;
    }
    if (bound === 'softDrop') {
      this.queue.push('softDropOn');
      return;
    }
    const action = bound && SIMPLE[bound];
    if (action) this.queue.push(action);
  }

  release(code: string): void {
    const bound = this.byCode.get(code);
    if (bound === 'left' || bound === 'right') {
      const dir = bound;
      this.heldDirs = this.heldDirs.filter((d) => d !== dir);
      if (this.horizontal?.dir === dir) {
        const other = this.heldDirs[this.heldDirs.length - 1];
        // 다른 방향이 아직 눌려 있으면 그쪽으로 이어 간다(DAS는 처음부터 다시)
        this.horizontal = other ? { dir: other, held: 0, repeated: 0 } : null;
      }
      return;
    }
    if (bound === 'softDrop') this.queue.push('softDropOff');
  }

  /** 모든 키를 놓은 것으로(창이 포커스를 잃을 때) */
  reset(): void {
    if (this.horizontal || this.heldDirs.length) this.queue.push('softDropOff');
    this.horizontal = null;
    this.heldDirs = [];
  }

  /** 이번 프레임에 할 동작. dt는 직전 프레임부터 흐른 ms */
  update(dt: number): Action[] {
    const actions = this.queue;
    this.queue = [];
    const h = this.horizontal;
    if (h) {
      h.held += dt;
      if (h.held >= DAS_MS) {
        const total = 1 + Math.floor((h.held - DAS_MS) / ARR_MS);
        while (h.repeated < total) {
          actions.push(h.dir);
          h.repeated++;
        }
      }
    }
    return actions;
  }
}

