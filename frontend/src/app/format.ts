/**
 * 화면에 보일 시간·조사 표기(T-42). '1:54.1만에'·'AI NORMAL를'처럼 읽기 어색한 표현을 막는다.
 */

/** 경기 시간: '45초', '3분 4초', '1시간 2분' */
export function formatDuration(ms: number | null): string {
  if (ms == null) return '-';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return s % 60 ? `${m}분 ${s % 60}초` : `${m}분`;
  return m % 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분` : `${Math.floor(m / 60)}시간`;
}

/** 승리 시간(0.1초까지): '46.7초', '1분 54.1초', '2분 0.4초' */
export function formatWinTime(ms: number): string {
  const tenths = Math.floor(ms / 100);
  const sec = Math.floor(tenths / 10);
  const rest = `${sec % 60}.${tenths % 10}초`;
  return sec < 60 ? rest : `${Math.floor(sec / 60)}분 ${rest}`;
}

/** 영어 난이도 이름의 받침 여부(읽는 소리 기준): 노멀·임파서블은 받침 있음 */
const HAS_FINAL: Record<string, boolean> = { VERY_EASY: false, EASY: false, NORMAL: true, HARD: false, IMPOSSIBLE: true };

/** 난이도 뒤 목적격 조사: 'EASY를', 'NORMAL을' */
export function objectParticle(difficulty: string): '을' | '를' {
  return HAS_FINAL[difficulty] ? '을' : '를';
}
