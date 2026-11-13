// 부하 봇용 게임 규칙 일부. frontend/src/engine(rng·bag·pieces·board)과 같은 규칙을 k6에서 쓰려고 옮겼다.
// 서버(backend/engine)·FE·AI가 같은 결과를 내는지는 shared/rules-fixtures로 이미 확인한다. 여기 값이 바뀌면 그쪽부터 고친다.

export const WIDTH = 10;
export const HEIGHT = 22;
export const SPAWN_X = 3;
export const SPAWN_Y = 0;
const ORDER = ['I', 'O', 'T', 'S', 'Z', 'J', 'L'];

/** mulberry32(GR-01): 시드의 하위 32비트를 부호 없는 정수로 쓴다 */
function mulberry32(seed) {
  let a = Number(BigInt.asUintN(32, BigInt(seed))) | 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 7-bag: 묶음마다 ORDER를 Fisher-Yates(뒤에서부터)로 섞는다 */
export class PieceSequence {
  constructor(seed) {
    this.rand = mulberry32(seed);
    this.pieces = [];
  }

  at(index) {
    while (this.pieces.length <= index) {
      const bag = ORDER.slice();
      for (let i = bag.length - 1; i > 0; i--) {
        const j = Math.floor(this.rand() * (i + 1));
        const tmp = bag[i];
        bag[i] = bag[j];
        bag[j] = tmp;
      }
      this.pieces.push(...bag);
    }
    return this.pieces[index];
  }
}

/** [row, col] — 회전 상자 안의 칸(표준 SRS 모양) */
export const SHAPES = {
  I: [[[1, 0], [1, 1], [1, 2], [1, 3]], [[0, 2], [1, 2], [2, 2], [3, 2]], [[2, 0], [2, 1], [2, 2], [2, 3]], [[0, 1], [1, 1], [2, 1], [3, 1]]],
  O: [[[0, 1], [0, 2], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [1, 2]]],
  T: [[[0, 1], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [1, 2], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 1]], [[0, 1], [1, 0], [1, 1], [2, 1]]],
  S: [[[0, 1], [0, 2], [1, 0], [1, 1]], [[0, 1], [1, 1], [1, 2], [2, 2]], [[1, 1], [1, 2], [2, 0], [2, 1]], [[0, 0], [1, 0], [1, 1], [2, 1]]],
  Z: [[[0, 0], [0, 1], [1, 1], [1, 2]], [[0, 2], [1, 1], [1, 2], [2, 1]], [[1, 0], [1, 1], [2, 1], [2, 2]], [[0, 1], [1, 0], [1, 1], [2, 0]]],
  J: [[[0, 0], [1, 0], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 2]], [[0, 1], [1, 1], [2, 0], [2, 1]]],
  L: [[[0, 2], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [2, 1], [2, 2]], [[1, 0], [1, 1], [1, 2], [2, 0]], [[0, 0], [0, 1], [1, 1], [2, 1]]],
};

/** 220자 board 문자열(CON-001, 맨 위 행부터)을 칸 배열로 */
export function parseBoard(value) {
  return value.split('');
}

function blocked(board, r, c) {
  return r < 0 || r >= HEIGHT || c < 0 || c >= WIDTH || board[r * WIDTH + c] !== '0';
}

export function fits(board, piece, rotation, x, y) {
  const cells = SHAPES[piece][rotation];
  for (let i = 0; i < 4; i++) {
    if (blocked(board, y + cells[i][0], x + cells[i][1])) return false;
  }
  return true;
}

export function place(board, piece, rotation, x, y) {
  const next = board.slice();
  for (const [r, c] of SHAPES[piece][rotation]) next[(y + r) * WIDTH + x + c] = piece;
  return next;
}

/** 꽉 찬 줄을 지우고 위를 빈 줄로 채운다 */
export function clearLines(board) {
  const kept = [];
  for (let r = 0; r < HEIGHT; r++) {
    const row = board.slice(r * WIDTH, (r + 1) * WIDTH);
    if (row.indexOf('0') >= 0) kept.push(row);
  }
  const cleared = HEIGHT - kept.length;
  const out = [];
  for (let i = 0; i < cleared; i++) for (let c = 0; c < WIDTH; c++) out.push('0');
  for (const row of kept) out.push(...row);
  return { board: out, cleared };
}
