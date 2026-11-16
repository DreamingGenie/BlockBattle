import { BotNotice, ResultModal } from '../game/ResultModal';
import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Game, boardFromString, emptyBoard, type Board } from '../engine';
import { BoardCanvas, type BoardCanvasHandle } from '../game/BoardCanvas';
import { applyActions, inputSound, useKeyboard } from '../game/controls';
import { sfx } from '../game/sound';
import { lockMessage, restoreArgs, REASON_TEXT, type GameOverPayload, type ResyncPayload } from '../game/friend';
import { InputState } from '../game/input';
import { useGameLoop } from '../game/useGameLoop';
import type { Countdown } from './RoomScreen';

export interface MatchPanelHandle {
  countdown: (c: Countdown) => void;
  board: (p: { userId: number; seq: number; board: string; score: number }) => void;
  attack: (p: { fromUserId: number; toUserId: number; lines: number; holeColumn: number }) => void;
  gameOver: (p: GameOverPayload) => void;
  rematchChanged: (p: { userId: number; accept: boolean }) => void;
  invalidLock: () => void;
  resync: (p: ResyncPayload) => void;
}

interface Props {
  code: string;
  myId: number;
  names: Record<number, string>;
  connected: boolean;
  send: (destination: string, body: unknown) => boolean;
  resubscribeState: () => (() => void) | undefined;
  /** 방을 나가 로비로(결과 팝업의 버튼) */
  onLeave: () => void;
}

/**
 * 친구 대전 진행(T-22, REQ-012~014): COUNTDOWN의 시드로 내 엔진을 startsAt에 시작하고 lock마다 서버로 보낸다.
 * 상대 보드·점수는 서버 BOARD, 방해 줄은 ATTACK을 그대로 쓴다. INVALID_LOCK·재접속 뒤에는 RESYNC로 서버 상태를 되살린다.
 */
