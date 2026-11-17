import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { HEARTBEAT_MS, type PresenceCounts } from './presence';
import { useSession } from './session';
import { setGameInProgress } from './devtoolsGuard';

interface PresenceValue {
  counts: PresenceCounts | null;
  /** 마지막 heartbeat가 실패했다 */
  failed: boolean;
  /** 경기 화면이 경기 중이면 +1, 끝나면 -1 */
  mark: (delta: 1 | -1) => void;
}

const PresenceContext = createContext<PresenceValue | null>(null);

/**
 * 로그인한 동안 20초마다 heartbeat를 보내 접속·플레이 중 인원을 받는다(T-49).
 * 경기를 시작하거나 끝내면 기다리지 않고 바로 한 번 보내 플레이 중 수가 곧장 바뀌게 한다.
 */
export function PresenceProvider({ children }: { children: ReactNode }) {
  const { api, me } = useSession();
  const [counts, setCounts] = useState<PresenceCounts | null>(null);
  const [failed, setFailed] = useState(false);
  const [games, setGames] = useState(0);
  const playing = games > 0;
  const playingRef = useRef(playing);
  playingRef.current = playing;
  const loggedIn = me !== null;

  const beat = useCallback(async () => {
    try {
      setCounts(await api.post<PresenceCounts>('/presence/heartbeat', { playing: playingRef.current }));
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, [api]);

  useEffect(() => {
    if (!loggedIn) {
      setCounts(null);
      setFailed(false);
      return;
    }
    const timer = window.setInterval(beat, HEARTBEAT_MS);
    return () => window.clearInterval(timer);
  }, [loggedIn, beat]);

  // 개발자 도구 안내 화면을 경기 중에는 미룬다(T-50). 로그인과 상관없이 경기 화면 기준이다
  useEffect(() => setGameInProgress(playing), [playing]);

  // 로그인 직후와 경기 시작·종료 때 바로 보낸다
  useEffect(() => {
    if (loggedIn) void beat();
  }, [loggedIn, playing, beat]);

  const mark = useCallback((delta: 1 | -1) => setGames((n) => Math.max(0, n + delta)), []);
  const value = useMemo(() => ({ counts, failed, mark }), [counts, failed, mark]);
  return <PresenceContext.Provider value={value}>{children}</PresenceContext.Provider>;
}

export function usePresence(): PresenceValue {
  const value = useContext(PresenceContext);
  if (!value) throw new Error('PresenceProvider 밖에서 usePresence를 불렀다');
  return value;
}

/** 경기 화면에서 부른다. active인 동안 이 사용자를 '플레이 중'으로 센다 */
export function usePlaying(active: boolean): void {
  const { mark } = usePresence();
  useEffect(() => {
    if (!active) return;
    mark(1);
    return () => mark(-1);
  }, [active, mark]);
}
