import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { navigate } from '../app/router';
import { useSession } from '../app/session';
import { useRoomConnection, type PersonalError, type RoomView } from '../realtime/room';
import type { WsMessage } from '../realtime/stomp';
import type { GameOverPayload, ResyncPayload } from '../game/friend';
import { FxControls } from '../game/FxControls';
import { MatchPanel, type MatchPanelHandle } from './MatchPanel';

export interface Countdown {
  matchId: number;
  seriesNo: number;
  roundNo: number;
  attemptNo: number;
  seed: number;
  startsAt: number;
}

/** 친구 대전 방(FR-ROM-02·03): 입장 확인 → 대기실(준비·나가기·초대 URL) → COUNTDOWN */
export function RoomScreen({ code }: { code: string }) {
  const { api, me } = useSession();
  const [room, setRoom] = useState<RoomView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [closed, setClosed] = useState<string | null>(null);
  const [countdown, setCountdown] = useState<Countdown | null>(null);
  const [now, setNow] = useState(Date.now());
  const participant = !!room && !!me && room.players.some((p) => p.userId === me.userId);
  const match = useRef<MatchPanelHandle>(null);
  const [names, setNames] = useState<Record<number, string>>({});
  useEffect(() => {
    // 상대가 나간 뒤에도 결과 화면에 이름이 남도록 쌓아 둔다
    if (room) setNames((n) => ({ ...n, ...Object.fromEntries(room.players.map((p) => [p.userId, `${p.nickname}#${p.tag}`])) }));
  }, [room]);

  const load = useCallback(async () => {
    try {
      setRoom(await api.get<RoomView>(`/rooms/${code}`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [api, code]);

  useEffect(() => {
    load();
  }, [load]);

  const onTopic = useCallback(
    (message: WsMessage) => {
      switch (message.type) {
        case 'PLAYER_JOINED':
        case 'READY_CHANGED':
          load();
          break;
        case 'PLAYER_LEFT':
          if ((message.payload as { reason?: string }).reason === 'DISCONNECTED') setNotice('상대의 연결이 끊겼습니다. 10초 동안 기다립니다.');
          load();
          break;
        case 'PLAYER_RECONNECTED':
          setNotice('상대가 다시 연결했습니다.');
          break;
        case 'COUNTDOWN':
          setCountdown(message.payload as unknown as Countdown);
          match.current?.countdown(message.payload as unknown as Countdown);
          setNotice(null);
          break;
        case 'BOARD':
          match.current?.board(message.payload as never);
          break;
        case 'ATTACK':
          match.current?.attack(message.payload as never);
          break;
        case 'GAME_OVER':
          match.current?.gameOver(message.payload as unknown as GameOverPayload);
          load();
          break;
        case 'REMATCH_CHANGED':
          match.current?.rematchChanged(message.payload as never);
          break;
        case 'ROOM_CLOSED': {
          const reason = (message.payload as { reason?: string }).reason;
          setClosed(reason === 'HOST_LEFT' ? '방장이 나가서 방이 닫혔습니다.' : reason === 'EXPIRED' ? '30분 동안 활동이 없어 방이 닫혔습니다.' : '방이 닫혔습니다.');
          break;
        }
      }
    },
    [load],
  );

  const onError = useCallback((e: PersonalError) => {
    if (e.code === 'INVALID_LOCK') match.current?.invalidLock();
    else setNotice(errorMessage(new ApiError(400, e.code, e.message)));
  }, []);

  const connection = useRoomConnection(api, participant && !closed ? code : null, {
    onTopic,
    onError,
    onState: (message) => {
      if (message.type !== 'RESYNC') return;
      const p = message.payload as unknown as ResyncPayload;
      match.current?.resync(p);
      // 새로 연 화면이면 대기 화면 대신 진행 중 경기를 보인다(POL-006 v2)
      if (p.seed != null) setCountdown((c) => c ?? { matchId: p.matchId, seriesNo: 0, roundNo: 0, attemptNo: 1, seed: p.seed!, startsAt: 0 });
    },
    onConnected: (reconnect) => {
      if (reconnect) setNotice('다시 연결했습니다.');
      load();
    },
  });

  // COUNTDOWN 남은 초 표시
  useEffect(() => {
    if (!countdown) return;
    const timer = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(timer);
  }, [countdown]);

  async function join() {
    setError(null);
    try {
      setRoom(await api.post<RoomView>(`/rooms/${code}/join`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function leave() {
    try {
      await api.post<void>(`/rooms/${code}/leave`);
    } catch {
      // 이미 닫힌 방이어도 로비로 간다
    }
    navigate('/lobby');
  }

  const mine = room?.players.find((p) => p.userId === me?.userId);
  const inviteUrl = `${window.location.origin}/room/${code}`;
  const secondsLeft = countdown ? Math.max(0, Math.ceil((countdown.startsAt - now) / 1000)) : null;

  return (
    <main className="page">
      <header className="topbar">
        <button className="link" onClick={participant ? leave : () => navigate('/lobby')}>
          ← {participant ? '나가기' : '로비'}
        </button>
        <strong>친구 대전 · {code}</strong>
        <span className="row">
          {participant && !closed && <FxControls />}
          <span className="muted small">{participant && !closed ? (connection.connected ? '연결됨' : '연결 중…') : ''}</span>
        </span>
      </header>

      {closed && (
        <section className="card result">
          <p>{closed}</p>
          <button onClick={() => navigate('/lobby')}>로비로</button>
        </section>
      )}
      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      {room && !participant && !closed && (
        <section className="card stack" style={{ marginTop: 16, maxWidth: 480 }}>
          <h2>방 {room.roomCode}</h2>
          <p className="muted">
            {room.roundCount === 3 ? '3판 2선승' : '1판'} · 참가자 {room.players.length}/2 · {room.status === 'WAITING' ? '대기 중' : '경기 중'}
          </p>
          <ul>
            {room.players.map((p) => (
              <li key={p.userId}>
                {p.nickname}#{p.tag}
                {p.userId === room.hostUserId ? ' (방장)' : ''}
              </li>
            ))}
          </ul>
          <button onClick={join} disabled={room.status !== 'WAITING' || room.players.length >= 2}>
            입장하기
          </button>
        </section>
      )}

      {room && participant && !closed && me && (
        <MatchPanel
          ref={match}
          onLeave={leave}
          code={code}
          myId={me.userId}
          names={names}
          connected={connection.connected}
          send={connection.send}
          resubscribeState={connection.resubscribeState}
        />
      )}

      {room && participant && !closed && !countdown && (
        <section className="grid menu">
          <div className="card stack">
            <h2>참가자</h2>
            {room.players.map((p) => (
              <div key={p.userId} className="row">
                <strong>
                  {p.nickname}#{p.tag}
                </strong>
                {p.userId === room.hostUserId && <span className="badge">방장</span>}
                <span className={p.ready ? 'ok' : 'muted'}>{p.ready ? '준비 완료' : '대기'}</span>
              </div>
            ))}
            {room.players.length < 2 && <p className="muted">상대를 기다리는 중입니다.</p>}
            {room.status === 'WAITING' && (
              <button onClick={() => connection.send(`/app/rooms/${code}/ready`, { ready: !mine?.ready })} disabled={!connection.connected}>
                {mine?.ready ? '준비 취소' : '준비'}
              </button>
            )}
            {secondsLeft !== null && <h2>{secondsLeft > 0 ? `${secondsLeft}초 뒤 시작` : '시작!'}</h2>}
          </div>
          <div className="card stack">
            <h2>초대</h2>
            <p className="muted">{room.roundCount === 3 ? '3판 2선승' : '1판'}</p>
            <code>{inviteUrl}</code>
            <div className="row">
              <button className="secondary" onClick={() => navigator.clipboard?.writeText(inviteUrl)}>
                초대 링크 복사
              </button>
              <button className="secondary" onClick={() => navigator.clipboard?.writeText(code)}>
                코드 복사
              </button>
            </div>
          </div>
        </section>
      )}
    </main>
  );
}
