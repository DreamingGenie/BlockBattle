/** 게임 규칙 상수(요구사항 GR-01~GR-04). 값은 shared/rules-fixtures/timing.json과 같아야 한다. */

export const BOARD_WIDTH = 10;
export const BOARD_HEIGHT = 22;
/** 위 2행은 화면에 보이지 않는다(GR-01) */
export const HIDDEN_ROWS = 2;

/** 좌우 자동 반복: 처음 167ms 뒤 33ms 간격(GR-02) */
export const DAS_MS = 167;
export const ARR_MS = 33;

/** 레벨별 한 칸 낙하 간격(ms), 인덱스 0 = 1레벨, 15레벨 이상은 마지막 값(GR-03) */
export const GRAVITY_MS = [1000, 793, 618, 473, 355, 262, 190, 135, 94, 64, 43, 28, 18, 11, 7] as const;
export const SOFT_DROP_MS = 50;
export const LINES_PER_LEVEL = 10;

/** 고정 지연 500ms, 블록당 재설정 최대 15번(GR-04) */
export const LOCK_DELAY_MS = 500;
export const MAX_LOCK_RESETS = 15;
export const PREVIEW_COUNT = 5;

/** 생성 위치: 4x4 상자 왼쪽 열 3, 맨 위 행 0(숨김 2행 중앙 3~6열) */
export const SPAWN_X = 3;
export const SPAWN_Y = 0;

export function gravityMs(level: number): number {
  const index = Math.min(Math.max(level, 1), GRAVITY_MS.length) - 1;
  return GRAVITY_MS[index]!;
}

export function levelForLines(totalLines: number): number {
  return 1 + Math.floor(totalLines / LINES_PER_LEVEL);
}
