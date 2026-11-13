import { useEffect } from 'react';
import type { Game } from '../engine';
import { GAME_KEYS, InputState, type Action } from './input';

/** 동작 목록을 엔진에 반영한다. 'pause'는 엔진에 넘기지 않고 돌려준다(싱글만 쓴다) */
export function applyActions(game: Game, actions: Action[]): { pause: boolean } {
  let pause = false;
  for (const action of actions) {
    switch (action) {
      case 'left':
        game.moveLeft();
        break;
      case 'right':
        game.moveRight();
        break;
      case 'rotateCW':
        game.rotateCW();
        break;
      case 'rotateCCW':
        game.rotateCCW();
        break;
      case 'hardDrop':
        game.hardDrop();
        break;
      case 'hold':
        game.holdPiece();
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
    const down = (e: KeyboardEvent) => {
      if (!GAME_KEYS.has(e.code)) return;
      e.preventDefault();
      if (!e.repeat) input.press(e.code);
    };
    const up = (e: KeyboardEvent) => {
      if (!GAME_KEYS.has(e.code)) return;
      e.preventDefault();
      input.release(e.code);
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
