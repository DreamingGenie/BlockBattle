/**
 * 봇 판정용 입력 요약값(T-40). 원본 기록은 보내지 않고 판마다 요약값만 결과와 함께 서버로 보낸다.
 * - intervalCv: 블록을 놓은 간격의 변동계수(표준편차 ÷ 평균). 사람은 들쭉날쭉(대개 0.3 이상), 고정 지연 봇은 0에 가깝다.
 * - holdMeanMs·holdCv: 실제 키(isTrusted)를 누르고 있던 시간의 평균과 변동계수. 콘솔 스크립트는 실제 키를 만들 수 없다.
 * - inputsPerPiece: 블록 하나당 게임 키 입력 수. 최단 입력만 쓰는 봇은 이론상 최솟값에 붙는다.
 * 시계는 호출자가 넘긴다(시험에서 시각을 정할 수 있게).
 */
export interface InputStats {
  intervalCv: number | null;
  holdMeanMs: number | null;
  holdCv: number | null;
  inputsPerPiece: number | null;
}

const MAX_SAMPLES = 2000;

function meanAndCv(xs: number[]): { mean: number; cv: number } | null {
  if (xs.length < 2) return null;
  const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
  if (mean <= 0) return null;
  const variance = xs.reduce((a, b) => a + (b - mean) ** 2, 0) / xs.length;
  return { mean, cv: Math.sqrt(variance) / mean };
}

const round = (x: number, digits: number) => Math.round(x * 10 ** digits) / 10 ** digits;

export class InputStatsRecorder {
  private lockTimes: number[] = [];
  private holds: number[] = [];
  private down = new Map<string, number>();

  reset(): void {
    this.lockTimes = [];
    this.holds = [];
    this.down.clear();
  }

  keyDown(code: string, t: number): void {
    if (!this.down.has(code)) this.down.set(code, t);
  }

  keyUp(code: string, t: number): void {
    const start = this.down.get(code);
    this.down.delete(code);
    // 창이 포커스를 잃는 등으로 1분 넘게 눌린 것으로 잡힌 값은 버린다
    if (start !== undefined && t >= start && t - start < 60_000 && this.holds.length < MAX_SAMPLES) this.holds.push(t - start);
  }

  lock(t: number): void {
    if (this.lockTimes.length < MAX_SAMPLES) this.lockTimes.push(t);
  }

  summary(keyPresses: number, piecesPlaced: number): InputStats {
    const intervals: number[] = [];
    for (let i = 1; i < this.lockTimes.length; i++) intervals.push(this.lockTimes[i]! - this.lockTimes[i - 1]!);
    const interval = meanAndCv(intervals);
    const hold = meanAndCv(this.holds);
    return {
      intervalCv: interval ? round(interval.cv, 3) : null,
      holdMeanMs: hold ? Math.round(hold.mean) : null,
      holdCv: hold ? round(hold.cv, 3) : null,
      inputsPerPiece: piecesPlaced > 0 ? round(keyPresses / piecesPlaced, 2) : null,
    };
  }
}
