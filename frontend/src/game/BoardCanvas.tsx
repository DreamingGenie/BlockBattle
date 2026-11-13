import { forwardRef, useImperativeHandle, useRef } from 'react';
import { CELL, drawScene, sceneSize, type BoardScene } from './renderer';

export interface BoardCanvasHandle {
  draw: (scene: BoardScene) => void;
}

/** 매 프레임 draw(scene)로 다시 그리는 캔버스. React 상태를 거치지 않아 프레임마다 다시 렌더링하지 않는다 */
export const BoardCanvas = forwardRef<BoardCanvasHandle, { cell?: number; sides?: boolean; label?: string }>(
  function BoardCanvas({ cell = CELL, sides = true, label }, ref) {
    const canvas = useRef<HTMLCanvasElement>(null);
    const size = sceneSize(cell, sides);
    useImperativeHandle(ref, () => ({
      draw(scene) {
        const ctx = canvas.current?.getContext('2d');
        if (ctx) drawScene(ctx, scene, cell, sides);
      },
    }));
    return <canvas ref={canvas} width={size.width} height={size.height} aria-label={label} className="board" />;
  },
);
