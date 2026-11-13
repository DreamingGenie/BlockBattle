import { fits } from './board';
import { kicksFor } from './pieces';
import type { Board, Placement, Rotation } from './types';

export type RotateDirection = 'CW' | 'CCW';

/**
 * SRS 회전(GR-04): 벽 차기 후보를 순서대로 시도해 처음 들어맞는 자리를 돌려준다. 모두 막히면 null.
 * 킥의 dy는 위쪽이 + 라서 보드 행에는 y - dy로 적용한다.
 */
export function tryRotate(board: Board, p: Placement, direction: RotateDirection): Placement | null {
  const to = ((p.rotation + (direction === 'CW' ? 1 : 3)) % 4) as Rotation;
  for (const [dx, dy] of kicksFor(p.piece, p.rotation, to)) {
    const candidate: Placement = { ...p, rotation: to, x: p.x + dx, y: p.y - dy };
    if (fits(board, candidate)) return candidate;
  }
  return null;
}
