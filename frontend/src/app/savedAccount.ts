/**
 * '이 기기에서 이어 하기'(게스트 복구). 로그아웃할 때 서버 Refresh 토큰을 지우지 않고 HttpOnly 쿠키에 남겨 두면,
 * 같은 브라우저에서 /auth/refresh 한 번으로 그 계정에 다시 들어갈 수 있다.
 * 로컬 저장소에는 버튼에 보일 이름표(닉네임·태그)만 둔다. 비밀값은 여전히 스크립트가 못 읽는 쿠키에만 있다.
 * 이 값이 있으면 '잠시 나간 상태'라 첫 진입 때 자동 로그인하지 않는다.
 */
export interface SavedAccount {
  nickname: string;
  tag: string;
  userType: 'GUEST' | 'MEMBER';
}

const KEY = 'bb.savedAccount';

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function defaultStore(): Store | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null; // 사생활 보호 모드 등에서 접근 자체가 막힌 경우
  }
}

export function loadSavedAccount(store: Store | null = defaultStore()): SavedAccount | null {
  try {
    const raw = store?.getItem(KEY);
    if (!raw) return null;
    const v = JSON.parse(raw) as Partial<SavedAccount>;
    if (typeof v.nickname !== 'string' || typeof v.tag !== 'string' || !v.nickname || !v.tag) return null;
    return { nickname: v.nickname, tag: v.tag, userType: v.userType === 'MEMBER' ? 'MEMBER' : 'GUEST' };
  } catch {
    return null;
  }
}

/** 저장하지 못하면 false — 이어 하기 버튼을 못 띄우므로 부르는 쪽이 완전 로그아웃으로 처리한다 */
export function saveSavedAccount(account: SavedAccount, store: Store | null = defaultStore()): boolean {
  try {
    if (!store) return false;
    store.setItem(KEY, JSON.stringify(account));
    return true;
  } catch {
    return false;
  }
}

export function clearSavedAccount(store: Store | null = defaultStore()): void {
  try {
    store?.removeItem(KEY);
  } catch {
    // 지우지 못해도 다음 이어 하기에서 쿠키가 없으면 다시 지운다
  }
}
