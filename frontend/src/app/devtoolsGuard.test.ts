import { afterEach, describe, expect, it, vi } from 'vitest';
import { addListener } from 'devtools-detector';
import { classifyShortcut, duringGame, installDevtoolsGuard, setGameInProgress, type ShortcutKey } from './devtoolsGuard';
import { DEFAULT_BINDINGS } from '../game/keybindings';

vi.mock('devtools-detector', () => ({ addListener: vi.fn(), removeListener: vi.fn(), launch: vi.fn(), stop: vi.fn() }));

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

// T-50: 2026-11-17 AI 대전 중 안내 화면이 보드를 덮어, 앞서던 판이 보이지 않는 채로 패배 저장됐다.
describe('경기 중 미루기', () => {
  /** 화면을 대신하는 최소 창. 안내 화면이 뜨면 body가 사유를 단 요소 하나로 바뀐다 */
  function fakeWindow() {
    const shown: string[] = [];
    const doc = {
      createElement: () => ({ setAttribute() {}, dataset: {} as Record<string, string>, textContent: '' }),
      body: { replaceChildren: (n: { dataset: Record<string, string> }) => shown.push(n.dataset.reason ?? '') },
    };
    const win = { document: doc, addEventListener() {}, removeEventListener() {} } as unknown as Window;
    return { win, shown };
  }

  function install() {
    vi.mocked(addListener).mockClear();
    const { win, shown } = fakeWindow();
    const uninstall = installDevtoolsGuard(win);
    const detect = vi.mocked(addListener).mock.calls[0]![0] as (open: boolean, d?: { checkerName: string }) => void;
    return { shown, detect, uninstall };
  }

  let cleanup: (() => void) | null = null;
  afterEach(() => {
    setGameInProgress(false);
    cleanup?.();
  });

  /** 깨지면: 경기 화면이 안내문으로 덮여 판을 잃는다(위 사고가 다시 난다) */
  it('경기 중 열림 감지는 화면을 바꾸지 않고, 경기가 끝났을 때 아직 열림이면 그때 띄운다', () => {
    const g = install();
    cleanup = g.uninstall;
    setGameInProgress(true);
    g.detect(true, { checkerName: 'performance' });
    expect(g.shown).toEqual([]);
    setGameInProgress(false);
    expect(g.shown).toEqual(['performance']);
  });

  /** 깨지면: 잠깐의 오탐이 경기 뒤 결과 화면까지 덮는다 */
  it('경기 중에 열림이 닫힘으로 돌아왔으면 경기가 끝나도 띄우지 않는다', () => {
    const g = install();
    cleanup = g.uninstall;
    setGameInProgress(true);
    g.detect(true, { checkerName: 'performance' });
    g.detect(false);
    setGameInProgress(false);
    expect(g.shown).toEqual([]);
  });

  /** 깨지면: 경기 밖(로비)에서 개발자 도구를 열어도 막히지 않는다 */
  it('경기 밖에서는 열림 감지 즉시 띄운다', () => {
    const g = install();
    cleanup = g.uninstall;
    g.detect(true, { checkerName: 'debugger-checker' });
    expect(g.shown).toEqual(['debugger-checker']);
  });

  /** 깨지면: 경기 중 F12 한 번에 판이 날아가거나, 경기 중에 개발자 도구가 그대로 열린다 */
  it('경기 중 단축키는 화면을 바꾸지 않고 기본 동작만 막는다', () => {
    expect(duringGame('block', true)).toBe('swallow');
    expect(duringGame('swallow', true)).toBe('swallow');
    expect(duringGame(null, true)).toBeNull();
    expect(duringGame('block', false)).toBe('block');
  });
});
