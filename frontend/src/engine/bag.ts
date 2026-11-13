import { mulberry32, type Seed } from './rng';
import { PIECE_ORDER, type PieceType } from './types';

/**
 * 7-bag(GR-01, POL-014): 묶음마다 [I,O,T,S,Z,J,L]을 Fisher-Yates(뒤에서부터 j = floor(rand × (i + 1)))로 섞고,
 * 다음 묶음도 같은 생성기를 이어서 쓴다.
 */
export class PieceSequence {
  private readonly rand: () => number;
  private readonly pieces: PieceType[] = [];

  constructor(seed: Seed) {
    this.rand = mulberry32(seed);
  }

  /** 0부터 세는 index번째 블록 */
  at(index: number): PieceType {
    while (this.pieces.length <= index) {
      this.pieces.push(...this.nextBag());
    }
    return this.pieces[index]!;
  }

  slice(from: number, count: number): PieceType[] {
    const result: PieceType[] = [];
    for (let i = 0; i < count; i++) result.push(this.at(from + i));
    return result;
  }

  private nextBag(): PieceType[] {
    const bag = [...PIECE_ORDER];
    for (let i = bag.length - 1; i > 0; i--) {
      const j = Math.floor(this.rand() * (i + 1));
      [bag[i], bag[j]] = [bag[j]!, bag[i]!];
    }
    return bag;
  }
}

export function bagSequence(seed: Seed, count: number): PieceType[] {
  return new PieceSequence(seed).slice(0, count);
}
