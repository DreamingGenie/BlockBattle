import { addListener, launch, removeListener, stop } from 'devtools-detector';
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

/** reason: 무엇이 걸렸는지(단축키면 'shortcut', 열림 감지면 검사기 이름). 오탐을 조사할 때 요소 속성으로 본다 */
function showNotice(doc: Document, reason: string): void {
  const notice = doc.createElement('div');
  notice.setAttribute('style', NOTICE_STYLE);
  notice.dataset.reason = reason;
  notice.textContent = '개발자 도구 사용이 금지되어 있습니다.';
  doc.body.replaceChildren(notice);
}

/**
 * 경기 진행 여부. 경기 중에는 안내 화면을 띄우지 않는다(T-50): 화면이 덮여도 게임은 뒤에서 계속 돌아
 * 보이지 않는 채로 판을 잃는다. 감지기는 잠깐의 멈춤을 열림으로 잘못 볼 수 있어서 경기가 끝날 때 다시 본다
 */
let gameInProgress = false;
let onGameEnd: (() => void) | null = null;

/** 경기 화면이 진행 중이면 true. 진행 중에서 아님으로 바뀔 때 미뤄 둔 열림을 처리한다 */
export function setGameInProgress(value: boolean): void {
  const ended = gameInProgress && !value;
  gameInProgress = value;
  if (ended) onGameEnd?.();
}

/** 경기 중이면 'block'을 'swallow'로 낮춘다: 개발자 도구가 열리지 않게만 막고 화면은 그대로 둔다 */
export function duringGame(action: GuardAction, playing: boolean): GuardAction {
  return playing && action === 'block' ? 'swallow' : action;
}

/**
 * 단축키·우클릭 메뉴를 가로채고, 브라우저 메뉴로 연 개발자 도구도 devtools-detector로 감지한다.
 * 해제 함수를 돌려준다. 한 번 안내 화면이 뜨면 새로고침해야 돌아온다
 */
export function installDevtoolsGuard(win: Window = window): () => void {
  // 감지기의 지금 상태. 경기 중에 열림이 오면 기억만 하고, 경기가 끝났을 때 아직 열림이면 그때 띄운다
  let open = false;
  let openReason = 'detector';
  const onOpen = (isOpen: boolean, detail?: { checkerName: string }) => {
    open = isOpen;
    if (!isOpen) return;
    openReason = detail?.checkerName ?? 'detector';
    if (!gameInProgress) showNotice(win.document, openReason);
  };
  onGameEnd = () => {
    if (open) showNotice(win.document, openReason);
  };
  addListener(onOpen);
  launch();
  const onKeyDown = (e: KeyboardEvent) => {
    const gameKeys = new Set(Object.values(loadBindings()).flat());
    const action = duringGame(classifyShortcut(e, gameKeys), gameInProgress);
    if (!action) return;
    e.preventDefault(); // 전파는 막지 않는다: swallow일 때 게임이 그 키를 받아야 한다
    if (action === 'block') showNotice(win.document, 'shortcut');
  };
  const onContextMenu = (e: MouseEvent) => e.preventDefault();
  win.addEventListener('keydown', onKeyDown, true);
  win.addEventListener('contextmenu', onContextMenu, true);
  return () => {
    win.removeEventListener('keydown', onKeyDown, true);
    win.removeEventListener('contextmenu', onContextMenu, true);
    removeListener(onOpen);
    stop();
    onGameEnd = null;
  };
}
