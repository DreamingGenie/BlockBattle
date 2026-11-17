import { useEffect, useState } from 'react';
import { Icon, type IconName } from '../app/Icon';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { LiveCount } from '../app/LiveCount';
import { LogoutDialog } from './LogoutDialog';

/** 승률(%) — 경기가 없으면 null */
function winRate(wins: number, losses: number): number | null {
  const total = wins + losses;
  return total === 0 ? null : Math.round((wins / total) * 100);
}

interface PlayMode {
  path: string;
  name: string;
  desc: string;
  icon: IconName;
  tone: string;
  stat: string;
  sub: string;
  rate: number | null;
}

/** 로비: 게임 모드 세 개를 크게, 기록·랭킹·키 설정을 작게. 모드마다 내 전적 요약(REQ-003, API-006) */
export function LobbyScreen() {
  const { me, logout, reloadMe } = useSession();
  const [askLogout, setAskLogout] = useState(false);

  useEffect(() => {
    reloadMe().catch(() => {});
  }, [reloadMe]);

  if (!me) return null;
  const s = me.stats;
  const aiRate = winRate(s.aiWins, s.aiLosses);
  const friendRate = winRate(s.friendWins, s.friendLosses);
  const play: PlayMode[] = [
    {
      path: '/solo',
      name: '싱글',
      desc: '혼자 최고 점수에 도전하고 랭킹에 오릅니다',
      icon: 'blocks',
      tone: 'tone-cyan',
      stat: s.soloGames ? `최고 ${s.soloBestScore.toLocaleString()}점` : '첫 판을 기다리는 중',
      sub: `${s.soloGames.toLocaleString()}판`,
      rate: null,
    },
    {
      path: '/ai',
      name: 'vs AI',
      desc: 'VERY EASY부터 IMPOSSIBLE까지 골라 AI와 대전합니다',
      icon: 'bot',
      tone: 'tone-gold',
      stat: `${s.aiWins}승 ${s.aiLosses}패`,
      sub: aiRate == null ? '아직 대전 없음' : `승률 ${aiRate}%`,
      rate: aiRate,
    },
    {
      path: '/room',
      name: '친구 대전',
      desc: '방을 만들어 코드로 친구를 부르거나 코드로 들어갑니다',
      icon: 'users',
      tone: 'tone-pink',
      stat: `${s.friendWins}승 ${s.friendLosses}패`,
      sub: friendRate == null ? '아직 대전 없음' : `승률 ${friendRate}%`,
      rate: friendRate,
    },
  ];
  const more: { path: string; name: string; desc: string; icon: IconName }[] = [
    { path: '/records', name: '최근 경기', desc: '내 경기 기록과 상세 결과', icon: 'history' },
    { path: '/rankings', name: '랭킹', desc: '싱글 점수 · AI 난이도별 최단 승리', icon: 'trophy' },
    { path: '/keys', name: '키 설정', desc: '이동·회전·드롭·홀드 키 바꾸기', icon: 'keyboard' },
  ];

  return (
    <main className="page lobby">
      <header className="topbar">
        <span className="brand">
          <span className="brand-mark" aria-hidden="true">
            <i />
            <i />
            <i />
            <i />
          </span>
          <strong className="wordmark">블록 배틀</strong>
        </span>
        <LiveCount />
        <span className="user-box">
          <span className="avatar" aria-hidden="true">
            {me.nickname.slice(0, 1)}
          </span>
          <span className="user-name">
            <b>{me.nickname}</b>
            <span className="tag num">#{me.tag}</span>
          </span>
          <span className={`badge${me.userType === 'MEMBER' ? ' ok' : ''}`}>{me.userType === 'MEMBER' ? '회원' : '게스트'}</span>
          <button className="ghost" onClick={() => setAskLogout(true)}>
            <Icon name="logout" />
            로그아웃
          </button>
        </span>
      </header>

      <h2 className="section-label">게임 시작</h2>
      <ul className="play-modes">
        {play.map((m) => (
          <li key={m.path}>
            <button className={`play-card ${m.tone}`} onClick={() => navigate(m.path)}>
              <span className="play-icon">
                <Icon name={m.icon} size={26} />
              </span>
              <span className="play-name">{m.name}</span>
              <span className="play-desc">{m.desc}</span>
              <span className="play-stat">
                <span className="play-stat-main num">{m.stat}</span>
                <span className="meta num">{m.sub}</span>
              </span>
              {m.rate != null && (
                <span className="meter" aria-hidden="true">
                  <span style={{ width: `${m.rate}%` }} />
                </span>
              )}
              <span className="play-go">
                시작하기 <Icon name="arrow" size={15} />
              </span>
            </button>
          </li>
        ))}
      </ul>

      <h2 className="section-label">내 기록 · 설정</h2>
      <ul className="more-modes">
        {more.map((m) => (
          <li key={m.path}>
            <button className="more-card" onClick={() => navigate(m.path)}>
              <span className="more-icon">
                <Icon name={m.icon} size={20} />
              </span>
              <span className="more-text">
                <span className="more-name">{m.name}</span>
                <span className="meta">{m.desc}</span>
              </span>
              <Icon name="arrow" className="more-arrow" />
            </button>
          </li>
        ))}
      </ul>
      {askLogout && (
        <LogoutDialog
          name={`${me.nickname}#${me.tag}`}
          guest={me.userType === 'GUEST'}
          onPick={(mode) => logout(mode)}
          onClose={() => setAskLogout(false)}
        />
      )}
    </main>
  );
}
