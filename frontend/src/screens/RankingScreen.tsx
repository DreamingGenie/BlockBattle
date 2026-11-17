import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { formatTime } from './RecordsScreen';
import { formatWinTime, objectParticle } from '../app/format';
import { difficultyLabel } from '../ai/types';
import { Icon } from '../app/Icon';
import { listRows, podiumSlots } from './podium';

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
  const [mine, setMine] = useState<{ rank: number; value: string } | null>(null);
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
          mine: d.me ? { rank: d.me.rank, value: `${d.me.score.toLocaleString()}점` } : null,
        };
      }
      const d = await api.get<AiRanking>('/rankings/ai', { query: { difficulty: tab, limit: 10 } });
      return {
        rows: d.top.map((e) => ({ rank: e.rank, userId: e.userId, name: `${e.nickname}#${e.tag}`, value: formatWinTime(e.durationMs), achievedAt: e.achievedAt })),
        mine: d.me ? { rank: d.me.rank, value: formatWinTime(d.me.durationMs) } : null,
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
        {rows === null ? (
          <span />
        ) : mine ? (
          <span className="chip accent" title="내 기록">
            <Icon name="trophy" />
            내 기록 <b className="num">{mine.rank}위</b>
            <span className="chip-sep" />
            <span className="num">{mine.value}</span>
          </span>
        ) : (
          <span className="chip">
            <Icon name="trophy" />
            아직 내 기록 없음
          </span>
        )}
      </header>
      <div className="rank-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={`rank-tab${tab === t ? ' active' : ''}`} onClick={() => setTab(t)}>
            {t === 'SOLO' ? '싱글' : difficultyLabel(t)}
          </button>
        ))}
      </div>
      <p className="caption-pill rank-caption">
        <Icon name="sort" />
        {tab === 'SOLO' ? (
          <span>
            <b>싱글 최고 점수</b> 높은 순
          </span>
        ) : (
          <span>
            <b>AI {difficultyLabel(tab)}</b>
            {objectParticle(tab)} 가장 빨리 이긴 시간 순
          </span>
        )}
      </p>
      {error && <p className="error">{error}</p>}
      {rows && rows.length === 0 && (
        <div className="empty-state">
          <Icon name="trophy" size={34} />
          <p>아직 기록이 없습니다.</p>
          <p className="meta">첫 번째 주인공이 되어 보세요!</p>
        </div>
      )}
      {rows && rows.length > 0 && (
        <div key={tab} className="rank-board">
          <ol className="podium">
            {podiumSlots(rows).map(({ place, row: r }, i) =>
              r ? (
                <li key={r.userId} className={`podium-slot place-${place}${r.userId === me?.userId ? ' mine' : ''}`} style={{ animationDelay: `${i * 90}ms` }}>
                  <span className="medal">{r.rank}</span>
                  <span className="podium-name">
                    {r.name}
                    {r.userId === me?.userId && <span className="badge ok">나</span>}
                  </span>
                  <span className="podium-value num">{r.value}</span>
                  <span className="meta podium-date">
                    <Icon name="calendar" size={14} />
                    {formatTime(r.achievedAt)}
                  </span>
                  <span className="podium-base" />
                </li>
              ) : (
                // 빈 자리도 칸을 차지해 1위가 늘 가운데에 온다
                <li key={`empty-${place}`} className={`podium-slot place-${place} empty`} style={{ animationDelay: `${i * 90}ms` }}>
                  <span className="medal">{place}</span>
                  <span className="podium-name">빈 자리</span>
                  <span className="meta podium-date">도전해 보세요</span>
                  <span className="podium-base" />
                </li>
              ),
            )}
          </ol>
          {rows.length > 3 && (
            <ol className="rank-list" start={4}>
              {listRows(rows).map((r, i) => (
                <li key={r.userId} className={`rank-row${r.userId === me?.userId ? ' mine' : ''}`} style={{ animationDelay: `${300 + i * 40}ms` }}>
                  <span className="rank-no num">{r.rank}</span>
                  <span className="rank-name">
                    {r.name}
                    {r.userId === me?.userId && <span className="badge ok">나</span>}
                  </span>
                  <span className="rank-value num">{r.value}</span>
                  <span className="meta rank-date">
                    <Icon name="calendar" size={14} />
                    {formatTime(r.achievedAt)}
                  </span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </main>
  );
}
