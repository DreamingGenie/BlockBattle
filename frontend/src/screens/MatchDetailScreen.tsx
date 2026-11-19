import { difficultyLabel } from '../ai/types';
import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { END_TEXT, MODE_TEXT, ResultBadge, formatDuration, formatTime } from './RecordsScreen';
import { Icon } from '../app/Icon';

/** GET /matches/{matchId} 응답(T-15 구현 그대로). APM은 서버가 조회 때 계산한 값(POL-012) */
interface MatchDetail {
  matchId: number;
  mode: 'SOLO' | 'AI' | 'FRIEND';
  status: string;
  endReason: string | null;
  aiDifficulty: string | null;
  ranked: boolean;
  room: { roomCode: string; seriesNo: number; roundNo: number; attemptNo: number } | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  winnerUserId: number | null;
  players: {
    slot: number;
    playerType: 'USER' | 'AI';
    userId: number | null;
    nickname: string | null;
    tag: string | null;
    result: string;
    score: number;
    linesCleared: number;
    level: number;
    piecesPlaced: number;
    attackSent: number;
    attackReceived: number;
    maxCombo: number;
    apm: number;
    /** 친구 대전 등수(옛 경기·싱글·AI는 null) */
    placement?: number | null;
  }[];
}

/** 경기 상세(FR-REC-01): 참가자만 볼 수 있다 */
export function MatchDetailScreen({ id }: { id: string }) {
  const { api, me } = useSession();
  const [data, setData] = useState<MatchDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<MatchDetail>(`/matches/${id}`)
      .then(setData)
      .catch((e) => setError(errorMessage(e)));
  }, [api, id]);

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/records')}>
          ← 기록
        </button>
        <strong>경기 #{id}</strong>
        <span />
      </header>
      {error && <p className="error">{error}</p>}
      {data && (
        <section className="card stack" style={{ marginTop: 12 }}>
          <div className="row">
            <span className="badge accent">{MODE_TEXT[data.mode]}</span>
            {data.aiDifficulty && <span className="badge">{difficultyLabel(data.aiDifficulty)}</span>}
            {data.room && (
              <span className="badge">
                방 {data.room.roomCode} · 시리즈 {data.room.seriesNo} · {data.room.roundNo}라운드{data.room.attemptNo > 1 ? `(재시도 ${data.room.attemptNo})` : ''}
              </span>
            )}
            {data.status === 'ABORTED' && <span className="badge danger">무효 경기</span>}
            {!data.ranked && <span className="badge">랭킹 미반영</span>}
          </div>
          <div className="row">
            <span className="meta">
              <Icon name="calendar" size={14} />
              {formatTime(data.startedAt)} ~ {formatTime(data.endedAt)}
            </span>
            <span className="meta">
              <Icon name="clock" size={14} />
              {formatDuration(data.durationMs)}
            </span>
            <span className="meta">종료 사유 · {data.endReason ? END_TEXT[data.endReason] ?? data.endReason : '-'}</span>
          </div>
          <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>플레이어</th>
                <th>결과</th>
                <th>점수</th>
                <th>줄</th>
                <th>레벨</th>
                <th>블록</th>
                <th>공격 보냄</th>
                <th>공격 받음</th>
                <th>최대 콤보</th>
                <th>APM</th>
              </tr>
            </thead>
            <tbody>
              {data.players.map((p) => (
                <tr key={p.slot} className={p.userId === me?.userId ? 'mine' : undefined}>
                  <td>{p.playerType === 'AI' ? `AI${data.aiDifficulty ? ` (${difficultyLabel(data.aiDifficulty)})` : ''}` : `${p.nickname}#${p.tag}`}</td>
                  <td>
                    <ResultBadge result={p.result} />
                    {data.players.length > 2 && p.placement != null && <span className="badge">{p.placement}위</span>}
                  </td>
                  <td className="num strong">{p.score.toLocaleString()}</td>
                  <td>{p.linesCleared}</td>
                  <td>{p.level}</td>
                  <td>{p.piecesPlaced}</td>
                  <td>{p.attackSent}</td>
                  <td>{p.attackReceived}</td>
                  <td>{p.maxCombo}</td>
                  <td>{p.apm}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </section>
      )}
    </main>
  );
}
