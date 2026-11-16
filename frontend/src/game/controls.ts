import { useEffect } from 'react';
import type { Game } from '../engine';
import { InputState, type Action } from './input';
import { sfx } from './sound';

/** 성공한 조작의 효과음(내 보드 전용) */
export function inputSound(action: Action): void {
  if (action === 'left' || action === 'right') sfx.move();
  else if (action === 'rotateCW' || action === 'rotateCCW') sfx.rotate();
  else if (action === 'hold') sfx.hold();
}

/**
 * 동작 목록을 엔진에 반영한다. 'pause'는 엔진에 넘기지 않고 돌려준다(싱글만 쓴다).
 * onApplied는 이동·회전·홀드가 실제로 성공했을 때 불린다(효과음용, REQ-023). 엔진 결과에는 영향이 없다.
 */
export function applyActions(game: Game, actions: Action[], onApplied?: (action: Action) => void): { pause: boolean } {
  let pause = false;
  const ok = (action: Action, done: boolean) => {
    if (done) onApplied?.(action);
  };
  for (const action of actions) {
    switch (action) {
      case 'left':
        ok(action, game.moveLeft());
        break;
      case 'right':
        ok(action, game.moveRight());
        break;
      case 'rotateCW':
        ok(action, game.rotateCW());
        break;
      case 'rotateCCW':
        ok(action, game.rotateCCW());
        break;
      case 'hardDrop':
        game.hardDrop();
        break;
      case 'hold':
        ok(action, game.holdPiece());
        break;
      case 'softDropOn':
        game.setSoftDrop(true);
        break;
      case 'softDropOff':
        game.setSoftDrop(false);
        break;
      case 'pause':
        pause = true;
        break;
    }
  }
  return { pause };
}

/** 키보드 이벤트를 InputState에 연결한다. 브라우저 자동 반복 keydown은 버리고 DAS·ARR은 InputState가 센다 */
export function useKeyboard(input: InputState, enabled: boolean): void {
  useEffect(() => {
    if (!enabled) return;
    // 스크립트가 dispatchEvent로 만든 가짜 키(isTrusted=false)는 게임에 넣지 않는다(T-39)
    const down = (e: KeyboardEvent) => {
      if (!e.isTrusted || !input.handles(e.code)) return;
      e.preventDefault();
      if (!e.repeat) {
        input.press(e.code);
        input.stats.keyDown(e.code, e.timeStamp);
      }
    };
    const up = (e: KeyboardEvent) => {
      if (!e.isTrusted || !input.handles(e.code)) return;
      e.preventDefault();
      input.release(e.code);
      input.stats.keyUp(e.code, e.timeStamp);
    };
    const blur = () => input.reset();
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    window.addEventListener('blur', blur);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      window.removeEventListener('blur', blur);
      input.reset();
    };
  }, [input, enabled]);
}
