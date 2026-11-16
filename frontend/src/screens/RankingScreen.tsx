import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { formatTime } from './RecordsScreen';
import { formatWinTime, objectParticle } from '../app/format';
import { difficultyLabel } from '../ai/types';

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

const TABS = ['SOLO', 'VERY_EASY', 'EASY', 'NORMAL', 'HARD', 'IMPOSSIBLE'] as const;
type Tab = (typeof TABS)[number];


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
      <div className="rank-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={`rank-tab${tab === t ? ' active' : ''}`} onClick={() => setTab(t)}>
            {t === 'SOLO' ? '싱글' : difficultyLabel(t)}
          </button>
        ))}
      </div>
      <p className="muted small rank-caption">{tab === 'SOLO' ? '싱글 최고 점수 순' : `AI ${difficultyLabel(tab)}${objectParticle(tab)} 가장 빨리 이긴 시간 순`}</p>
      {error && <p className="error">{error}</p>}
      {rows && rows.length === 0 && <p className="muted rank-empty">아직 기록이 없습니다. 첫 번째 주인공이 되어 보세요!</p>}
      {rows && rows.length > 0 && (
        <div key={tab} className="rank-board">
          <ol className="podium">
            {[1, 0, 2].map((i) => {
              const r = rows[i];
              if (!r) return <li key={i} className="podium-slot empty" />;
              return (
                <li key={r.userId} className={`podium-slot place-${r.rank}${r.userId === me?.userId ? ' mine' : ''}`} style={{ animationDelay: `${i * 90}ms` }}>
                  <span className="medal">{r.rank}</span>
                  <span className="podium-name">{r.name}</span>
                  <span className="podium-value num">{r.value}</span>
                  <span className="podium-date muted small">{formatTime(r.achievedAt)}</span>
                  <span className="podium-base" />
                </li>
              );
            })}
          </ol>
          {rows.length > 3 && (
            <ol className="rank-list" start={4}>
              {rows.slice(3).map((r, i) => (
                <li key={r.userId} className={`rank-row${r.userId === me?.userId ? ' mine' : ''}`} style={{ animationDelay: `${300 + i * 40}ms` }}>
                  <span className="rank-no num">{r.rank}</span>
                  <span className="rank-name">{r.name}</span>
                  <span className="rank-value num">{r.value}</span>
                  <span className="rank-date muted small">{formatTime(r.achievedAt)}</span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </main>
  );
}
