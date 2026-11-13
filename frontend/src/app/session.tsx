import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiClient } from '../api/client';

/** GET /users/me 응답(API-006) */
export interface Me {
  userId: number;
  nickname: string;
  tag: string;
  userType: 'GUEST' | 'MEMBER';
  stats: { soloGames: number; soloBestScore: number; aiWins: number; aiLosses: number; friendWins: number; friendLosses: number };
}

interface SessionValue {
  api: ApiClient;
  me: Me | null;
  /** 첫 진입 때 Refresh 쿠키로 로그인을 되살리는 중 */
  restoring: boolean;
  login: (nickname: string) => Promise<void>;
  logout: () => Promise<void>;
  reloadMe: () => Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useMemo(() => new ApiClient(), []);
  const [me, setMe] = useState<Me | null>(null);
  const [restoring, setRestoring] = useState(true);

  const reloadMe = useCallback(async () => {
    setMe(await api.get<Me>('/users/me'));
  }, [api]);

  useEffect(() => {
    api.onSessionLost = () => setMe(null);
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

  const login = useCallback(
    async (nickname: string) => {
      await api.guest(nickname);
      await reloadMe();
    },
    [api, reloadMe],
  );

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setMe(null);
    }
  }, [api]);

  const value = useMemo(() => ({ api, me, restoring, login, logout, reloadMe }), [api, me, restoring, login, logout, reloadMe]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('SessionProvider 밖에서 useSession을 불렀다');
  return value;
}
