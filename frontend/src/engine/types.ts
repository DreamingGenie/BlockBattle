export type PieceType = 'I' | 'O' | 'T' | 'S' | 'Z' | 'J' | 'L';
/** 0 = 생성 방향, 1 = 시계 방향 한 번(R), 2 = 두 번, 3 = 반시계 한 번(L) */
export type Rotation = 0 | 1 | 2 | 3;
/** '0' 빈칸, 블록 문자, 'G' 방해 줄(CON-001) */
export type Cell = '0' | PieceType | 'G';

export const PIECE_ORDER: readonly PieceType[] = ['I', 'O', 'T', 'S', 'Z', 'J', 'L'];

/** 보드: 22행 × 10열, 맨 위 행부터 왼쪽→오른쪽. index = row * 10 + col */
export type Board = Cell[];

/** 놓인 블록의 위치. x·y는 회전 상자(I·O 4x4, 나머지 3x3)의 왼쪽 위 칸(열·행) */
export interface Placement {
  piece: PieceType;
  rotation: Rotation;
  x: number;
  y: number;
}

/** 마지막 성공 동작. T-스핀 판정은 'rotate'일 때만 성립한다(GR-05) */
export type LastAction = 'rotate' | 'move' | 'none';

/** 대기 방해 줄 한 묶음: 모든 줄의 구멍이 같은 열(GR-08) */
export interface GarbageBundle {
  lines: number;
  holeColumn: number;
}
