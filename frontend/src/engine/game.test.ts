/**
 * 게임 상태 기계의 시간·조작 규칙(GR-02~GR-04)과 결정론.
 * 깨지면: 같은 시드·입력에서 화면마다 다른 게임이 되거나, 낙하·고정·홀드·topout이 승인 규칙과 어긋났다는 뜻이다.
 */
import { describe, expect, it } from 'vitest';
import { bagSequence } from './bag';
import { boardFromBottomRows, fits } from './board';
import { Game, type LockEvent } from './game';

function play(game: Game, script: string): void {
  for (const op of script.split(' ')) {
    if (op === 'L') game.moveLeft();
    else if (op === 'R') game.moveRight();
    else if (op === 'CW') game.rotateCW();
    else if (op === 'CCW') game.rotateCCW();
    else if (op === 'H') game.holdPiece();
    else if (op === 'D') game.hardDrop();
    else if (op.startsWith('t')) game.tick(Number(op.slice(1)));
  }
}

/** 소프트 드롭으로 바닥에 막 닿게 한다(고정 타이머는 아직 시작 전) */
function softDropToFloor(game: Game): void {
  const current = game.current!;
  let landing = current.y;
  while (fits(game.board, { ...current, y: landing + 1 })) landing++;
  game.setSoftDrop(true);
  game.tick(50 * (landing - current.y));
  game.setSoftDrop(false);
}

const SCRIPT ='L L CW D R R R D H D CCW t1200 L D t3000 R CW CW D H D D t700 D';

describe('결정론', () => {
  it('같은 시드·같은 입력이면 상태가 같다', () => {
    const a = new Game('123456789');
    const b = new Game(123456789n);
    play(a, SCRIPT);
    play(b, SCRIPT);
    expect(a.snapshot()).toEqual(b.snapshot());
    expect(a.drainEvents()).toEqual(b.drainEvents());
  });

  it('블록은 시드의 7-bag 순서대로 나오고 다음 5개를 보여 준다(GR-01·GR-04)', () => {
    const game = new Game(42);
    const order = bagSequence(42, 6);
    expect(game.current?.piece).toBe(order[0]);
    expect(game.preview()).toEqual(order.slice(1, 6));
  });
});

describe('생성·홀드·topout(GR-04)', () => {
  it('새 블록은 숨김 2행 중앙(상자 왼쪽 열 3, 행 0)에 생성 방향으로 나온다', () => {
    const game = new Game(0);
    expect(game.current).toMatchObject({ rotation: 0, x: 3, y: 0 });
  });

  it('홀드는 블록 하나가 고정될 때까지 한 번만 쓸 수 있다', () => {
    const game = new Game(0);
    const [first, second] = bagSequence(0, 2);
    expect(game.holdPiece()).toBe(true);
    expect(game.hold).toBe(first);
    expect(game.current?.piece).toBe(second);
    expect(game.holdPiece()).toBe(false);
    game.hardDrop();
    expect(game.holdPiece()).toBe(true);
    expect(game.current?.piece).toBe(first);
  });

  it('lock 이벤트에 홀드 사용 여부와 seq가 실린다(BR-05 입력)', () => {
    const game = new Game(0);
    game.holdPiece();
    game.hardDrop();
    game.hardDrop();
    const locks = game.drainEvents().filter((e): e is LockEvent => e.type === 'lock');
    expect(locks.map((e) => [e.seq, e.holdUsed])).toEqual([[1, true], [2, false]]);
  });

  it('생성 자리가 막히면 topout으로 끝난다', () => {
    const game = new Game(0);
    for (let i = 0; i < 40 && !game.gameOver; i++) game.hardDrop();
    expect(game.gameOver).toBe(true);
    expect(game.drainEvents().at(-1)).toEqual({ type: 'topout' });
  });
});

describe('낙하·고정(GR-03·GR-04)', () => {
  it('1레벨은 1000ms마다 한 칸 내려간다', () => {
    const game = new Game(0);
    game.tick(999);
    expect(game.current?.y).toBe(0);
    game.tick(1);
    expect(game.current?.y).toBe(1);
  });

  it('소프트 드롭은 50ms마다 한 칸이고 칸당 1점이다', () => {
    const game = new Game(0);
    game.setSoftDrop(true);
    game.tick(500);
    expect(game.current?.y).toBe(10);
    game.setSoftDrop(false);
    game.hardDrop();
    const lock = game.drainEvents()[0] as LockEvent;
    expect(lock.result.score).toBe(10 + 2 * (lock.placement.y - 10));
  });

  it('바닥에 닿으면 500ms 뒤 고정된다', () => {
    const game = new Game(0);
    softDropToFloor(game);
    expect(game.drainEvents()).toHaveLength(0);
    game.tick(499);
    expect(game.drainEvents()).toHaveLength(0);
    game.tick(1);
    expect(game.drainEvents().filter((e) => e.type === 'lock')).toHaveLength(1);
  });

  it('바닥에서 이동하면 500ms를 다시 세지만 블록당 15번까지만', () => {
    const game = new Game(0);
    softDropToFloor(game);
    for (let i = 0; i < 15; i++) {
      game.tick(400);
      expect(i % 2 === 0 ? game.moveLeft() : game.moveRight()).toBe(true);
    }
    expect(game.drainEvents()).toHaveLength(0);
    game.tick(400);
    game.moveLeft();
    game.tick(100);
    expect(game.drainEvents().filter((e) => e.type === 'lock')).toHaveLength(1);
  });

  it('받은 방해 줄은 줄을 못 지운 고정 때 올라온다(GR-08)', () => {
    const game = new Game(0);
    game.receiveGarbage({ lines: 2, holeColumn: 4 });
    expect(game.attackReceived).toBe(2);
    game.hardDrop();
    const bottom = game.board.slice(200).join('');
    expect(bottom).toBe('GGGG0GGGGG'.repeat(2));
    expect(game.pending).toEqual([]);
  });
});

describe('보드 문자열(CON-001)', () => {
  it('220자가 아니거나 허용 문자 밖이면 거부한다', () => {
    expect(() => boardFromBottomRows(['GGGGGGGGGX'])).toThrow();
  });
});
