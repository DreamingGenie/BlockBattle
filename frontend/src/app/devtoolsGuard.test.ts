import { describe, expect, it } from 'vitest';
import { classifyShortcut, type ShortcutKey } from './devtoolsGuard';
import { DEFAULT_BINDINGS } from '../game/keybindings';

const key = (code: string, mods: Partial<ShortcutKey> = {}): ShortcutKey => ({
  code,
  ctrlKey: false,
  shiftKey: false,
  altKey: false,
  metaKey: false,
  ...mods,
});

const gameKeys = new Set(Object.values(DEFAULT_BINDINGS).flat());

describe('classifyShortcut', () => {
  /** 깨지면: 기본 키(왼쪽 Ctrl 회전 + 왼쪽 Shift 홀드 + C 홀드)를 함께 누른 경기 중에 화면이 안내문으로 바뀌어 판이 날아간다 */
  it('게임 키와 겹치는 Ctrl+Shift+C는 화면을 바꾸지 않고 기본 동작만 막는다', () => {
    expect(classifyShortcut(key('KeyC', { ctrlKey: true, shiftKey: true }), gameKeys)).toBe('swallow');
  });

  /** 깨지면: 게임 조작 키(회전·홀드·방향키) 하나만 눌러도 차단 화면이 뜬다 */
  it('수식 키 없는 게임 키와 일반 입력에는 관여하지 않는다', () => {
    for (const code of gameKeys) expect(classifyShortcut(key(code), gameKeys)).toBeNull();
    expect(classifyShortcut(key('KeyC', { ctrlKey: true }), gameKeys)).toBeNull(); // 복사
    expect(classifyShortcut(key('KeyR', { ctrlKey: true, shiftKey: true }), gameKeys)).toBeNull(); // 강력 새로고침
  });

  it('F12·Ctrl+Shift+I/J·Ctrl+U·Cmd+Option+I는 안내 화면으로 바꾼다', () => {
    expect(classifyShortcut(key('F12'), gameKeys)).toBe('block');
    expect(classifyShortcut(key('KeyI', { ctrlKey: true, shiftKey: true }), gameKeys)).toBe('block');
    expect(classifyShortcut(key('KeyJ', { ctrlKey: true, shiftKey: true }), gameKeys)).toBe('block');
    expect(classifyShortcut(key('KeyU', { ctrlKey: true }), gameKeys)).toBe('block');
    expect(classifyShortcut(key('KeyI', { metaKey: true, altKey: true }), gameKeys)).toBe('block');
  });
});
