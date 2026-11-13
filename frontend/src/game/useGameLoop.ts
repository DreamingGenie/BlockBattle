import { useEffect, useRef } from 'react';

/**
 * requestAnimationFrame 루프(T-19). 매 프레임 직전 프레임부터 흐른 ms를 넘긴다.
 * 탭이 숨겨졌다 돌아오면 dt가 커지므로 한 프레임에 최대 100ms만 흘린다(블록이 순간 이동하지 않게).
 */
export function useGameLoop(onFrame: (dt: number) => void, running: boolean): void {
  const callback = useRef(onFrame);
  callback.current = onFrame;

  useEffect(() => {
    if (!running) return;
    let last = performance.now();
    let id = requestAnimationFrame(function frame(now) {
      const dt = Math.min(100, now - last);
      last = now;
      callback.current(dt);
      id = requestAnimationFrame(frame);
    });
    return () => cancelAnimationFrame(id);
  }, [running]);
}
