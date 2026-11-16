import { controlsHelp, keysText, loadBindings } from '../game/keybindings';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { Game } from '../engine';
import { BoardCanvas, type BoardCanvasHandle } from '../game/BoardCanvas';
import { applyActions, inputSound, useKeyboard } from '../game/controls';
import { FxControls } from '../game/FxControls';
import { InputState } from '../game/input';
import { BotNotice, ResultModal } from '../game/ResultModal';
import { soloResultBody } from '../game/result';
import { useGameLoop } from '../game/useGameLoop';

interface Started {
  matchId: number;
  seed: number;
}

interface Saved {
  matchId?: number;
  botSuspected?: boolean;
  ranked: boolean;
  newBest: boolean;
  soloRank: number | null;
}

interface Ranking {
  top: { rank: number; userId: number; nickname: string; tag: string; score: number }[];
  me: { rank: number; score: number } | null;
}

type Phase = 'starting' | 'playing' | 'paused' | 'saving' | 'result' | 'error';

/** 싱글(FR-SOL-01·02, REQ-004·005): 경기 시작 → 플레이 → topout 때 결과 한 번 저장 → 랭킹 */
export function SoloScreen() {
  const { api, me, reloadMe } = useSession();
  const [phase, setPhase] = useState<Phase>('starting');
  const [error, setError] = useState<string | null>(null);
  const [hud, setHud] = useState({ score: 0, lines: 0, level: 1 });
  const [saved, setSaved] = useState<Saved | null>(null);
  const [ranking, setRanking] = useState<Ranking | null>(null);
  const [round, setRound] = useState(0);
  const match = useRef<Started | null>(null);
  const game = useRef<Game | null>(null);
  const playMs = useRef(0);
  const board = useRef<BoardCanvasHandle>(null);
  const input = useMemo(() => new InputState(), []);

  // 경기 시작(API-008). 개발 모드(StrictMode)에서 효과가 두 번 돌아도 판마다 한 번만 보낸다
  const requested = useRef(-1);
  const currentRound = useRef(round);
  currentRound.current = round;
  useEffect(() => {
    if (requested.current === round) return;
    requested.current = round;
    setPhase('starting');
    setSaved(null);
    setRanking(null);
    (async () => {
      try {
        const started = await api.post<Started>('/matches', { mode: 'SOLO' }, { retryUnavailable: true });
        if (currentRound.current !== round) return;
        match.current = started;
        game.current = new Game(started.seed);
        board.current?.reset();
        playMs.current = 0;
        input.resetCounters();
        setHud({ score: 0, lines: 0, level: 1 });
        setPhase('playing');
      } catch (e) {
        setError(errorMessage(e));
        setPhase('error');
      }
    })();
  }, [api, round]);

  const finish = useCallback(async () => {
    const g = game.current;
    const m = match.current;
    if (!g || !m) return;
    setPhase('saving');
    try {
      // 결과는 경기당 한 번만 저장한다(API-009, 두 번째는 서버가 거부)
      setSaved(await api.post<Saved>(`/matches/${m.matchId}/result`, soloResultBody(g, playMs.current, input.presses, input.stats.summary(input.presses, g.piecesPlaced)), { retryUnavailable: true }));
      setRanking(await api.get<Ranking>('/rankings/solo'));
      reloadMe().catch(() => {});
      setPhase('result');
    } catch (e) {
      setError(errorMessage(e));
      setPhase('error');
    }
  }, [api, reloadMe]);

  useKeyboard(input, phase === 'playing' || phase === 'paused');

  useGameLoop((dt) => {
    const g = game.current;
    if (!g) return;
    const actions = input.update(dt);
    if (phase === 'paused') {
      if (actions.includes('pause')) setPhase('playing');
    } else if (phase === 'playing') {
      const { pause } = applyActions(g, actions, inputSound);
      if (pause) {
        setPhase('paused');
      } else {
        g.tick(dt);
        playMs.current += dt; // 일시정지 시간은 플레이 시간에 넣지 않는다
        const events = g.drainEvents();
        if (events.some((e) => e.type === 'lock')) input.stats.lock(performance.now());
        for (const e of events) {
          if (e.type === 'lock') board.current?.lock(e, g.combo);
          else board.current?.topout();
        }
        if (events.some((e) => e.type === 'lock')) setHud({ score: g.score, lines: g.linesCleared, level: g.level });
        if (events.some((e) => e.type === 'topout')) finish();
      }
    }
    board.current?.draw({
      board: g.board,
      current: g.current,
      hold: g.hold,
      holdUsed: g.holdUsed,
      next: g.preview(),
      pendingLines: 0,
    });
  }, phase !== 'starting' && phase !== 'error');

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>싱글</strong>
        <span className="row">
          <FxControls />
          <span className="muted small">{keysText(loadBindings(), 'pause')} 일시정지</span>
        </span>
      </header>
      <section className="play">
        <div className="board-wrap">
          <BoardCanvas ref={board} label="내 보드" sound />
          {phase === 'starting' && <div className="overlay">경기를 준비하는 중…</div>}
          {phase === 'paused' && (
            <div className="overlay">
              <div className="stack">
                <h2>일시정지</h2>
                <p className="muted">{keysText(loadBindings(), 'pause')} 키를 다시 누르면 이어서 합니다.</p>
              </div>
            </div>
          )}
          {phase === 'saving' && <div className="overlay">결과를 저장하는 중…</div>}
          {phase === 'error' && (
            <div className="overlay">
              <div className="stack">
                <p className="error">{error}</p>
                <button onClick={() => navigate('/lobby')}>로비로</button>
              </div>
            </div>
          )}
        </div>
        <aside className="card hud">
          <div>
            <span className="muted">점수</span>
            <strong>{hud.score.toLocaleString()}</strong>
          </div>
          <div>
            <span className="muted">줄</span>
            <strong>{hud.lines}</strong>
          </div>
          <div>
            <span className="muted">레벨</span>
            <strong>{hud.level}</strong>
          </div>
          <p className="muted keys">
            {controlsHelp()}
          </p>
        </aside>
      </section>
      {phase === 'result' && saved?.botSuspected && (
        <BotNotice matchId={saved.matchId ?? match.current?.matchId ?? 0} onRetry={() => setRound((r) => r + 1)} />
      )}
      {phase === 'result' && saved && !saved.botSuspected && (
        <ResultModal tone={saved.newBest ? 'win' : 'over'} title={saved.newBest ? 'NEW RECORD' : 'GAME OVER'}>
          <p className="result-score num">{game.current?.score.toLocaleString()}점</p>
          <p>
            {saved.newBest ? <span className="ok">최고 점수 갱신!</span> : '최고 점수는 그대로입니다.'}{' '}
            {saved.ranked ? '' : <span className="muted">(점수 검사에 걸려 랭킹에는 반영되지 않았습니다)</span>}
          </p>
          {ranking && (
            <table>
              <thead>
                <tr>
                  <th>순위</th>
                  <th>닉네임</th>
                  <th>점수</th>
                </tr>
              </thead>
              <tbody>
                {ranking.top.map((r) => (
                  <tr key={r.userId} className={r.userId === me?.userId ? 'mine' : undefined}>
                    <td>{r.rank}</td>
                    <td>
                      {r.nickname}#{r.tag}
                    </td>
                    <td>{r.score.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="muted">
            내 순위: {ranking?.me ? `${ranking.me.rank}위 · ${ranking.me.score.toLocaleString()}점` : '아직 기록 없음'}
          </p>
          <div className="row">
            <button onClick={() => setRound((r) => r + 1)}>다시 하기</button>
            <button className="secondary" onClick={() => navigate('/lobby')}>
              로비로
            </button>
          </div>
        </ResultModal>
      )}
    </main>
  );
}
