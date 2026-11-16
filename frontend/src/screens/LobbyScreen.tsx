import { useEffect } from 'react';
import { navigate } from '../app/router';
import { useSession } from '../app/session';

/** 로비: 모드 목록 하나에 내 전적 요약(REQ-003, API-006)을 함께 보여 준다 */
export function LobbyScreen() {
  const { me, logout, reloadMe } = useSession();

  useEffect(() => {
    reloadMe().catch(() => {});
  }, [reloadMe]);

  if (!me) return null;
  const s = me.stats;
  const modes = [
    { path: '/solo', name: '싱글', desc: '혼자 최고 점수에 도전하고 랭킹에 오릅니다', stat: `${s.soloGames}판 · 최고 ${s.soloBestScore.toLocaleString()}점` },
    { path: '/ai', name: 'vs AI', desc: 'VERY EASY부터 IMPOSSIBLE까지 골라 AI와 대전합니다', stat: `${s.aiWins}승 ${s.aiLosses}패` },
    { path: '/room', name: '친구 대전', desc: '방을 만들어 코드로 친구를 부르거나 코드로 들어갑니다', stat: `${s.friendWins}승 ${s.friendLosses}패` },
    { path: '/records', name: '최근 경기', desc: '내가 한 경기 기록과 상세 결과를 봅니다', stat: '' },
    { path: '/rankings', name: '랭킹', desc: '싱글 점수와 AI 난이도별 최단 승리 시간 순위', stat: '' },
    { path: '/keys', name: '키 설정', desc: '이동·회전·드롭·홀드 키를 원하는 대로 바꿉니다', stat: '' },
  ];

  return (
    <main className="page">
      <header className="topbar">
        <strong className="wordmark">블록 배틀</strong>
        <span className="row">
          <span>
            {me.nickname}
            <span className="muted">#{me.tag}</span>
          </span>
          <span className="muted small">{me.userType === 'MEMBER' ? '회원' : '게스트'}</span>
          <button className="link" onClick={() => logout()}>
            로그아웃
          </button>
        </span>
      </header>
      <ul className="modes">
        {modes.map((m) => (
          <li key={m.path}>
            <button className="mode" onClick={() => navigate(m.path)}>
              <span className="mode-name">{m.name}</span>
              <span className="mode-desc muted">{m.desc}</span>
              <span className="mode-stat num">{m.stat}</span>
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
