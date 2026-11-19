import { BotNotice, ResultModal } from '../game/ResultModal';
import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Game, boardFromString, emptyBoard, type Board } from '../engine';
import { BoardCanvas, type BoardCanvasHandle } from '../game/BoardCanvas';
import { applyActions, inputSound, useKeyboard } from '../game/controls';
import { sfx } from '../game/sound';
import { lockMessage, restoreArgs, REASON_TEXT, type GameOverPayload, type PlayerOutPayload, type ResyncPayload } from '../game/friend';
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
  playerOut: (p: PlayerOutPayload) => void;
}

interface Props {
  code: string;
  myId: number;
  names: Record<number, string>;
  /** 지금 방에 있는 참가자(나 포함). COUNTDOWN 때 상대 보드 자리를 만든다 */
  playerIds: number[];
  connected: boolean;
  send: (destination: string, body: unknown) => boolean;
  resubscribeState: () => (() => void) | undefined;
  /** 방을 나가 로비로(결과 팝업의 버튼) */
  onLeave: () => void;
}

/**
 * 친구 대전 진행(T-22, REQ-012~014): COUNTDOWN의 시드로 내 엔진을 startsAt에 시작하고 lock마다 서버로 보낸다.
 * 상대 보드·점수는 서버 BOARD, 방해 줄은 ATTACK을 그대로 쓴다. INVALID_LOCK·재접속 뒤에는 RESYNC로 서버 상태를 되살린다.
 * 다인 대전(최대 4인, 2026-11-19): 상대 보드를 여럿 그리고, PLAYER_OUT으로 탈락한 사람을 흐리게 표시한다.
 */

