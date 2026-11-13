// 부하 봇의 배치 선택. 서버 검증(POL-005: 겹치지 않고 바로 아래가 막힌 자리)을 통과하는 자리만 고른다.
// k6 VU 하나가 봇 둘을 돌리므로 계산이 무거우면 수신 처리가 밀려 지연 측정이 오염된다(T-17 진단: 봇 1회 30ms → k6 CPU 18코어).
// 그래서 보드를 0/1 숫자 배열로 다루고, 후보는 위에서 떨어뜨려 닿는 자리(회전·열마다 1개)만 본다.
import { HEIGHT, SPAWN_X, SPAWN_Y, WIDTH, SHAPES } from './engine.js';

const WELL = 9; // 우물 열 — 수비 때 비워 두고 공격 때 세로로 넣어 여러 줄을 지운다
const DANGER_HEIGHT = 10;

/** 220자 board 문자열 → 0/1 배열 */
export function toGrid(boardString) {
  const g = new Uint8Array(WIDTH * HEIGHT);
  for (let i = 0; i < g.length; i++) g[i] = boardString.charCodeAt(i) === 48 ? 0 : 1;
  return g;
}

// 회전·블록별 칸 목록과 열별 가장 아래 칸(착지 계산용)을 미리 만든다
const PREP = {};
for (const piece of Object.keys(SHAPES)) {
  PREP[piece] = SHAPES[piece].map((cells) => {
    const bottom = {};
    for (const [r, c] of cells) bottom[c] = Math.max(bottom[c] === undefined ? -1 : bottom[c], r);
    const flat = [];
    for (const [r, c] of cells) flat.push(r, c);
    const colFlat = [];
    for (const c of Object.keys(bottom)) colFlat.push(Number(c), bottom[c]);
    return { flat, colFlat };
  });
}

/** El-Tetris(Dellacherie 계열) 점수 요소. 우물 열은 행 전환·우물 합에서 '채운 칸'으로 본다 */
function evaluate(g, fromRow) {
  let rowTransitions = 0;
  for (let r = fromRow; r < HEIGHT; r++) {
    let prev = 1;
    const base = r * WIDTH;
    for (let c = 0; c < WIDTH; c++) {
      const cur = c === WELL ? 1 : g[base + c];
      if (cur !== prev) rowTransitions++;
      prev = cur;
    }
    if (prev !== 1) rowTransitions++;
  }
  let columnTransitions = 0;
  let holes = 0;
  let wells = 0;
  let maxHeight = 0;
  let wellCells = 0;
  for (let c = 0; c < WIDTH; c++) {
    let prev = 0;
    let seen = false;
    let depth = 0;
    for (let r = fromRow; r < HEIGHT; r++) {
      const cur = g[r * WIDTH + c];
      if (cur !== prev && r > fromRow) columnTransitions++;
      if (cur) {
        if (!seen) {
          seen = true;
          if (HEIGHT - r > maxHeight) maxHeight = HEIGHT - r;
        }
        if (c === WELL) wellCells++;
        depth = 0;
      } else {
        if (seen) holes++;
        if (c !== WELL) {
          const left = c === 0 || c - 1 === WELL ? 1 : g[r * WIDTH + c - 1];
          const right = c === WIDTH - 1 || c + 1 === WELL ? 1 : g[r * WIDTH + c + 1];
          if (left && right) {
            depth++;
            wells += depth;
          } else {
            depth = 0;
          }
        }
      }
      prev = cur;
    }
    if (prev === 0) columnTransitions++; // 바닥은 채운 칸으로 본다
  }
  return { rowTransitions, columnTransitions, holes, wells, maxHeight, wellCells };
}

/** 서버 topout 판정과 같은 조건: 다음 블록이 생성 자리(회전 0, x 3, y 0)에 들어가는가 */
function spawnFits(g, piece) {
  for (const [r, c] of SHAPES[piece][0]) {
    if (g[(SPAWN_Y + r) * WIDTH + SPAWN_X + c]) return false;
  }
  return true;
}

/** 가장 좋은 자리 {rotation, x, y, lines}. 생성 자리가 막혔으면 null(topout) */
export function choose(grid, piece, role, combo) {
  if (!spawnFits(grid, piece)) return null;
  const tops = new Int16Array(WIDTH);
  for (let c = 0; c < WIDTH; c++) {
    let r = 0;
    while (r < HEIGHT && !grid[r * WIDTH + c]) r++;
    tops[c] = r; // 맨 위 채운 칸의 행(없으면 HEIGHT)
  }
  // 모든 후보를 같은 행 범위로 평가한다: 지금 맨 위 칸보다 5행 위(블록 높이 4 + 1)부터. 그 위는 모든 후보에서 빈 행이라 순위에 영향이 없다
  let minTop = HEIGHT;
  for (let c = 0; c < WIDTH; c++) if (tops[c] < minTop) minTop = tops[c];
  const fromRow = Math.max(0, minTop - 5);
  const work = new Uint8Array(WIDTH * HEIGHT);
  let best = null;
  const rotations = piece === 'O' ? 1 : 4;
  for (let rotation = 0; rotation < rotations; rotation++) {
    const { flat, colFlat } = PREP[piece][rotation];
    for (let x = -2; x < WIDTH; x++) {
      let y = HEIGHT;
      let inside = true;
      for (let k = 0; k < colFlat.length; k += 2) {
        const col = x + colFlat[k];
        if (col < 0 || col >= WIDTH) {
          inside = false;
          break;
        }
        const landY = tops[col] - colFlat[k + 1] - 1;
        if (landY < y) y = landY;
      }
      if (!inside || y < 0) continue;
      // 위에서 떨어뜨린 자리라 겹침이 없고 바로 아래가 막혀 있다(서버 검증 ③)
      work.set(grid);
      for (let k = 0; k < 8; k += 2) work[(y + flat[k]) * WIDTH + x + flat[k + 1]] = 1;
      let lines = 0;
      for (let r = HEIGHT - 1; r >= 0; r--) {
        let full = true;
        for (let c = 0; c < WIDTH; c++) {
          if (!work[r * WIDTH + c]) {
            full = false;
            break;
          }
        }
        if (full) {
          lines++;
          work.copyWithin(WIDTH, 0, r * WIDTH); // 위 줄들을 한 칸 내린다
          work.fill(0, 0, WIDTH);
          r++; // 같은 행을 다시 본다
        }
      }
      const f = evaluate(work, fromRow);
      const landing = HEIGHT - y - 1.5;
      let score = -4.5 * landing - 3.2 * f.rowTransitions - 9.3 * f.columnTransitions - 7.9 * f.holes - 3.4 * f.wells;
      if (f.maxHeight >= DANGER_HEIGHT) {
        score += 9.4 * lines; // 위험하면 무엇이든 지운다
      } else {
        score -= 6 * f.wellCells; // 우물 열은 여러 줄 삭제에만 쓴다
        if (role === 'attack' && (lines >= 3 || (lines === 2 && combo >= 0))) score += 40; // 2줄 이상 공격
        else if (lines > 0) score -= 6; // 공격이 안 되는 삭제는 아낀다(수비 때는 상쇄도 줄인다)
      }
      if (best === null || score > best.score) best = { rotation, x, y, lines, score };
    }
  }
  return best;
}