export const MatchPanel = forwardRef<MatchPanelHandle, Props>(function MatchPanel({ code, myId, names, connected, send, resubscribeState, onLeave }, ref) {
  const game = useRef<Game | null>(null);
  const matchId = useRef<number | null>(null);
  const startsAt = useRef(0);
  const finished = useRef(false);
  const resyncing = useRef(false);
  const stopResync = useRef<(() => void) | undefined>(undefined);
  const opponent = useRef<{ board: Board; score: number }>({ board: emptyBoard(), score: 0 });
  const myBoard = useRef<BoardCanvasHandle>(null);
  const oppBoard = useRef<BoardCanvasHandle>(null);
  const input = useMemo(() => new InputState(), []);
  const [round, setRound] = useState<Countdown | null>(null);
  const [scores, setScores] = useState({ me: 0, opp: 0 });
  const [over, setOver] = useState<GameOverPayload | null>(null);
  const [rematch, setRematch] = useState<Record<number, boolean>>({});
  const [now, setNow] = useState(Date.now());
  const [note, setNote] = useState<string | null>(null);

  useImperativeHandle(ref, () => ({
    countdown(c) {
      game.current = new Game(c.seed); // 같은 시드 → 같은 블록 순서(REQ-012)
      input.resetCounters(); // 봇 판정용 키 입력 수는 라운드마다 센다(T-41)
      matchId.current = c.matchId;
      startsAt.current = c.startsAt;
      finished.current = false;
      resyncing.current = false;
      opponent.current = { board: emptyBoard(), score: 0 };
      myBoard.current?.reset();
      oppBoard.current?.reset();
      setRound(c);
      setOver(null);
      setRematch({});
      setScores({ me: 0, opp: 0 });
      setNote(null);
    },
    board(p) {
      if (p.userId === myId) {
        setScores((s) => ({ ...s, me: p.score }));
      } else {
        opponent.current = { board: boardFromString(p.board), score: p.score };
        setScores((s) => ({ ...s, opp: p.score }));
      }
    },
    attack(p) {
      if (p.toUserId === myId) {
        game.current?.receiveGarbage({ lines: p.lines, holeColumn: p.holeColumn });
        myBoard.current?.garbage();
      }
    },
    gameOver(p) {
      finished.current = true;
      if (p.winnerUserId === myId) {
        oppBoard.current?.topout();
        sfx.win();
      } else if (p.winnerUserId !== null) {
        myBoard.current?.topout();
      }
      setOver(p);
    },
    rematchChanged(p) {
      setRematch((r) => ({ ...r, [p.userId]: p.accept }));
    },
    invalidLock() {
      // 서버 보드와 어긋났다: 멈추고 상태 큐를 다시 구독해 RESYNC로 맞춘다(POL-005)
      if (resyncing.current) return;
      resyncing.current = true;
      setNote('서버 보드와 맞추는 중…');
      stopResync.current = resubscribeState();
    },
    resync(p) {
      let g = game.current;
      if (!g || p.matchId !== matchId.current) {
        if (p.seed == null) {
          if (!g) setNote('진행 중인 경기가 있지만 이 화면에는 시드가 없어 이어 할 수 없습니다. 상대가 끝내기를 기다려 주세요.');
          return;
        }
        // 페이지를 새로 열었다: RESYNC의 시드로 같은 블록 순서를 다시 만들어 바로 이어 한다(POL-006 v2)
        g = new Game(p.seed);
        game.current = g;
        myBoard.current?.reset();
        oppBoard.current?.reset();
        matchId.current = p.matchId;
        startsAt.current = 0;
        finished.current = false;
        setRound({ matchId: p.matchId, seriesNo: 0, roundNo: 0, attemptNo: 1, seed: p.seed, startsAt: 0 });
        setOver(null);
        setRematch({});
      }
      g.restore(restoreArgs(p));
      if (p.opponent) opponent.current = { board: boardFromString(p.opponent.board), score: p.opponent.score };
      setScores((s) => ({ me: p.me?.score ?? s.me, opp: p.opponent?.score ?? s.opp }));
      resyncing.current = false;
      stopResync.current?.();
      stopResync.current = undefined;
      setNote(null);
    },
  }));

  const playing = !!round && !over;
  useKeyboard(input, playing && connected);

  const lastTick = useRef(0);
  useGameLoop((dt) => {
    if (Date.now() - lastTick.current >= 200) {
      lastTick.current = Date.now();
      setNow(lastTick.current); // 남은 초 표시는 0.2초마다만 다시 그린다
    }
    const g = game.current;
    if (!g) return;
    const live = Date.now() >= startsAt.current && !finished.current && !resyncing.current && connected;
    if (live) {
      applyActions(g, input.update(dt).filter((a) => a !== 'pause'), inputSound); // 친구 대전은 일시정지 없음(GR-02)
      g.tick(dt);
      for (const event of g.drainEvents()) {
        if (event.type === 'lock') {
          send(`/app/rooms/${code}/lock`, lockMessage(event, input.presses));
          myBoard.current?.lock(event, g.combo); // 연출은 전송 뒤, 보낸 값에는 영향 없음(REQ-023)
        } else if (event.type === 'topout') {
          finished.current = true;
          myBoard.current?.topout();
          send(`/app/rooms/${code}/topout`, { seq: g.seq });
        }
      }
    } else {
      input.update(dt); // 시작 전·끊김 중 입력은 버린다(끊긴 쪽 보드는 멈춘다, POL-006)
    }
    const pending = g.pending.reduce((s, b) => s + b.lines, 0);
    myBoard.current?.draw({ board: g.board, current: g.current, hold: g.hold, holdUsed: g.holdUsed, next: g.preview(), pendingLines: pending });
    oppBoard.current?.draw({ board: opponent.current.board, current: null, ghost: false });
  }, !!round);

  const startIn = round ? Math.ceil((startsAt.current - now) / 1000) : 99;
  const lastBeep = useRef<number | null>(null);
  useEffect(() => {
    // 카운트다운 3·2·1·시작 효과음. 이어 하기(startsAt 0)에는 울리지 않는다
    if (!round || over || startsAt.current === 0) return;
    const n = Math.max(0, startIn);
    if (n <= 3 && lastBeep.current !== n) {
      lastBeep.current = n;
      sfx.countdown(n);
    }
    if (n > 3) lastBeep.current = null;
  }, [round, over, startIn]);

  if (!round) return null;
  const opponentId = Object.keys(names).map(Number).find((id) => id !== myId);
  const nextIn = over?.nextRoundStartsAt ? Math.max(0, Math.ceil((over.nextRoundStartsAt - now) / 1000)) : null;
  const iWon = over?.winnerUserId === myId;
  const seriesDone = over?.seriesWinnerUserId != null;

  return (
    <section className="stack match-panel">
      <p className="muted small round-line">
        {round.seriesNo > 0
          ? `시리즈 ${round.seriesNo} · ${round.roundNo}라운드${round.attemptNo > 1 ? ` (재시도 ${round.attemptNo})` : ''}`
          : '경기 이어 하는 중'}
        {!connected && ' · 연결이 끊겨 다시 연결하는 중… 내 보드는 멈춰 있습니다'}
        {note && ` · ${note}`}
      </p>
      <div className="play">
        <div className="board-wrap">
          <p className="muted">
            나 · {scores.me.toLocaleString()}점
          </p>
          <BoardCanvas ref={myBoard} label="내 보드" sound />
          {startIn > 0 && !over && <div className="overlay"><h1>{startIn}</h1></div>}
        </div>
        <div className="board-wrap">
          <p className="muted">
            {opponentId ? names[opponentId] : '상대'} · {scores.opp.toLocaleString()}점
          </p>
          <BoardCanvas ref={oppBoard} cell={20} sides={false} label="상대 보드" />
        </div>
      </div>
      {over && over.botSuspectedUserIds?.includes(myId) && (
        <BotNotice
          matchId={over.matchId}
          extra={
            <p className="muted small">이번 라운드는 양쪽 모두 통계에 기록되지 않습니다. 결과 화면은 뒤에서 계속 진행됩니다.</p>
          }
        />
      )}
      {over && !over.botSuspectedUserIds?.includes(myId) && (
        <ResultModal
          tone={over.winnerUserId === null ? 'draw' : iWon ? 'win' : 'lose'}
          title={over.winnerUserId === null ? 'DRAW' : iWon ? 'VICTORY' : 'DEFEAT'}
        >
          {over.botSuspectedUserIds && over.botSuspectedUserIds.length > 0 && (
            <div className="opp-bot-notice" role="alert">
              <strong>상대방의 봇 사용이 의심됩니다</strong>
              <span>소명되기 전까지는 이번 대전이 통계에 기록되지 않습니다.</span>
            </div>
          )}
          <p className="muted">{REASON_TEXT[over.reason]}</p>
          <p>
            승수:{' '}
            {Object.entries(over.seriesWins)
              .map(([id, w]) => `${names[Number(id)] ?? id} ${w}`)
              .join(' : ')}
          </p>
          <table>
            <thead>
              <tr>
                <th>플레이어</th>
                <th>점수</th>
                <th>공격 보냄</th>
                <th>공격 받음</th>
                <th>최대 콤보</th>
                <th>APM</th>
              </tr>
            </thead>
            <tbody>
              {over.stats.map((s) => (
                <tr key={s.userId} className={s.userId === myId ? 'mine' : undefined}>
                  <td>{names[s.userId] ?? s.userId}</td>
                  <td>{s.score.toLocaleString()}</td>
                  <td>{s.attackSent}</td>
                  <td>{s.attackReceived}</td>
                  <td>{s.maxCombo}</td>
                  <td>{s.apm}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {nextIn !== null && !seriesDone && <p>{nextIn > 0 ? `${nextIn}초 뒤 다음 라운드` : '다음 라운드 준비 중…'}</p>}
          {!seriesDone && (
            <div className="row">
              <button className="secondary" onClick={onLeave}>
                방 나가기
              </button>
            </div>
          )}
          {seriesDone && (
            <div className="stack">
              <strong>{over.seriesWinnerUserId === myId ? '시리즈 승리!' : '시리즈 패배'}</strong>
              <div className="row">
                <button onClick={() => send(`/app/rooms/${code}/rematch`, { accept: true })} disabled={rematch[myId] === true}>
                  재대결 수락
                </button>
                <button className="secondary" onClick={() => send(`/app/rooms/${code}/rematch`, { accept: false })}>
                  거절
                </button>
                <button className="secondary" onClick={onLeave}>
                  로비로
                </button>
              </div>
              <p className="muted">
                {Object.entries(rematch)
                  .map(([id, a]) => `${names[Number(id)] ?? id}: ${a ? '수락' : '거절'}`)
                  .join(' · ') || '두 사람이 모두 수락하면 새 시리즈를 시작합니다.'}
              </p>
            </div>
          )}
        </ResultModal>
      )}
    </section>
  );
});
