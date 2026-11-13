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
    expect(Object.keys(body).sort()).toEqual(['holdUsed', 'lastMove', 'linesCleared', 'piece', 'rotation', 'seq', 'x', 'y']);
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
});
