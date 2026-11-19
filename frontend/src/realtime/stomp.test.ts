import { afterEach, describe, expect, it, vi } from 'vitest';
import { StompClient, decodeFrames, encodeFrame } from './stomp';

/** STOMP 1.2 프레임과 재연결(API 명세 5장, POL-013, BR-06). 깨지면 서버와 메시지를 주고받지 못하거나 끊긴 뒤 다시 붙지 못한다 */
describe('STOMP 프레임', () => {
  it('명령·헤더·본문을 NULL로 끝나는 프레임으로 만든다', () => {
    expect(encodeFrame('SEND', { destination: '/app/rooms/K7Q2MX/ready' }, '{"ready":true}')).toBe(
      'SEND\ndestination:/app/rooms/K7Q2MX/ready\n\n{"ready":true}\u0000',
    );
  });

  it('한 메시지 안 여러 프레임과 하트비트 줄바꿈을 나누고 한글 본문을 그대로 둔다', () => {
    const data = '\n' + encodeFrame('CONNECTED', { version: '1.2' }) + encodeFrame('MESSAGE', { subscription: 'sub-0' }, '{"nickname":"민수"}') + '\n';
    const frames = decodeFrames(data);
    expect(frames.map((f) => f.command)).toEqual(['CONNECTED', 'MESSAGE']);
    expect(frames[1]!.headers.subscription).toBe('sub-0');
    expect(JSON.parse(frames[1]!.body)).toEqual({ nickname: '민수' });
  });
});

class FakeSocket {
  static all: FakeSocket[] = [];
  sent: string[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  constructor() {
    FakeSocket.all.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.onclose?.({} as CloseEvent);
  }
  serverSends(frame: string) {
    this.onmessage?.({ data: frame } as MessageEvent);
  }
}

describe('StompClient', () => {
  afterEach(() => {
    FakeSocket.all = [];
    vi.useRealTimers();
  });

  function setup(token = 't1') {
    let current = token;
    const client = new StompClient({
      url: 'ws://x/ws',
      getToken: () => current,
      createSocket: () => new FakeSocket() as never,
      onAuthError: async () => {
        current = 't2';
        return true;
      },
      retryMs: 1000,
    });
    return { client, setToken: (t: string) => (current = t) };
  }

  it('CONNECT에 Bearer 토큰을 싣고, CONNECTED 뒤 등록된 구독을 보낸다', () => {
    const { client } = setup();
    const got: unknown[] = [];
    client.subscribe('/topic/rooms/K7Q2MX', (body) => got.push(body));
    client.connect();
    const socket = FakeSocket.all[0]!;
    socket.onopen?.({} as Event);
    expect(socket.sent[0]).toContain('Authorization:Bearer t1');
    socket.serverSends(encodeFrame('CONNECTED', { version: '1.2' }));
    expect(socket.sent[1]).toContain('SUBSCRIBE\nid:sub-0\ndestination:/topic/rooms/K7Q2MX');
    socket.serverSends(encodeFrame('MESSAGE', { subscription: 'sub-0' }, '{"type":"READY_CHANGED"}'));
    expect(got).toEqual([{ type: 'READY_CHANGED' }]);
  });

  it('연결이 끊기면 1초 뒤 다시 연결하고 구독을 복구한다', () => {
    vi.useFakeTimers();
    const { client } = setup();
    client.subscribe('/user/queue/state', () => {});
    client.connect();
    const first = FakeSocket.all[0]!;
    first.onopen?.({} as Event);
    first.serverSends(encodeFrame('CONNECTED', {}));
    first.onclose?.({} as CloseEvent);
    expect(client.isConnected).toBe(false);
    expect(client.send('/app/x', {})).toBe(false); // 끊긴 동안은 보내지 않는다
    vi.advanceTimersByTime(1000);
    const second = FakeSocket.all[1]!;
    second.onopen?.({} as Event);
    second.serverSends(encodeFrame('CONNECTED', {}));
    expect(second.sent.some((s) => s.includes('destination:/user/queue/state'))).toBe(true);
  });

  it('인증 ERROR 프레임이면 토큰을 다시 받아 새 토큰으로 연결한다', async () => {
    const { client } = setup();
    client.connect();
    const first = FakeSocket.all[0]!;
    first.onopen?.({} as Event);
    first.serverSends(encodeFrame('ERROR', { message: 'TOKEN_EXPIRED' }, '{"code":"TOKEN_EXPIRED"}'));
    await Promise.resolve();
    await Promise.resolve();
    const second = FakeSocket.all[1]!;
    second.onopen?.({} as Event);
    expect(second.sent[0]).toContain('Authorization:Bearer t2');
  });

  /** 보안 점검 M-2. 깨지면: TCP close 없이 끊긴 연결(모바일 망 등)을 서버도 클라이언트도 알아채지 못해, 끊김 유예와 재연결이 시작되지 않는다 */
  it('하트비트 10초를 제안하고, 서버가 25초를 바라면 25초마다 줄바꿈을 보낸다', () => {
    vi.useFakeTimers();
    const { client } = setup();
    client.connect();
    const socket = FakeSocket.all[0]!;
    socket.onopen?.({} as Event);
    expect(socket.sent[0]).toContain('heart-beat:10000,10000');
    socket.serverSends(encodeFrame('CONNECTED', { 'heart-beat': '10000,25000' }));
    const before = socket.sent.length;
    vi.advanceTimersByTime(24_999);
    expect(socket.sent.slice(before)).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(socket.sent.slice(before)).toEqual(['\n']);
  });

  it('서버 신호가 30초 넘게 없으면 죽은 연결로 보고 다시 연결한다', () => {
    vi.useFakeTimers();
    const { client } = setup();
    client.connect();
    const first = FakeSocket.all[0]!;
    first.onopen?.({} as Event);
    first.serverSends(encodeFrame('CONNECTED', { 'heart-beat': '10000,25000' }));
    for (let i = 0; i < 6; i++) {
      vi.advanceTimersByTime(10_000);
      first.serverSends('\n'); // 서버 하트비트가 오는 동안은 그대로 둔다
    }
    expect(FakeSocket.all).toHaveLength(1);
    expect(client.isConnected).toBe(true);

    vi.advanceTimersByTime(40_000); // 조용해진 뒤 3배(30초)를 넘긴 다음 점검
    expect(client.isConnected).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(FakeSocket.all).toHaveLength(2);
  });

  it('disconnect 뒤에는 다시 연결하지 않는다', () => {
    vi.useFakeTimers();
    const { client } = setup();
    client.connect();
    const first = FakeSocket.all[0]!;
    first.onopen?.({} as Event);
    first.serverSends(encodeFrame('CONNECTED', {}));
    client.disconnect();
    vi.advanceTimersByTime(5000);
    expect(FakeSocket.all).toHaveLength(1);
  });
});
