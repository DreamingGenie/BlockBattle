import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiClient } from '../api/client';
import { clearSavedAccount, loadSavedAccount, saveSavedAccount, type SavedAccount } from './savedAccount';

/** GET /users/me 응답(API-006) */
export interface Me {
  userId: number;
  nickname: string;
  tag: string;
  userType: 'GUEST' | 'MEMBER';
  stats: { soloGames: number; soloBestScore: number; aiWins: number; aiLosses: number; friendWins: number; friendLosses: number };
}

/** keep: 이 기기에서 이어 할 수 있게 Refresh 쿠키를 남긴다. full: 서버 토큰까지 지운다(공용 PC) */
export type LogoutMode = 'keep' | 'full';

interface SessionValue {
  api: ApiClient;
  me: Me | null;
  /** 첫 진입 때 Refresh 쿠키로 로그인을 되살리는 중 */
  restoring: boolean;
  /** 로그아웃하며 남겨 둔 이 기기의 계정(이어 하기 버튼) */
  saved: SavedAccount | null;
  login: (nickname: string) => Promise<void>;
  logout: (mode: LogoutMode) => Promise<void>;
  /** 남겨 둔 계정으로 다시 들어간다. 쿠키가 만료·폐기됐으면 false */
  resume: () => Promise<boolean>;
  /** 남겨 둔 계정을 이 기기에서 지운다(서버 토큰도 폐기 시도) */
  forget: () => Promise<void>;
  reloadMe: () => Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useMemo(() => new ApiClient(), []);
  const [me, setMe] = useState<Me | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [saved, setSaved] = useState<SavedAccount | null>(() => loadSavedAccount());

  const reloadMe = useCallback(async () => {
    setMe(await api.get<Me>('/users/me'));
  }, [api]);

  useEffect(() => {
    api.onSessionLost = () => setMe(null);
    // 이어 하기로 남겨 둔 계정이 있으면 사용자가 직접 '이어 하기'를 누를 때까지 자동 로그인하지 않는다
    if (loadSavedAccount()) {
      setRestoring(false);
      return;
    }
    // 새로고침: Access 토큰은 메모리에만 있으므로 Refresh 쿠키로 다시 받는다(REQ-001)
    (async () => {
      try {
        if (await api.refresh()) await reloadMe();
      } catch {
        setMe(null);
      } finally {
        setRestoring(false);
      }
    })();
  }, [api, reloadMe]);

  const forgetSaved = useCallback(() => {
    clearSavedAccount();
    setSaved(null);
  }, []);

  const login = useCallback(
    async (nickname: string) => {
      await api.guest(nickname);
      // 새 게스트의 Refresh 쿠키가 예전 것을 덮으므로 남겨 둔 계정은 더 이상 이어 할 수 없다
      forgetSaved();
      await reloadMe();
    },
    [api, reloadMe, forgetSaved],
  );

  const logout = useCallback(
    async (mode: LogoutMode) => {
      const current = me;
      if (mode === 'keep' && current && saveSavedAccount({ nickname: current.nickname, tag: current.tag, userType: current.userType })) {
        api.forgetAccess();
        setSaved(loadSavedAccount());
        setMe(null);
        return;
      }
      try {
        await api.logout();
      } finally {
        forgetSaved();
        setMe(null);
      }
    },
    [api, me, forgetSaved],
  );

  const resume = useCallback(async () => {
    if (await api.refresh()) {
      await reloadMe();
      forgetSaved();
      return true;
    }
    forgetSaved();
    return false;
  }, [api, reloadMe, forgetSaved]);

  const forget = useCallback(async () => {
    // 쿠키가 살아 있으면 서버 토큰까지 폐기해 공용 PC에 흔적을 남기지 않는다
    try {
      if (await api.refresh()) await api.logout();
    } catch {
      // 이미 만료됐으면 지울 것이 없다
    } finally {
      api.forgetAccess();
      forgetSaved();
    }
  }, [api, forgetSaved]);

  const value = useMemo(
    () => ({ api, me, restoring, saved, login, logout, resume, forget, reloadMe }),
    [api, me, restoring, saved, login, logout, resume, forget, reloadMe],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('SessionProvider 밖에서 useSession을 불렀다');
  return value;
}
