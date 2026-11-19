import { appPath } from '../app/basePath';
/**
 * REST 클라이언트(API 명세 1장, T-18).
 * 응답 봉투 {success, data, error}를 풀고 Access 토큰을 붙인다. 401이면 Refresh 쿠키로 한 번 재발급해 다시 보내고,
 * 재발급도 실패하면 세션을 잃은 것으로 보고 onSessionLost를 부른다(REQ-001). Access 토큰은 메모리에만 둔다.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export interface GuestLogin {
  userId: number;
  nickname: string;
  tag: string;
  userType: 'GUEST' | 'MEMBER';
  accessToken: string;
  expiresIn: number;
}

interface Envelope<T> {
  success: boolean;
  data: T;
  error: { code: string; message: string } | null;
}

type FetchFn = (input: string, init?: RequestInit) => Promise<Response>;

export interface RequestOptions {
  /** 인증 헤더를 붙이고 401이면 재발급을 시도한다 */
  auth?: boolean;
  query?: Record<string, string | number | undefined>;
  /**
   * 502·503·504(nginx가 backend에 닿지 못함)나 네트워크 오류면 RETRY_DELAYS_MS 간격으로 다시 보낸다(T-36).
   * 배포로 backend가 다시 뜨는 동안 경기 시작·결과 저장이 실패하지 않게 한다. 4xx·500은 다시 보내지 않는다.
   */
  retryUnavailable?: boolean;
}

/** 합 30초: backend 기동(약 10초)을 덮는다 */
export const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000];
const UNAVAILABLE = new Set([502, 503, 504]);

export class ApiClient {
  private accessToken: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  onSessionLost: () => void = () => {};

  constructor(
    private readonly fetchFn: FetchFn = (input, init) => fetch(input, init),
    private readonly base = appPath('api/v1'),
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {}

  get token(): string | null {
    return this.accessToken;
  }

  setToken(token: string | null): void {
    this.accessToken = token;
  }

  async get<T>(path: string, options: RequestOptions = {}): Promise<T> {
    return this.request<T>('GET', path, undefined, options);
  }

  async post<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    return this.request<T>('POST', path, body, options);
  }

  async request<T>(method: string, path: string, body: unknown, options: RequestOptions, retried = false): Promise<T> {
    const auth = options.auth ?? true;
    const headers: Record<string, string> = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;
    const init: RequestInit = {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    };
    const res = await this.send(this.base + path + queryString(options.query), init, options.retryUnavailable ?? false);
    if (res.status === 401 && auth && !retried) {
      if (await this.refresh()) return this.request<T>(method, path, body, options, true);
      this.loseSession();
      throw new ApiError(401, 'UNAUTHORIZED', '다시 로그인해 주세요.');
    }
    return unwrap<T>(res);
  }

  private async send(url: string, init: RequestInit, retry: boolean): Promise<Response> {
    for (let attempt = 0; ; attempt++) {
      const last = !retry || attempt >= RETRY_DELAYS_MS.length;
      try {
        const res = await this.fetchFn(url, init);
        if (last || !UNAVAILABLE.has(res.status)) return res;
      } catch (e) {
        if (last) throw e;
      }
      await this.sleep(RETRY_DELAYS_MS[attempt]!);
    }
  }

  /** Refresh 쿠키로 Access 토큰을 다시 받는다(API-002). 동시에 여러 요청이 401을 받아도 한 번만 보낸다 */
  refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = (async () => {
        try {
          const res = await this.fetchFn(`${this.base}/auth/refresh`, { method: 'POST', credentials: 'same-origin' });
          if (!res.ok) return false;
          const data = await unwrap<{ accessToken: string }>(res);
          this.accessToken = data.accessToken;
          return true;
        } catch {
          return false;
        } finally {
          this.refreshing = null;
        }
      })();
    }
    return this.refreshing;
  }

  async guest(nickname: string): Promise<GuestLogin> {
    const data = await this.post<GuestLogin>('/auth/guest', { nickname }, { auth: false });
    this.accessToken = data.accessToken;
    return data;
  }

  async logout(): Promise<void> {
    try {
      await this.post<void>('/auth/logout');
    } finally {
      this.accessToken = null;
    }
  }

  /** 서버에는 알리지 않고 메모리의 Access 토큰만 버린다. Refresh 쿠키는 남아 '이 기기에서 이어 하기'에 쓴다 */
  forgetAccess(): void {
    this.accessToken = null;
  }

  private loseSession(): void {
    this.accessToken = null;
    this.onSessionLost();
  }
}

function queryString(query?: Record<string, string | number | undefined>): string {
  if (!query) return '';
  const params = Object.entries(query).filter(([, v]) => v !== undefined && v !== '');
  if (params.length === 0) return '';
  return '?' + params.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join('&');
}

async function unwrap<T>(res: Response): Promise<T> {
  if (res.status === 204) return undefined as T;
  let envelope: Envelope<T>;
  try {
    envelope = (await res.json()) as Envelope<T>;
  } catch {
    throw new ApiError(res.status, 'INTERNAL_ERROR', '서버 응답을 읽지 못했습니다.');
  }
  if (!envelope.success || !res.ok) {
    throw new ApiError(res.status, envelope.error?.code ?? 'INTERNAL_ERROR', envelope.error?.message ?? '요청이 실패했습니다.');
  }
  return envelope.data;
}

/** 오류 코드를 화면 안내 문구로(API 명세 6장) */
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '네트워크 오류가 났습니다. 잠시 뒤 다시 시도해 주세요.';
  const messages: Record<string, string> = {
    INVALID_NICKNAME: '닉네임은 한글·영문·숫자 2~10자로 입력해 주세요.',
    NICKNAME_TAG_EXHAUSTED: '이 닉네임은 더 만들 수 없습니다. 다른 닉네임을 써 주세요.',
    ROOM_NOT_FOUND: '방을 찾을 수 없습니다. 코드를 확인해 주세요.',
    ROOM_FULL: '방이 가득 찼습니다.',
    ROOM_ALREADY_PLAYING: '이미 경기가 시작된 방입니다.',
    ALREADY_IN_ROOM: '이미 다른 방에 들어가 있습니다. 그 방에서 먼저 나와 주세요.',
    NOT_PARTICIPANT: '이 방(경기)의 참가자가 아닙니다.',
    MATCH_NOT_FOUND: '경기를 찾을 수 없습니다.',
    UNAUTHORIZED: '다시 로그인해 주세요.',
    TOKEN_EXPIRED: '로그인이 만료됐습니다. 다시 로그인해 주세요.',
  };
  return messages[error.code] ?? error.message;
}
