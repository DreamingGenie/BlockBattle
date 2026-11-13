import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import { StompClient, wsUrl, type WsMessage } from './stomp';

/** GET /rooms/{code}·POST join 응답(API 명세 3.13·3.14) */
export interface RoomView {
  roomCode: string;
  status: 'WAITING' | 'PLAYING' | 'FINISHED' | 'CLOSED';
  roundCount: number;
  hostUserId: number;
  players: { userId: number; nickname: string; tag: string; ready: boolean }[];
  expiresAt: string;
}

/** 개인 오류(/user/queue/errors, API 명세 5.6) */
export interface PersonalError {
  code: string;
  message: string;
  payload?: Record<string, unknown>;
}

export interface RoomHandlers {
  onTopic: (message: WsMessage) => void;
  onError: (error: PersonalError) => void;
  onState: (message: WsMessage) => void;
  onConnected?: (reconnect: boolean) => void;
  onDisconnected?: () => void;
}

/**
 * 방 STOMP 연결(T-21): /topic/rooms/{code}·/user/queue/errors·/user/queue/state를 구독한다.
 * 끊기면 StompClient가 1초마다 다시 붙고 구독을 복구한다. 토큰이 만료되면 Refresh로 재발급해 다시 연결한다.
 */
export function useRoomConnection(api: ApiClient, code: string | null, handlers: RoomHandlers) {
  const [connected, setConnected] = useState(false);
  const client = useRef<StompClient | null>(null);
  const latest = useRef(handlers);
  latest.current = handlers;

  useEffect(() => {
    if (!code) return;
    const stomp = new StompClient({
      url: wsUrl(),
      getToken: () => api.token,
      onAuthError: () => api.refresh(),
      onConnected: (reconnect) => {
        setConnected(true);
        latest.current.onConnected?.(reconnect);
      },
      onDisconnected: () => {
        setConnected(false);
        latest.current.onDisconnected?.();
      },
    });
    stomp.subscribe(`/topic/rooms/${code}`, (body) => latest.current.onTopic(body as WsMessage));
    stomp.subscribe('/user/queue/errors', (body) => latest.current.onError(body as PersonalError));
    stomp.subscribe('/user/queue/state', (body) => latest.current.onState(body as WsMessage));
    stomp.connect();
    client.current = stomp;
    return () => {
      stomp.disconnect();
      client.current = null;
      setConnected(false);
    };
  }, [api, code]);

  return {
    connected,
    send: (destination: string, body: unknown) => client.current?.send(destination, body) ?? false,
    /** /user/queue/state를 한 번 더 구독해 RESYNC를 다시 받는다(INVALID_LOCK 뒤 복원, T-22). 해지 함수를 돌려준다 */
    resubscribeState: (): (() => void) | undefined =>
      client.current?.subscribe('/user/queue/state', (body) => latest.current.onState(body as WsMessage)),
  };
}

/** 경로 /room/{code}(초대 URL, 기획서 4.3)로 들어오면 해시 라우트 #/room/{code}로 바꾼다 */
export function adoptInvitePath(): void {
  const match = window.location.pathname.match(/^\/room\/([A-Za-z0-9]{6})\/?$/);
  if (match) window.history.replaceState(null, '', `/#/room/${match[1]!.toUpperCase()}`);
}
