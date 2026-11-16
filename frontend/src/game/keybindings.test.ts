import { describe, expect, it } from 'vitest';
import { DEFAULT_BINDINGS, assignKey, isReservedKey, keyLabel, sanitize } from './keybindings';

/** T-38 키 설정. 깨지면 기능 키(F5 새로고침 등)를 지정할 수 있게 되거나, 한 키가 두 동작에 걸리거나, 깨진 저장값으로 조작이 멎는다 */
describe('keybindings', () => {
  it('F1~F12·Tab·Windows 키 같은 기능 키는 지정할 수 없다', () => {
    for (const code of ['F1', 'F5', 'F12', 'Tab', 'MetaLeft', 'ContextMenu', 'BrowserBack', 'AudioVolumeUp']) {
      expect(isReservedKey(code)).toBe(true);
    }
    for (const code of ['KeyA', 'Space', 'ArrowUp', 'ShiftLeft', 'Digit1', 'Numpad0', 'Escape']) {
      expect(isReservedKey(code)).toBe(false);
    }
  });

  it('다른 동작이 쓰던 키를 지정하면 거기서 빠지고 어디서 옮겼는지 알려 준다', () => {
    const { bindings, movedFrom } = assignKey(DEFAULT_BINDINGS, 'left', 0, 'KeyZ');
    expect(bindings.left).toEqual(['KeyZ']);
    expect(bindings.rotateCCW).toEqual(['ControlLeft']);
    expect(movedFrom).toBe('rotateCCW');
  });

  it('빈 두 번째 칸에 넣으면 키가 2개가 되고, 같은 동작 안에서 옮기면 겹치지 않는다', () => {
    const two = assignKey(DEFAULT_BINDINGS, 'left', 1, 'KeyA').bindings;
    expect(two.left).toEqual(['ArrowLeft', 'KeyA']);
    const swapped = assignKey(two, 'left', 0, 'KeyA');
    expect(swapped.bindings.left).toEqual(['KeyA']);
    expect(swapped.movedFrom).toBeNull();
  });

  it('깨진 저장값은 기본값으로, 기능 키·중복은 버린다', () => {
    expect(sanitize('nope')).toEqual(DEFAULT_BINDINGS);
    const fixed = sanitize({ left: ['F5', 'KeyA'], right: ['KeyA', 'KeyD'], hold: 'x' });
    expect(fixed.left).toEqual(['KeyA']);
    expect(fixed.right).toEqual(['KeyD']);
    expect(fixed.hold).toEqual(DEFAULT_BINDINGS.hold);
  });

  it('키 이름을 읽기 쉽게 보여 준다', () => {
    expect(['ArrowUp', 'KeyX', 'Digit3', 'ControlLeft', 'Space'].map(keyLabel)).toEqual(['↑', 'X', '3', 'L Ctrl', 'Space']);
  });
});
