/** 시상대 칸 하나. row가 없으면 아직 아무도 오르지 않은 빈 자리다 */
export interface PodiumSlot<T> {
  place: 1 | 2 | 3;
  row: T | null;
}

const ORDER = [2, 1, 3] as const;

/**
 * 시상대는 왼쪽부터 2위·1위·3위 세 칸을 늘 그린다. 기록이 1~2개뿐이어도 빈 칸을 남겨
 * 1위가 화면 가운데에서 움직이지 않는다. 기록이 없으면 시상대를 그리지 않는다.
 */
export function podiumSlots<T>(rows: readonly T[]): PodiumSlot<T>[] {
  if (rows.length === 0) return [];
  return ORDER.map((place) => ({ place, row: rows[place - 1] ?? null }));
}

/** 시상대 아래 목록: 4위부터 */
export function listRows<T>(rows: readonly T[]): T[] {
  return rows.slice(3);
}
