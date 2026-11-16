import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { AiController, requestServerMove, type Difficulty } from '../ai';
import { Game } from '../engine';
import { BoardCanvas, type BoardCanvasHandle } from '../game/BoardCanvas';
import { applyActions, inputSound, useKeyboard } from '../game/controls';
import { FxControls } from '../game/FxControls';
import { sfx } from '../game/sound';
import { InputState } from '../game/input';
import { revealOnMount } from '../game/reveal';
import { useGameLoop } from '../game/useGameLoop';
import { aiResultBody, forwardAttacks } from '../game/versus';

interface Started {
  matchId: number;
  seed: number;
}

type Phase = 'choose' | 'starting' | 'playing' | 'saving' | 'result' | 'error';

const DIFFICULTIES: { value: Difficulty; label: string; note: string }[] = [
  { value: 'EASY', label: 'EASY', note: '가끔 실수하고, 블록 하나에 2초씩 씁니다' },
  { value: 'NORMAL', label: 'NORMAL', note: '한 수만 보고, 블록 하나에 1.5초씩 씁니다' },
  { value: 'HARD', label: 'HARD', note: '다음 블록까지 보고, 블록 하나에 1초씩 씁니다' },
  { value: 'IMPOSSIBLE', label: 'IMPOSSIBLE', note: 'T-스핀 자리까지 찾고 쉬지 않고 놓습니다' },
];

