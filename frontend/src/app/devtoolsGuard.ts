import { loadBindings } from '../game/keybindings';

/**
 * 개발자 도구 단축키를 가로채 안내 화면으로 바꾼다(운영 빌드 전용).
 * 화면을 가리는 장치일 뿐 보안 대책이 아니다. 브라우저 메뉴로 여는 것과 브라우저 밖 요청(curl 등)은 막지 못하므로
 * 점수·대전 검증은 서버가 한다(ResultLimits, StompAuthInterceptor).
 */
export type GuardAction = 'block' | 'swallow' | null;

export interface ShortcutKey {
  code: string;
  ctrlKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  metaKey: boolean;
}

/**
 * block: 안내 화면으로 바꾼다. swallow: 브라우저 기본 동작만 막는다. null: 관여하지 않는다.
 * 게임 키와 겹치는 조합(기본 설정의 왼쪽 Ctrl 회전 + 왼쪽 Shift 홀드 + C 홀드 = Ctrl+Shift+C)은 경기 중에 눌릴 수 있어
 * 화면을 바꾸지 않고 기본 동작만 막는다.
 */
export function classifyShortcut(e: ShortcutKey, gameKeys: ReadonlySet<string>): GuardAction {
  if (e.code === 'F12') return 'block';
  const ctrl = e.ctrlKey || e.metaKey;
  // Windows·Linux: Ctrl+Shift+I/J/C, macOS: Cmd+Option+I/J/C
  const devtoolsCombo = (e.ctrlKey && e.shiftKey) || (e.metaKey && e.altKey);
  if (devtoolsCombo && (e.code === 'KeyI' || e.code === 'KeyJ' || e.code === 'KeyC')) {
    return gameKeys.has(e.code) ? 'swallow' : 'block';
  }
  if (ctrl && !e.shiftKey && !e.altKey && e.code === 'KeyU') return 'block'; // 페이지 소스 보기
  return null;
}

const NOTICE_STYLE =
  'position:fixed;top:0;left:0;width:100%;height:100%;background:#000;color:#fff;z-index:999999;' +
  'display:flex;align-items:center;justify-content:center;text-align:center;font-size:24px;';

function showNotice(doc: Document): void {
  const notice = doc.createElement('div');
  notice.setAttribute('style', NOTICE_STYLE);
  notice.textContent = '개발자 도구 사용이 금지되어 있습니다.';
  doc.body.replaceChildren(notice);
}

/** 단축키·우클릭 메뉴를 가로챈다. 해제 함수를 돌려준다. 한 번 안내 화면이 뜨면 새로고침해야 돌아온다 */
export function installDevtoolsGuard(win: Window = window): () => void {
  const onKeyDown = (e: KeyboardEvent) => {
    const gameKeys = new Set(Object.values(loadBindings()).flat());
    const action = classifyShortcut(e, gameKeys);
    if (!action) return;
    e.preventDefault(); // 전파는 막지 않는다: swallow일 때 게임이 그 키를 받아야 한다
    if (action === 'block') showNotice(win.document);
  };
  const onContextMenu = (e: MouseEvent) => e.preventDefault();
  win.addEventListener('keydown', onKeyDown, true);
  win.addEventListener('contextmenu', onContextMenu, true);
  return () => {
    win.removeEventListener('keydown', onKeyDown, true);
    win.removeEventListener('contextmenu', onContextMenu, true);
  };
}
