import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { formatDuration } from '../app/format';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { difficultyLabel } from '../ai/types';
import { Icon } from '../app/Icon';

/** GET /users/me/matches 응답(T-15 구현 그대로) */
interface MatchPage {
  items: {
    matchId: number;
    mode: 'SOLO' | 'AI' | 'FRIEND';
    status: 'FINISHED' | 'ABORTED';
    endReason: string | null;
    startedAt: string;
    durationMs: number | null;
    myResult: 'WIN' | 'LOSE' | 'NONE';
    myScore: number;
    opponent: { playerType: 'USER' | 'AI'; userId: number | null; nickname: string | null; tag: string | null; aiDifficulty: string | null } | null;
  }[];
  page: number;
  size: number;
  totalElements: number;
}

export const MODE_TEXT: Record<string, string> = { SOLO: '싱글', AI: 'vs AI', FRIEND: '친구 대전' };
export const RESULT_TEXT: Record<string, string> = { WIN: '승', LOSE: '패', NONE: '-' };
export const END_TEXT: Record<string, string> = {
  COMPLETED: '완료',
  TOP_OUT: '천장 도달',
  DRAW: '무승부',
  LEFT: '퇴장',
  DISCONNECT: '연결 끊김',
  EXPIRED: '만료',
};

export { formatDuration } from '../app/format';

/** '11월 14일 15:44'(올해가 아니면 '2025년 11월 14일 15:44') */
export function formatTime(iso: string | null, now: Date = new Date()): string {
  if (!iso) return '-';
  const d = new Date(iso);
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const day = `${d.getMonth() + 1}월 ${d.getDate()}일 ${hm}`;
  return d.getFullYear() === now.getFullYear() ? day : `${d.getFullYear()}년 ${day}`;
}

/** 승·패·무효 배지(기록 목록·경기 상세 공용) */
export function ResultBadge({ result }: { result: string }) {
  if (result === 'WIN') return <span className="badge ok">승</span>;
  if (result === 'LOSE') return <span className="badge danger">패</span>;
  if (result === 'ABORTED') return <span className="badge">무효</span>;
  if (result === 'NONE') return <span className="meta">-</span>;
  return <span className="badge">{RESULT_TEXT[result] ?? result}</span>;
}

/** 최근 경기 목록(FR-REC-01, REQ-015): mode 필터·페이지. 응답을 서버가 준 순서 그대로 보여 준다 */
export function RecordsScreen() {
  const { api } = useSession();
  const [mode, setMode] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20); // 요구사항 기본 20, 최대 50(FR-REC-01)
  const [data, setData] = useState<MatchPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    api
      .get<MatchPage>('/users/me/matches', { query: { mode: mode || undefined, page, size } })
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [api, mode, page, size]);

  const lastPage = data ? Math.max(0, Math.ceil(data.totalElements / data.size) - 1) : 0;

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>기록</strong>
        {data ? (
          <span className="chip">
            <Icon name="history" />
            <span>
              총 <b className="num">{data.totalElements.toLocaleString()}</b>경기
            </span>
          </span>
        ) : (
          <span />
        )}
      </header>
      <div className="records-filter">
        <div className="rank-tabs" role="tablist">
        {['', 'SOLO', 'AI', 'FRIEND'].map((m) => (
          <button
            key={m || 'ALL'}
            role="tab"
            aria-selected={mode === m}
            className={`rank-tab${mode === m ? ' active' : ''}`}
            onClick={() => {
              setMode(m);
              setPage(0);
            }}
          >
            {m ? MODE_TEXT[m] : '전체'}
          </button>
        ))}
        </div>
        <label className="meta">
          페이지당
          <select
            value={size}
            onChange={(e) => {
              setSize(Number(e.target.value));
              setPage(0);
            }}
            aria-label="페이지 크기"
          >
            {[10, 20, 50].map((n) => (
              <option key={n} value={n}>
                {n}개
              </option>
            ))}
          </select>
        </label>
      </div>
      {error && <p className="error">{error}</p>}
      {data && (
        <section className="card" style={{ marginTop: 12 }}>
          {data.items.length === 0 ? (
            <div className="empty-state">
              <Icon name="history" size={34} />
              <p>아직 끝난 경기가 없습니다.</p>
              <p className="meta">로비에서 한 판 시작해 보세요!</p>
            </div>
          ) : (
            <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>시작</th>
                  <th>모드</th>
                  <th>상대</th>
                  <th>결과</th>
                  <th>점수</th>
                  <th>시간</th>
                  <th>종료</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((m) => (
                  <tr key={m.matchId} className="clickable" onClick={() => navigate(`/matches/${m.matchId}`)}>
                    <td>
                      <span className="meta">
                        <Icon name="calendar" size={14} />
                        {formatTime(m.startedAt)}
                      </span>
                    </td>
                    <td>
                      <span className="badge">{MODE_TEXT[m.mode]}</span>
                    </td>
                    <td>
                      {m.opponent == null
                        ? '-'
                        : m.opponent.playerType === 'AI'
                          ? `AI (${difficultyLabel(m.opponent.aiDifficulty ?? '')})`
                          : `${m.opponent.nickname}#${m.opponent.tag}`}
                    </td>
                    <td>
                      <ResultBadge result={m.status === 'ABORTED' ? 'ABORTED' : m.myResult} />
                    </td>
                    <td className="num strong">{m.myScore.toLocaleString()}</td>
                    <td className="num">{formatDuration(m.durationMs)}</td>
                    <td className="cell-2">{m.endReason ? END_TEXT[m.endReason] ?? m.endReason : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
          {data.items.length > 0 && (
          <div className="pager">
            <button className="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              이전
            </button>
            <span className="meta num">
              <b>{page + 1}</b> / {lastPage + 1}
            </span>
            <button className="secondary" disabled={page >= lastPage} onClick={() => setPage((p) => p + 1)}>
              다음
            </button>
          </div>
          )}
        </section>
      )}
    </main>
  );
}
