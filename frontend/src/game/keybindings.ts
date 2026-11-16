/**
 * 키 설정(T-38). 동작마다 키를 최대 2개 둔다. 브라우저 localStorage에 저장하고, 없거나 깨졌으면 기본값을 쓴다.
 * F1~F12·Tab·Windows 키처럼 브라우저나 OS가 먼저 가져가는 기능 키는 지정할 수 없다.
 */
export type BindAction = 'left' | 'right' | 'softDrop' | 'hardDrop' | 'rotateCW' | 'rotateCCW' | 'hold' | 'pause';

export type KeyBindings = Record<BindAction, string[]>;

export const MAX_KEYS_PER_ACTION = 2;

export const BIND_ACTIONS: { action: BindAction; label: string }[] = [
  { action: 'left', label: '왼쪽 이동' },
  { action: 'right', label: '오른쪽 이동' },
  { action: 'softDrop', label: '소프트 드롭' },
  { action: 'hardDrop', label: '하드 드롭' },
  { action: 'rotateCW', label: '시계 방향 회전' },
  { action: 'rotateCCW', label: '반시계 방향 회전' },
  { action: 'hold', label: '홀드' },
  { action: 'pause', label: '일시정지(싱글)' },
];

export const DEFAULT_BINDINGS: KeyBindings = {
  left: ['ArrowLeft'],
  right: ['ArrowRight'],
  softDrop: ['ArrowDown'],
  hardDrop: ['Space'],
  rotateCW: ['ArrowUp', 'KeyX'],
  rotateCCW: ['KeyZ', 'ControlLeft'],
  hold: ['KeyC', 'ShiftLeft'],
  pause: ['Escape'],
};

const STORAGE_KEY = 'block-battle:keybindings:v1';

const RESERVED = new Set([
  'Tab', 'ContextMenu', 'PrintScreen', 'ScrollLock', 'Pause', 'NumLock', 'CapsLock', 'Power', 'Sleep', 'WakeUp', 'Fn', 'FnLock',
  'Lang1', 'Lang2', 'HangulMode', 'Hanja', 'KanaMode', 'Convert', 'NonConvert', 'Unidentified', '',
]);

/** 지정할 수 없는 키: 브라우저·OS 기능 키 */
export function isReservedKey(code: string): boolean {
  return (
    RESERVED.has(code) ||
    /^F([1-9]|1[0-9]|2[0-4])$/.test(code) ||
    /^(Meta|OS)(Left|Right)?$/.test(code) ||
    /^(Browser|Audio|Media|Launch)/.test(code)
  );
}

export function cloneBindings(b: KeyBindings): KeyBindings {
  return Object.fromEntries(Object.entries(b).map(([k, v]) => [k, [...v]])) as KeyBindings;
}

/** 저장값을 검사해 고친다: 모르는 동작·지정 불가 키·중복은 버리고, 빠진 동작은 기본값으로 */
export function sanitize(raw: unknown): KeyBindings {
  const result = cloneBindings(DEFAULT_BINDINGS);
  if (!raw || typeof raw !== 'object') return result;
  const used = new Set<string>();
  for (const { action } of BIND_ACTIONS) {
    const keys = (raw as Record<string, unknown>)[action];
    if (!Array.isArray(keys)) continue;
    result[action] = keys
      .filter((k): k is string => typeof k === 'string' && !isReservedKey(k) && !used.has(k))
      .slice(0, MAX_KEYS_PER_ACTION);
    result[action].forEach((k) => used.add(k));
  }
  // 기본값으로 채운 동작의 키가 다른 동작과 겹치면 뺀다
  for (const { action } of BIND_ACTIONS) {
    const keys = (raw as Record<string, unknown>)[action];
    if (Array.isArray(keys)) continue;
    result[action] = result[action].filter((k) => !used.has(k));
    result[action].forEach((k) => used.add(k));
  }
  return result;
}

export function loadBindings(): KeyBindings {
  try {
    const text = localStorage.getItem(STORAGE_KEY);
    return text ? sanitize(JSON.parse(text)) : cloneBindings(DEFAULT_BINDINGS);
  } catch {
    return cloneBindings(DEFAULT_BINDINGS);
  }
}

export function saveBindings(b: KeyBindings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(b));
  } catch {
    /* 저장할 수 없는 환경(사생활 보호 모드 등)이면 이번 화면에서만 쓴다 */
  }
}

/**
 * action의 slot번째 칸에 code를 지정한다. 다른 동작이 쓰던 키면 거기서 빼고 movedFrom으로 알려 준다.
 * 같은 동작의 다른 칸에 있던 키면 자리만 옮긴다.
 */
export function assignKey(
  b: KeyBindings,
  action: BindAction,
  slot: number,
  code: string,
): { bindings: KeyBindings; movedFrom: BindAction | null } {
  const next = cloneBindings(b);
  let movedFrom: BindAction | null = null;
  for (const { action: other } of BIND_ACTIONS) {
    const i = next[other].indexOf(code);
    if (i >= 0) {
      next[other].splice(i, 1);
      if (other !== action) movedFrom = other;
    }
  }
  const keys = next[action];
  if (slot < keys.length) keys[slot] = code;
  else keys.push(code);
  next[action] = keys.slice(0, MAX_KEYS_PER_ACTION);
  return { bindings: next, movedFrom };
}

export function clearKey(b: KeyBindings, action: BindAction, slot: number): KeyBindings {
  const next = cloneBindings(b);
  next[action].splice(slot, 1);
  return next;
}

const NAMED: Record<string, string> = {
  ArrowLeft: '←', ArrowRight: '→', ArrowUp: '↑', ArrowDown: '↓', Space: 'Space', Escape: 'Esc', Enter: 'Enter',
  ShiftLeft: 'L Shift', ShiftRight: 'R Shift', ControlLeft: 'L Ctrl', ControlRight: 'R Ctrl', AltLeft: 'L Alt', AltRight: 'R Alt',
  Backspace: 'Backspace', Backquote: '`', Minus: '-', Equal: '=', BracketLeft: '[', BracketRight: ']', Backslash: '\\',
  Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', Insert: 'Ins', Delete: 'Del', Home: 'Home', End: 'End',
  PageUp: 'PgUp', PageDown: 'PgDn', NumpadEnter: 'Num Enter', NumpadAdd: 'Num +', NumpadSubtract: 'Num -',
  NumpadMultiply: 'Num *', NumpadDivide: 'Num /', NumpadDecimal: 'Num .',
};

/** 화면에 보일 키 이름 */
export function keyLabel(code: string): string {
  if (NAMED[code]) return NAMED[code];
  if (code.startsWith('Key')) return code.slice(3);
  if (code.startsWith('Digit')) return code.slice(5);
  if (code.startsWith('Numpad')) return `Num ${code.slice(6)}`;
  return code;
}

/** 안내 문구용: 동작의 키들을 '↑ / X'처럼 */
export function keysText(b: KeyBindings, action: BindAction): string {
  return b[action].length ? b[action].map(keyLabel).join(' / ') : '없음';
}

/** 게임 화면 아래 조작 안내 한 줄(저장된 키 설정 기준) */
export function controlsHelp(b: KeyBindings = loadBindings()): string {
  return [
    `${keysText(b, 'left')}·${keysText(b, 'right')} 이동`,
    `${keysText(b, 'rotateCW')} 회전`,
    `${keysText(b, 'rotateCCW')} 반대 회전`,
    `${keysText(b, 'softDrop')} 소프트`,
    `${keysText(b, 'hardDrop')} 하드`,
    `${keysText(b, 'hold')} 홀드`,
  ].join(' · ');
}
