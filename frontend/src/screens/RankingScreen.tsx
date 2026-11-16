import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { formatTime } from './RecordsScreen';

/** GET /rankings/solo (API-010) */
interface SoloRanking {
  top: { rank: number; userId: number; nickname: string; tag: string; score: number; achievedAt: string }[];
  me: { rank: number; score: number } | null;
}

/** GET /rankings/ai (T-35): 난이도별 AI 최단 승리 시간 */
interface AiRanking {
  difficulty: string;
  top: { rank: number; userId: number; nickname: string; tag: string; durationMs: number; achievedAt: string }[];
  me: { rank: number; durationMs: number } | null;
}

const TABS = ['SOLO', 'EASY', 'NORMAL', 'HARD', 'IMPOSSIBLE'] as const;
type Tab = (typeof TABS)[number];

/** 승리 시간 '1:23.4' */
export function formatWinTime(ms: number): string {
  const tenths = Math.floor(ms / 100);
  const s = Math.floor(tenths / 10);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}.${tenths % 10}`;
}

interface Row {
  rank: number;
  userId: number;
  name: string;
  value: string;
  achievedAt: string;
}

/** 랭킹(T-35): 싱글은 최고 점수, vs AI는 난이도별로 가장 빨리 이긴 시간. 상위 10 */
export function RankingScreen() {
  const { api, me } = useSession();
  const [tab, setTab] = useState<Tab>('SOLO');
  const [rows, setRows] = useState<Row[] | null>(null);
  const [mine, setMine] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setRows(null);
    setMine(null);
    setError(null);
    const load = async () => {
      if (tab === 'SOLO') {
        const d = await api.get<SoloRanking>('/rankings/solo', { query: { limit: 10 } });
        return {
          rows: d.top.map((e) => ({ rank: e.rank, userId: e.userId, name: `${e.nickname}#${e.tag}`, value: `${e.score.toLocaleString()}점`, achievedAt: e.achievedAt })),
          mine: d.me ? `${d.me.rank}위 · ${d.me.score.toLocaleString()}점` : null,
        };
      }
      const d = await api.get<AiRanking>('/rankings/ai', { query: { difficulty: tab, limit: 10 } });
      return {
        rows: d.top.map((e) => ({ rank: e.rank, userId: e.userId, name: `${e.nickname}#${e.tag}`, value: formatWinTime(e.durationMs), achievedAt: e.achievedAt })),
        mine: d.me ? `${d.me.rank}위 · ${formatWinTime(d.me.durationMs)}` : null,
      };
    };
    load()
      .then((r) => {
        if (cancelled) return;
        setRows(r.rows);
        setMine(r.mine);
      })
      .catch((e) => !cancelled && setError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [api, tab]);

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>랭킹</strong>
        <span className="muted">{mine ? `내 기록 ${mine}` : ''}</span>
      </header>
      <div className="row" style={{ marginTop: 12 }}>
        {TABS.map((t) => (
          <button key={t} className={tab === t ? undefined : 'secondary'} onClick={() => setTab(t)}>
            {t === 'SOLO' ? '싱글' : `AI ${t}`}
          </button>
        ))}
      </div>
      <p className="muted small">{tab === 'SOLO' ? '싱글 최고 점수 순입니다.' : `AI ${tab}를 가장 빨리 이긴 시간 순입니다.`}</p>
      {error && <p className="error">{error}</p>}
      {rows && (
        <section className="card">
          {rows.length === 0 ? (
            <p className="muted">아직 기록이 없습니다.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>순위</th>
                    <th>플레이어</th>
                    <th>{tab === 'SOLO' ? '점수' : '승리 시간'}</th>
                    <th>달성</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.userId} style={r.userId === me?.userId ? { fontWeight: 700 } : undefined}>
                      <td className="num">{r.rank}</td>
                      <td>{r.name}</td>
                      <td className="num">{r.value}</td>
                      <td>{formatTime(r.achievedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </main>
  );
}