interface Opponent {
  board: Board;
  score: number;
}
export const MatchPanel = forwardRef<MatchPanelHandle, Props>(function MatchPanel({ code, myId, names, playerIds, connected, send, resubscribeState, onLeave }, ref) {
  const game = useRef<Game | null>(null);
  const matchId = useRef<number | null>(null);
  const startsAt = useRef(0);
  const finished = useRef(false);
  /** GAME_OVER를 받은 경기. 이 경기의 RESYNC로는 다시 움직이지 않는다 */
  const overFor = useRef<number | null>(null);
  const resyncing = useRef(false);
  const stopResync = useRef<(() => void) | undefined>(undefined);
  const opponents = useRef(new Map<number, Opponent>());
  const myBoard = useRef<BoardCanvasHandle>(null);
  const oppBoards = useRef(new Map<number, BoardCanvasHandle>());
  const [oppIds, setOppIds] = useState<number[]>([]);
  /** 탈락한 사람과 등수(RESYNC로만 안 사람은 0) */
  const [outs, setOuts] = useState<Record<number, number>>({});
  const input = useMemo(() => new InputState(), []);
  const [round, setRound] = useState<Countdown | null>(null);
  const [scores, setScores] = useState<Record<number, number>>({});
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
      const ids = playerIds.filter((id) => id !== myId);
      opponents.current = new Map(ids.map((id) => [id, { board: emptyBoard(), score: 0 }]));
      myBoard.current?.reset();
      oppBoards.current.forEach((b) => b.reset());
      setOppIds(ids);
      setOuts({});
      setRound(c);
      setOver(null);
      setRematch({});
      setScores({});
      setNote(null);
    },
    board(p) {
      setScores((s) => ({ ...s, [p.userId]: p.score }));
      if (p.userId !== myId) {
        if (!opponents.current.has(p.userId)) setOppIds((ids) => (ids.includes(p.userId) ? ids : [...ids, p.userId]));
        opponents.current.set(p.userId, { board: boardFromString(p.board), score: p.score });
      }
    },
    playerOut(p) {
      setOuts((o) => ({ ...o, [p.userId]: p.placement }));
      if (p.userId === myId) {
        setNote(`${p.placement}위로 탈락했습니다 · 남은 사람들의 경기를 보는 중`);
      } else {
        oppBoards.current.get(p.userId)?.topout();
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
      overFor.current = p.matchId;
      // 아직 탈락하지 않았던 사람 중 승자가 아닌 사람의 보드에 topout 연출
      const alreadyOut = (id: number) => outs[id] !== undefined;
      if (p.winnerUserId === myId) sfx.win();
      else if (p.winnerUserId !== null && !alreadyOut(myId)) myBoard.current?.topout();
      opponents.current.forEach((_, id) => {
        if (id !== p.winnerUserId && !alreadyOut(id)) oppBoards.current.get(id)?.topout();
      });
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
        oppBoards.current.forEach((b) => b.reset());
        matchId.current = p.matchId;
        startsAt.current = 0;
        finished.current = false;
        setRound({ matchId: p.matchId, seriesNo: 0, roundNo: 0, attemptNo: 1, seed: p.seed, startsAt: 0 });
        setOver(null);
        setRematch({});
      }
      g.restore(restoreArgs(p));
      if (p.myPlacement != null) {
        // 끊긴 동안 탈락했다(다인 대전): 이어 하지 않고 남은 사람들의 경기를 본다
        finished.current = true;
        setOuts((o) => ({ ...o, [myId]: p.myPlacement! }));
        setNote(`${p.myPlacement}위로 탈락했습니다 · 남은 사람들의 경기를 보는 중`);
      } else if (finished.current && overFor.current !== p.matchId) {
        // 내가 topout을 신고했지만 서버 보드에서는 아직 블록이 나온다(방해 줄 도착 시차 등). 멈춰 있으면 경기가 끝나지 않아
        // 늦게 topout한 상대가 지므로, 서버 보드로 맞춘 뒤 이어 한다
        finished.current = false;
        myBoard.current?.reset();
      }
      const list = p.opponents ?? (p.opponent ? [{ ...p.opponent, out: false }] : []);
      list.forEach((o) => opponents.current.set(o.userId, { board: boardFromString(o.board), score: o.score }));
      if (list.length > 0) setOppIds((ids) => [...ids, ...list.map((o) => o.userId).filter((id) => !ids.includes(id))]);
      setOuts((prev) => {
        const next = { ...prev };
        list.forEach((o) => {
          if (o.out && next[o.userId] === undefined) next[o.userId] = 0;
        });
        return next;
      });
      setScores((s) => {
        const next = { ...s };
        if (p.me) next[myId] = p.me.score;
        list.forEach((o) => (next[o.userId] = o.score));
        return next;
      });
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
          send(`/app/rooms/${code}/topout`, { seq: g.seq, holdUsed: g.holdUsed });
        }
      }
    } else {
      input.update(dt); // 시작 전·끊김 중 입력은 버린다(끊긴 쪽 보드는 멈춘다, POL-006)
    }
    const pending = g.pending.reduce((s, b) => s + b.lines, 0);
    myBoard.current?.draw({ board: g.board, current: g.current, hold: g.hold, holdUsed: g.holdUsed, next: g.preview(), pendingLines: pending });
    opponents.current.forEach((o, id) => oppBoards.current.get(id)?.draw({ board: o.board, current: null, ghost: false }));
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
  const multi = oppIds.length > 1;
  const oppCell = multi ? 14 : 20;
  const nextIn = over?.nextRoundStartsAt ? Math.max(0, Math.ceil((over.nextRoundStartsAt - now) / 1000)) : null;
  const iWon = over?.winnerUserId === myId;
  // 다인 대전은 1판이라 무승부여도 다음 라운드가 없다
  const seriesDone = over != null && (over.seriesWinnerUserId != null || over.nextRoundStartsAt == null);
  const placementOf = (id: number) => over?.placements?.[String(id)];
  const myPlace = placementOf(myId);

  return (
    <section className="stack match-panel">
      <p className="meta round-line">
        {round.seriesNo > 0
          ? `시리즈 ${round.seriesNo} · ${round.roundNo}라운드${round.attemptNo > 1 ? ` (재시도 ${round.attemptNo})` : ''}`
          : '경기 이어 하는 중'}
        {!connected && ' · 연결이 끊겨 다시 연결하는 중… 내 보드는 멈춰 있습니다'}
        {note && ` · ${note}`}
      </p>
      <div className="play">
        <div className="board-wrap">
          <p className="board-label">
            <span>나</span>
            <b className="num">{(scores[myId] ?? 0).toLocaleString()}점</b>
          </p>
          <BoardCanvas ref={myBoard} label="내 보드" sound />
          {startIn > 0 && !over && <div className="overlay"><h1>{startIn}</h1></div>}
        </div>
        {oppIds.map((id) => (
          <div key={id} className={`board-wrap${outs[id] !== undefined ? ' out' : ''}`}>
            <p className="board-label">
              <span>{names[id] ?? '상대'}</span>
              <b className="num">{outs[id] ? `${outs[id]}위` : outs[id] === 0 ? '탈락' : `${(scores[id] ?? 0).toLocaleString()}점`}</b>
            </p>
            <BoardCanvas
              ref={(handle) => {
                if (handle) oppBoards.current.set(id, handle);
                else oppBoards.current.delete(id);
              }}
              cell={oppCell}
              sides={false}
              label={`${names[id] ?? '상대'} 보드`}
            />
          </div>
        ))}
      </div>
      {over && over.botSuspectedUserIds?.includes(myId) && (
        <BotNotice
          matchId={over.matchId}
          extra={
            <p className="muted small">이번 라운드는 참가자 모두 통계에 기록되지 않습니다. 결과 화면은 뒤에서 계속 진행됩니다.</p>
          }
        />
      )}
      {over && !over.botSuspectedUserIds?.includes(myId) && (
        <ResultModal
          tone={over.winnerUserId === null ? 'draw' : iWon ? 'win' : 'lose'}
          title={over.winnerUserId === null ? 'DRAW' : iWon ? 'VICTORY' : multi && myPlace ? `${myPlace}위` : 'DEFEAT'}
        >
          {over.botSuspectedUserIds && over.botSuspectedUserIds.length > 0 && (
            <div className="opp-bot-notice" role="alert">
              <strong>상대방의 봇 사용이 의심됩니다</strong>
              <span>소명되기 전까지는 이번 대전이 통계에 기록되지 않습니다.</span>
            </div>
          )}
          <p className="muted">{REASON_TEXT[over.reason]}</p>
          {Object.keys(over.seriesWins).length === 2 && (
            <p>
              승수:{' '}
              {Object.entries(over.seriesWins)
                .map(([id, w]) => `${names[Number(id)] ?? id} ${w}`)
                .join(' : ')}
            </p>
          )}
          <table>
            <thead>
              <tr>
                {multi && <th>등수</th>}
                <th>플레이어</th>
                <th>점수</th>
                <th>공격 보냄</th>
                <th>공격 받음</th>
                <th>최대 콤보</th>
                <th>APM</th>
              </tr>
            </thead>
            <tbody>
              {[...over.stats]
                .sort((x, y) => (placementOf(x.userId) ?? 9) - (placementOf(y.userId) ?? 9))
                .map((s) => (
                <tr key={s.userId} className={s.userId === myId ? 'mine' : undefined}>
                  {multi && <td>{placementOf(s.userId) ?? '-'}</td>}
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
              {!multi && over.seriesWinnerUserId != null && <strong>{over.seriesWinnerUserId === myId ? '시리즈 승리!' : '시리즈 패배'}</strong>}
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
                  .join(' · ') || '방에 있는 사람이 모두 수락하면 새 경기를 시작합니다.'}
              </p>
            </div>
          )}
        </ResultModal>
      )}
    </section>
  );
});
