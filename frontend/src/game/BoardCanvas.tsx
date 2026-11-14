import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import type { Board, LockEvent } from '../engine';
import { createFx, onGarbageQueued, onLock, onTopout, shakeOffset, stepFx } from './fx';
import { COLORS, CELL, drawScene, sceneSize, type BoardScene } from './renderer';
import { getSettings } from './settings';
import { prepareAudio, sfx } from './sound';

export interface BoardCanvasHandle {
  draw: (scene: BoardScene) => void;
  /** 고정 한 번의 연출과 효과음. combo는 고정 뒤 Game.combo */
  lock: (event: LockEvent, combo: number) => void;
  /** 방해 줄이 대기열에 들어왔다 */
  garbage: () => void;
  topout: () => void;
  /** 새 경기: 남은 연출을 지운다 */
  reset: () => void;
}

interface Props {
  cell?: number;
  sides?: boolean;
  label?: string;
  /** 효과음을 낼지(내 보드만 true) */
  sound?: boolean;
}

/**
 * 매 프레임 draw(scene)로 다시 그리는 캔버스. React 상태를 거치지 않아 프레임마다 다시 렌더링하지 않는다.
 * 연출(REQ-023)은 이 캔버스가 가진 FxState로 그리고, 흔들림은 CSS transform으로 처리한다.
 */
export const BoardCanvas = forwardRef<BoardCanvasHandle, Props>(function BoardCanvas({ cell = CELL, sides = true, label, sound = false }, ref) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const fx = useRef(createFx());
  const lastBoard = useRef<Board | null>(null);
  const lastFrame = useRef(0);
  const lastTransform = useRef('');
  const size = sceneSize(cell, sides);

  // 효과음을 내는 보드면 화면이 열릴 때 AudioContext를 미리 만든다(첫 키 입력 프레임 지연 방지)
  useEffect(() => {
    if (sound) prepareAudio();
  }, [sound]);

  useImperativeHandle(ref, () => ({
    draw(scene) {
      const el = canvas.current;
      const ctx = el?.getContext('2d');
      if (!el || !ctx) return;
      const now = performance.now();
      stepFx(fx.current, now, lastFrame.current ? now - lastFrame.current : 16);
      lastFrame.current = now;
      drawScene(ctx, scene, cell, sides, fx.current, now);
      lastBoard.current = scene.board;
      // 흔들림이 없을 때는 style을 건드리지 않는다(매 프레임 스타일 다시 계산을 피한다)
      const off = getSettings().shake && fx.current.shake ? shakeOffset(fx.current, now) : null;
      const transform = off && (off.x || off.y) ? `translate(${off.x.toFixed(1)}px, ${off.y.toFixed(1)}px)` : '';
      if (transform !== lastTransform.current) {
        el.style.transform = transform;
        lastTransform.current = transform;
      }
    },
    lock(event, combo) {
      const now = performance.now();
      const before = lastBoard.current ?? event.result.board;
      const rows = onLock(fx.current, { placement: event.placement, hardDropCells: event.hardDropCells, result: event.result, boardBefore: before, combo, colors: COLORS }, now);
      // 한 프레임에 고정이 두 번 나와도 다음 계산이 맞도록 고정 뒤 보드를 기억한다
      lastBoard.current = event.result.board;
      if (!sound) return;
      const r = event.result;
      if (event.hardDropCells > 0) sfx.hardDrop(event.hardDropCells);
      else sfx.lock();
      if (rows.length > 0) sfx.clear(rows.length, { tSpin: r.tSpin, b2b: r.backToBack, combo, perfect: r.perfectClear });
      else if (r.tSpin) sfx.tSpinNoClear();
      if (r.garbageRaised > 0) sfx.garbageRise(r.garbageRaised);
    },
    garbage() {
      onGarbageQueued(fx.current, performance.now());
      if (sound) sfx.garbageIncoming();
    },
    topout() {
      if (fx.current.deadAt === null && sound) sfx.gameOver();
      onTopout(fx.current, performance.now());
    },
    reset() {
      fx.current = createFx();
      lastBoard.current = null;
    },
  }));
  return <canvas ref={canvas} width={size.width} height={size.height} aria-label={label} className="board" />;
});