/** vs AI(FR-AI-01~03, REQ-007·008): 같은 시드의 두 엔진, AI는 서버 수 + 150ms 초과·오류 시 규칙 AI */
export function AiScreen() {
  const { api, reloadMe } = useSession();
  const [phase, setPhase] = useState<Phase>('choose');
  const [difficulty, setDifficulty] = useState<Difficulty>('NORMAL');
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0);
  const [hud, setHud] = useState({ me: 0, ai: 0, server: 0, fallback: 0 });
  const [outcome, setOutcome] = useState<{ win: boolean; ranked: boolean } | null>(null);
  const match = useRef<Started | null>(null);
  const me = useRef<Game | null>(null);
  const ai = useRef<Game | null>(null);
  const controller = useRef<AiController | null>(null);
  const playMs = useRef(0);
  const ended = useRef(false);
  const myBoard = useRef<BoardCanvasHandle>(null);
  const aiBoard = useRef<BoardCanvasHandle>(null);
  const input = useMemo(() => new InputState(), []);
  const requested = useRef(-1);
  const currentRound = useRef(round);
  currentRound.current = round;

  // 경기 시작(API-008). 판(round)마다 한 번만 보낸다
  useEffect(() => {
    if (round === 0 || requested.current === round) return;
    requested.current = round;
    setPhase('starting');
    setOutcome(null);
    (async () => {
      try {
        const started = await api.post<Started>('/matches', { mode: 'AI', aiDifficulty: difficulty });
        if (currentRound.current !== round) return;
        match.current = started;
        me.current = new Game(started.seed);
        ai.current = new Game(started.seed); // AI 보드도 같은 시드(FR-AI-03)
        myBoard.current?.reset();
        aiBoard.current?.reset();
        playMs.current = 0;
        ended.current = false;
        controller.current?.stop();
        controller.current = new AiController(ai.current, difficulty, {
          // AI 서버 POST /move(AI Base URL /ai/v1 뒤). 150ms 초과·오류면 규칙 AI, 늦은 응답은 버린다(T-8)
          requestMove: (body) => requestServerMove(body, { fetch: (u, i) => fetch(u, i), baseUrl: '/ai/v1', accessToken: api.token ?? '' }),
          rand: Math.random,
        });
        controller.current.start();
        setHud({ me: 0, ai: 0, server: 0, fallback: 0 });
        setPhase('playing');
      } catch (e) {
        setError(errorMessage(e));
        setPhase('error');
      }
    })();
  }, [api, round, difficulty]);

  useEffect(() => () => controller.current?.stop(), []);

  const finish = useCallback(
    async (win: boolean) => {
      if (ended.current) return;
      ended.current = true;
      controller.current?.stop();
      const m = match.current;
      if (!m || !me.current || !ai.current) return;
      setPhase('saving');
      try {
        const saved = await api.post<{ ranked: boolean }>(`/matches/${m.matchId}/result`, aiResultBody(me.current, ai.current, win, playMs.current));
        setOutcome({ win, ranked: saved.ranked });
        reloadMe().catch(() => {});
        setPhase('result');
      } catch (e) {
        setError(errorMessage(e));
        setPhase('error');
      }
    },
    [api, reloadMe],
  );

  useKeyboard(input, phase === 'playing');

  useGameLoop((dt) => {
    const g = me.current;
    const a = ai.current;
    if (!g || !a) return;
    if (phase === 'playing') {
      applyActions(g, input.update(dt).filter((x) => x !== 'pause'), inputSound); // vs AI는 일시정지 없음(GR-02)
      g.tick(dt);
      playMs.current += dt;
      const myEvents = g.drainEvents();
      const aiEvents = a.drainEvents();
      forwardAttacks(myEvents, a);
      forwardAttacks(aiEvents, g);
      // 연출(REQ-023): 내 보드는 전부, AI 보드는 게임 오버 회색 처리만
      for (const e of myEvents) {
        if (e.type === 'lock') myBoard.current?.lock(e, g.combo);
        else myBoard.current?.topout();
      }
      if (aiEvents.some((e) => e.type === 'lock' && e.result.sent > 0)) myBoard.current?.garbage();
      if (aiEvents.some((e) => e.type === 'topout') && !myEvents.some((e) => e.type === 'topout')) {
        aiBoard.current?.topout();
        sfx.win();
      }
      if (myEvents.length || aiEvents.length) {
        const decisions = controller.current?.decisions ?? [];
        setHud({
          me: g.score,
          ai: a.score,
          server: decisions.filter((d) => d.source === 'server').length,
          fallback: decisions.filter((d) => d.source === 'fallback').length,
        });
      }
      // 먼저 topout한 쪽이 진다. 같은 프레임이면 내가 진 것으로 본다
      if (myEvents.some((e) => e.type === 'topout')) finish(false);
      else if (aiEvents.some((e) => e.type === 'topout')) finish(true);
    }
    const pendingOf = (x: Game) => x.pending.reduce((s, b) => s + b.lines, 0);
    myBoard.current?.draw({ board: g.board, current: g.current, hold: g.hold, holdUsed: g.holdUsed, next: g.preview(), pendingLines: pendingOf(g) });
    aiBoard.current?.draw({ board: a.board, current: a.current, hold: a.hold, holdUsed: a.holdUsed, next: a.preview(), pendingLines: pendingOf(a) });
  }, phase === 'playing' || phase === 'saving' || phase === 'result');

  if (phase === 'choose') {
    return (
      <main className="page">
        <header className="topbar">
          <button className="link" onClick={() => navigate('/lobby')}>
            ← 로비
          </button>
          <strong>vs AI</strong>
          <span />
        </header>
        <section className="card stack" style={{ marginTop: 16, maxWidth: 520 }}>
          <h2>난이도</h2>
          {DIFFICULTIES.map((d) => (
            <label key={d.value} className="row">
              <input type="radio" name="difficulty" checked={difficulty === d.value} onChange={() => setDifficulty(d.value)} />
              <strong>{d.label}</strong>
              <span className="muted">{d.note}</span>
            </label>
          ))}
          <button onClick={() => setRound((r) => r + 1)}>시작</button>
        </section>
      </main>
    );
  }

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={() => navigate('/lobby')}>
          ← 로비
        </button>
        <strong>vs AI · {difficulty}</strong>
        <FxControls />
      </header>
      <section className="play">
        <div className="board-wrap">
          <p className="muted">나 · {hud.me.toLocaleString()}점</p>
          <BoardCanvas ref={myBoard} label="내 보드" sound />
          {phase === 'starting' && <div className="overlay">경기를 준비하는 중…</div>}
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
        <div className="board-wrap">
          <p className="muted">AI · {hud.ai.toLocaleString()}점</p>
          <BoardCanvas ref={aiBoard} cell={20} label="AI 보드" />
        </div>
      </section>
      {phase === 'result' && outcome && (
        <section className="card result" ref={revealOnMount}>
          <h2>{outcome.win ? '승리!' : '패배'}</h2>
          <p className="muted">
            내 점수 {me.current?.score.toLocaleString()} · AI 점수 {ai.current?.score.toLocaleString()}
            {outcome.ranked ? '' : ' · 점수 검사에 걸려 기록에 표시만 됩니다'}
          </p>
          <p className="muted small">
            AI 서버 응답 {hud.server}수 · 규칙 AI 대체 {hud.fallback}수
          </p>
          <div className="row">
            <button onClick={() => setRound((r) => r + 1)}>다시 하기</button>
            <button className="secondary" onClick={() => setPhase('choose')}>
              난이도 바꾸기
            </button>
            <button className="secondary" onClick={() => navigate('/lobby')}>
              로비로
            </button>
          </div>
        </section>
      )}
    </main>
  );
}
