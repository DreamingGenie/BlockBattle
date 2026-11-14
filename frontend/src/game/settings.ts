/**
 * 연출·효과음 설정(REQ-023). 브라우저 localStorage에 남기고, 저장소를 못 쓰면(사생활 보호 모드 등) 기본값으로 동작한다.
 * 운영체제의 '동작 줄이기'가 켜져 있으면 화면 흔들림을 기본으로 끈다.
 */
export interface FxSettings {
  muted: boolean;
  /** 0~1 */
  volume: number;
  shake: boolean;
}

const KEY = 'block-battle.fx';

export function defaultSettings(reducedMotion: boolean): FxSettings {
  return { muted: false, volume: 0.6, shake: !reducedMotion };
}

/** 저장된 값을 읽어 기본값 위에 덮는다. 형식이 틀린 값은 버린다 */
export function parseSettings(raw: string | null, reducedMotion: boolean): FxSettings {
  const base = defaultSettings(reducedMotion);
  if (!raw) return base;
  try {
    const v = JSON.parse(raw) as Partial<Record<keyof FxSettings, unknown>>;
    return {
      muted: typeof v.muted === 'boolean' ? v.muted : base.muted,
      volume: typeof v.volume === 'number' && v.volume >= 0 && v.volume <= 1 ? v.volume : base.volume,
      shake: typeof v.shake === 'boolean' ? v.shake : base.shake,
    };
  } catch {
    return base;
  }
}

function reducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

let current: FxSettings | null = null;
const listeners = new Set<(s: FxSettings) => void>();

export function getSettings(): FxSettings {
  if (!current) {
    let raw: string | null = null;
    try {
      raw = window.localStorage.getItem(KEY);
    } catch {
      raw = null;
    }
    current = parseSettings(raw, reducedMotion());
  }
  return current;
}

export function updateSettings(patch: Partial<FxSettings>): FxSettings {
  current = { ...getSettings(), ...patch };
  try {
    window.localStorage.setItem(KEY, JSON.stringify(current));
  } catch {
    // 저장하지 못해도 이번 접속 동안은 바뀐 값으로 동작한다
  }
  listeners.forEach((l) => l(current!));
  return current;
}

export function subscribeSettings(listener: (s: FxSettings) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
