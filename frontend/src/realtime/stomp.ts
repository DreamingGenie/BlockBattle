/**
 * 작은 STOMP 1.2 클라이언트(API 명세 5장, POL-013, T-21). 라이브러리 없이 브라우저 WebSocket 위에서 돈다.
 * - CONNECT에 Authorization: Bearer Access 토큰을 싣는다. 토큰은 연결할 때마다 getToken()으로 새로 읽는다.
 * - ERROR 프레임(UNAUTHORIZED·TOKEN_EXPIRED)이면 onAuthError로 토큰을 재발급받은 뒤 다시 연결한다.
 * - 연결이 끊기면 retryMs 간격으로 다시 연결하고, 등록된 구독을 모두 다시 보낸다.
 */

export interface Frame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

export function encodeFrame(command: string, headers: Record<string, string>, body = ''): string {
  let out = `${command}\n`;
  for (const [k, v] of Object.entries(headers)) out += `${k}:${v}\n`;
  return `${out}\n${body}\u0000`;
}

/** 한 WebSocket 메시지에 프레임이 여러 개 오거나 하트비트(빈 줄)가 섞여도 나눈다 */
export function decodeFrames(data: string): Frame[] {
  const frames: Frame[] = [];
  for (const raw of data.split('\u0000')) {
    const text = raw.replace(/^[\r\n]+/, '');
    if (!text) continue;
    const split = text.indexOf('\n\n');
    const head = split >= 0 ? text.slice(0, split) : text;
    const lines = head.split('\n');
    const headers: Record<string, string> = {};
    for (const line of lines.slice(1)) {
      const at = line.indexOf(':');
      if (at > 0) headers[line.slice(0, at)] = line.slice(at + 1);
    }
    frames.push({ command: lines[0]!.trim(), headers, body: split >= 0 ? text.slice(split + 2) : '' });
  }
  return frames;
}

type Socket = Pick<WebSocket, 'send' | 'close'> & {
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
};

export interface StompOptions {
  url: string;
  getToken: () => string | null;
  /** ERROR 프레임이 인증 오류일 때. true를 돌려주면 다시 연결한다 */
  onAuthError?: (code: string) => Promise<boolean>;
  onConnected?: (reconnect: boolean) => void;
  onDisconnected?: () => void;
  retryMs?: number;
  createSocket?: (url: string) => Socket;
}

export class StompClient {
  private socket: Socket | null = null;
  private subs = new Map<string, { destination: string; handler: (body: unknown, frame: Frame) => void }>();
  private nextId = 0;
  private connected = false;
  private closedByUser = false;
  private everConnected = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly options: StompOptions) {}

  get isConnected(): boolean {
    return this.connected;
  }

  connect(): void {
    this.closedByUser = false;
    this.open();
  }

  /** 구독을 등록한다. 연결 전이면 연결될 때 보낸다. 구독 해지 함수를 돌려준다 */
  subscribe(destination: string, handler: (body: unknown, frame: Frame) => void): () => void {
    const id = `sub-${this.nextId++}`;
    this.subs.set(id, { destination, handler });
    if (this.connected) this.socket?.send(encodeFrame('SUBSCRIBE', { id, destination }));
    return () => {
      this.subs.delete(id);
      if (this.connected) this.socket?.send(encodeFrame('UNSUBSCRIBE', { id }));
    };
  }

  /** 연결돼 있으면 보내고 true, 끊겨 있으면 버리고 false */
  send(destination: string, body: unknown): boolean {
    if (!this.connected || !this.socket) return false;
    this.socket.send(encodeFrame('SEND', { destination, 'content-type': 'application/json' }, JSON.stringify(body)));
    return true;
  }

  disconnect(): void {
    this.closedByUser = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.socket && this.connected) this.socket.send(encodeFrame('DISCONNECT', {}));
    this.socket?.close();
    this.socket = null;
    this.connected = false;
  }

  private open(): void {
    const create = this.options.createSocket ?? ((url: string) => new WebSocket(url) as unknown as Socket);
    const socket = create(this.options.url);
    this.socket = socket;
    socket.onopen = () => {
      const token = this.options.getToken();
      socket.send(encodeFrame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', ...(token ? { Authorization: `Bearer ${token}` } : {}) }));
    };
    socket.onmessage = (event) => {
      for (const frame of decodeFrames(String(event.data))) this.handle(socket, frame);
    };
    socket.onerror = () => {};
    socket.onclose = () => {
      if (this.socket !== socket) return;
      const wasConnected = this.connected;
      this.connected = false;
      if (wasConnected) this.options.onDisconnected?.();
      this.scheduleRetry();
    };
  }

  private handle(socket: Socket, frame: Frame): void {
    if (frame.command === 'CONNECTED') {
      this.connected = true;
      for (const [id, sub] of this.subs) socket.send(encodeFrame('SUBSCRIBE', { id, destination: sub.destination }));
      const reconnect = this.everConnected;
      this.everConnected = true;
      this.options.onConnected?.(reconnect);
      return;
    }
    if (frame.command === 'MESSAGE') {
      const sub = this.subs.get(frame.headers.subscription ?? '');
      if (!sub) return;
      let body: unknown = frame.body;
      try {
        body = JSON.parse(frame.body);
      } catch {
        // JSON이 아니면 문자열 그대로
      }
      sub.handler(body, frame);
      return;
    }
    if (frame.command === 'ERROR') {
      const code = frame.headers.message ?? '';
      // 서버가 연결을 끊는다. 인증 오류면 토큰을 다시 받고 재연결한다(POL-013)
      this.socket = null;
      this.connected = false;
      socket.close();
      if ((code === 'UNAUTHORIZED' || code === 'TOKEN_EXPIRED') && this.options.onAuthError) {
        this.options.onAuthError(code).then((retry) => {
          if (retry && !this.closedByUser) this.open();
        });
      } else {
        this.scheduleRetry();
      }
    }
  }

  private scheduleRetry(): void {
    if (this.closedByUser) return;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (!this.closedByUser) this.open();
    }, this.options.retryMs ?? 1000);
  }
}

/** 서버 발신 메시지 봉투(ENT-015) */
export interface WsMessage<T = Record<string, unknown>> {
  type: string;
  roomCode: string;
  sentAt: number;
  payload: T;
}

/** 개발 서버(같은 출처)의 /ws 주소 */
export function wsUrl(): string {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws`;
}
