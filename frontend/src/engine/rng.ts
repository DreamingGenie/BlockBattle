/**
 * mulberry32 난수 생성기(GR-01, POL-014).
 * 시드는 BIGINT의 하위 32비트를 부호 없는 정수로 해석한다. FE·BE·AI가 같은 수열을 만들어야 한다.
 */
export type Seed = number | bigint | string;

export function seedToUint32(seed: Seed): number {
  return Number(BigInt.asUintN(32, BigInt(seed)));
}

export function mulberry32(seed: Seed): () => number {
  let a = seedToUint32(seed) | 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
