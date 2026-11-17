/** POST /presence/heartbeat 응답(T-49) */
export interface PresenceCounts {
  online: number;
  playing: number;
}

/** 클라이언트가 heartbeat를 보내는 간격. 서버는 70초 안에 온 사람만 센다 */
export const HEARTBEAT_MS = 20_000;

export type LiveTone = 'on' | 'idle' | 'wait';

export interface LiveLabel {
  tone: LiveTone;
  /** 크게 보이는 값. 확인 중이면 null */
  playing: number | null;
  text: string;
  /** 보조 글씨(접속 수). 확인 중이면 null */
  sub: string | null;
}

/**
 * 상단 바 인원 칩의 표시. 첫 응답 전이거나 마지막 heartbeat가 실패했으면 숫자 대신 '확인 중'을 보인다
 * (오래된 숫자를 지금 값처럼 보이지 않게).
 */
export function liveLabel(counts: PresenceCounts | null, failed: boolean): LiveLabel {
  if (!counts || failed) return { tone: 'wait', playing: null, text: '인원 확인 중…', sub: null };
  const sub = `접속 ${counts.online.toLocaleString('ko-KR')}명`;
  if (counts.playing === 0) return { tone: 'idle', playing: 0, text: '플레이 중인 사람 없음', sub };
  return { tone: 'on', playing: counts.playing, text: '명 플레이 중', sub };
}
