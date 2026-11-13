import { useEffect } from 'react';
import { navigate } from '../app/router';
import { useSession } from '../app/session';

/** 로비: 내 정보·전적 요약(REQ-003, API-006)과 모드 메뉴 */
export function LobbyScreen() {
  const { me, logout, reloadMe } = useSession();

  useEffect(() => {
    reloadMe().catch(() => {});
  }, [reloadMe]);

  if (!me) return null;
  const s = me.stats;
  return (
    <main className="page">
      <header className="topbar">
        <strong>블록 배틀</strong>
        <span>
          {me.nickname}#{me.tag} <span className="badge">{me.userType === 'MEMBER' ? '회원' : '게스트'}</span>
        </span>
        <button className="link" onClick={() => logout()}>
          로그아웃
        </button>
      </header>
      <section className="grid stats">
        <div className="card">
          <h3>싱글</h3>
          <p>
            {s.soloGames}판 · 최고 {s.soloBestScore.toLocaleString()}점
          </p>
        </div>
        <div className="card">
          <h3>vs AI</h3>
          <p>
            {s.aiWins}승 {s.aiLosses}패
          </p>
        </div>
        <div className="card">
          <h3>친구 대전</h3>
          <p>
            {s.friendWins}승 {s.friendLosses}패
          </p>
        </div>
      </section>
      <section className="grid menu">
        <button className="big" onClick={() => navigate('/solo')}>
          싱글 플레이
          <small>최고 점수에 도전하고 랭킹에 올라가기</small>
        </button>
        <button className="big" onClick={() => navigate('/ai')}>
          vs AI
          <small>EASY · NORMAL · HARD</small>
        </button>
        <button className="big" onClick={() => navigate('/room')}>
          친구 대전
          <small>방을 만들거나 코드로 들어가기</small>
        </button>
        <button className="big" onClick={() => navigate('/records')}>
          기록
          <small>최근 경기와 상세 성적</small>
        </button>
      </section>
    </main>
  );
}
