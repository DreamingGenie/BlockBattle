import { describe, expect, it } from 'vitest';
import { Game, PieceSequence, boardFromBottomRows, boardToString, type LockEvent } from '../engine';
import { lockMessage, restoreArgs } from './friend';

/** API 명세 5.2 lock 본문과 5.7 RESYNC 복원. 깨지면 서버가 정상 lock을 INVALID_LOCK으로 거부하거나 재접속 뒤 보드가 어긋난다 */
describe('lockMessage', () => {
  it('엔진 lock 이벤트를 서버 lock 본문으로 바꾼다', () => {
    const game = new Game(11);
    game.rotateCW();
    game.hardDrop();
    const event = game.drainEvents().find((e): e is LockEvent => e.type === 'lock')!;
    const body = lockMessage(event);
    expect(body).toMatchObject({ seq: 1, piece: event.placement.piece, rotation: event.placement.rotation, holdUsed: false, linesCleared: 0 });
    expect(body.lastMove).toBe('MOVE'); // 회전 뒤 하드 드롭으로 내려왔으면 마지막 동작은 이동
    expect(Object.keys(body).sort()).toEqual([
      'hardDropCells', 'holdUsed', 'lastMove', 'linesCleared', 'piece', 'rotation', 'seq', 'softDropCells', 'x', 'y',
    ]);
    // 생성 행(y 0)에서 바닥까지 하드 드롭했으므로 하드 칸 수는 고정 y와 같다(서버 상한 안, POL-005 v2)
    expect(body.softDropCells).toBe(0);
    expect(body.hardDropCells).toBe(event.placement.y);
  });

  it('소프트 드롭으로 내려온 칸 수도 보낸다', () => {
    const game = new Game(11);
    game.softDropStep();
    game.softDropStep();
    game.hardDrop();
    const body = lockMessage(game.drainEvents().find((e): e is LockEvent => e.type === 'lock')!);
    expect(body.softDropCells).toBe(2);
    expect(body.softDropCells + body.hardDropCells).toBe(body.y);
  });
});

describe('Game.restore', () => {
  it('서버 보드·seq·지금 놓을 블록·홀드·대기열로 되돌린다', () => {
    const seed = 99;
    const game = new Game(seed);
    for (let i = 0; i < 3; i++) game.hardDrop();
    const serverBoard = boardToString(boardFromBottomRows(['GGGGGGGGG0', 'GGGG0GGGGG']));
    game.restore(restoreArgs({ matchId: 1, seq: 7, pieceIndex: 9, hold: 'T', holdUsed: false, board: serverBoard, pendingGarbage: [{ lines: 2, holeColumn: 4 }] }));
    const sequence = new PieceSequence(seed);
    expect(boardToString(game.board)).toBe(serverBoard);
    expect(game.seq).toBe(7);
    expect(game.current?.piece).toBe(sequence.at(9));
    expect(game.preview()[0]).toBe(sequence.at(10));
    expect(game.hold).toBe('T');
    expect(game.pending).toEqual([{ lines: 2, holeColumn: 4 }]);
    game.hardDrop(); // 이어서 놓으면 seq 8
    expect(game.seq).toBe(8);
  });

  /** POL-006 v2. 깨지면: 경기 중 새로고침한 화면이 다른 블록 순서로 이어 가 서버가 lock을 거부하거나 내 성적이 0으로 보인다 */
  it('시드만 있는 새 엔진도 RESYNC로 같은 블록 순서와 서버 성적을 되살린다', () => {
    const seed = 551203;
    const fresh = new Game(seed);
    const serverBoard = boardToString(boardFromBottomRows(['GGGGGGGGG0']));
    fresh.restore(restoreArgs({
      matchId: 3, seq: 12, pieceIndex: 14, hold: null, holdUsed: false, board: serverBoard, pendingGarbage: [],
      seed, me: { score: 12400, linesCleared: 41, level: 5, combo: 1, b2b: true },
    }));
    const sequence = new PieceSequence(seed);
    expect(fresh.current?.piece).toBe(sequence.at(14));
    expect(fresh.preview()).toEqual(sequence.slice(15, 5));
    expect(fresh.score).toBe(12400);
    expect(fresh.linesCleared).toBe(41);
    expect(fresh.level).toBe(5);
    expect(fresh.combo).toBe(1);
    expect(fresh.snapshot().b2b).toBe(true);
  });
});
