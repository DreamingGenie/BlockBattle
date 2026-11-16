import { useEffect, type ReactElement } from 'react';
import { LobbyScreen } from '../screens/LobbyScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { SoloScreen } from '../screens/SoloScreen';
import { AiScreen } from '../screens/AiScreen';
import { RoomEntryScreen } from '../screens/RoomEntryScreen';
import { RoomScreen } from '../screens/RoomScreen';
import { RecordsScreen } from '../screens/RecordsScreen';
import { RankingScreen } from '../screens/RankingScreen';
import { MatchDetailScreen } from '../screens/MatchDetailScreen';
import { MobileNoticeScreen } from '../screens/MobileNoticeScreen';
import { currentDevice, isMobileBrowser } from './device';
import { navigate, useRoute, type Route } from './router';
import { SessionProvider, useSession } from './session';

/** 화면 이름 → 화면. 게임·기록 화면은 T-19~T-22에서 채운다 */
const screens: Partial<Record<Route['name'], (route: Route) => ReactElement>> = {
  lobby: () => <LobbyScreen />,
  solo: () => <SoloScreen />,
  ai: () => <AiScreen />,
  records: () => <RecordsScreen />,
  rankings: () => <RankingScreen />,
  match: (route) => <MatchDetailScreen key={route.param} id={route.param ?? ''} />,
  room: (route) => (route.param ? <RoomScreen key={route.param} code={route.param} /> : <RoomEntryScreen />),
};

function Routed() {
  const { me, restoring } = useSession();
  const route = useRoute();

  useEffect(() => {
    // 초대 링크(#/room/CODE)로 들어왔다면 로그인 뒤에도 그 방으로 간다
    if (me && route.name === 'lobby' && window.location.hash === '') navigate('/lobby');
  }, [me, route.name]);

  if (restoring) return <main className="center muted">불러오는 중…</main>;
  if (!me) return <LoginScreen />;
  const screen = screens[route.name] ?? screens.lobby!;
  return screen(route);
}

export function App() {
  // 세션 복원·로그인 API를 부르기 전에 판단한다(REQ-021)
  if (isMobileBrowser(currentDevice())) return <MobileNoticeScreen />;
  return (
    <SessionProvider>
      <Routed />
    </SessionProvider>
  );
}
