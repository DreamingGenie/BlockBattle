import { describe, expect, it } from 'vitest';
import { InputState } from './input';

/** GR-02(POL-015) 조작 규칙. 깨지면 좌우 자동 반복 간격(167ms·33ms)이나 키 대응이 명세와 달라진다 */
describe('InputState', () => {
  const count = (actions: string[], a: string) => actions.filter((x) => x === a).length;

  it('누르면 그 프레임에 바로 1칸 움직인다', () => {
    const input = new InputState();
    input.press('ArrowLeft');
    expect(input.update(16)).toEqual(['left']);
  });

  it('167ms 동안은 반복하지 않고, 그 뒤 33ms마다 1칸씩 반복한다', () => {
    const input = new InputState();
    input.press('ArrowRight');
    input.update(0); // 누른 즉시 1칸
    expect(count(input.update(166), 'right')).toBe(0);
    expect(count(input.update(1), 'right')).toBe(1); // 167ms
    expect(count(input.update(32), 'right')).toBe(0); // 199ms
    expect(count(input.update(1), 'right')).toBe(1); // 200ms
    expect(count(input.update(66), 'right')).toBe(2); // 266ms
  });

  it('두 방향이 함께 눌리면 나중에 누른 쪽으로 가고, 그 키를 떼면 남은 방향으로 이어 간다', () => {
    const input = new InputState();
    input.press('ArrowLeft');
    input.update(16);
    input.press('ArrowRight');
    expect(input.update(16)).toEqual(['right']);
    expect(count(input.update(200), 'left')).toBe(0);
    input.release('ArrowRight');
    expect(count(input.update(170), 'left')).toBe(1); // DAS는 처음부터 다시 센다
  });

  it('키를 떼면 반복이 멈춘다', () => {
    const input = new InputState();
    input.press('ArrowLeft');
    input.update(300);
    input.release('ArrowLeft');
    expect(input.update(500)).toEqual([]);
  });

  it('회전·드롭·홀드·일시정지 키를 명세대로 대응시킨다', () => {
    const input = new InputState();
    for (const code of ['ArrowUp', 'KeyZ', 'Space', 'KeyC', 'Escape', 'ArrowDown']) input.press(code);
    input.release('ArrowDown');
    expect(input.update(16)).toEqual(['rotateCW', 'rotateCCW', 'hardDrop', 'hold', 'pause', 'softDropOn', 'softDropOff']);
  });
});
