import { describe, expect, it } from 'vitest';
import { parseSettings } from './settings';

/** REQ-023 설정 저장. 깨지면: 동작 줄이기 사용자에게 화면이 흔들리거나, 저장값이 깨졌을 때 게임 화면이 오류로 멈춘다 */
describe('parseSettings', () => {
  it('저장값이 없으면 기본값이고, 동작 줄이기면 흔들림이 꺼진다', () => {
    expect(parseSettings(null, false)).toEqual({ muted: false, volume: 0.6, shake: true });
    expect(parseSettings(null, true).shake).toBe(false);
  });

  it('저장값을 덮되, 형식이 틀린 값은 기본값으로 둔다', () => {
    expect(parseSettings('{"muted":true,"volume":0.2,"shake":false}', false)).toEqual({ muted: true, volume: 0.2, shake: false });
    expect(parseSettings('{"volume":7,"muted":"yes"}', false)).toEqual({ muted: false, volume: 0.6, shake: true });
    expect(parseSettings('not json', true)).toEqual({ muted: false, volume: 0.6, shake: false });
  });
});
