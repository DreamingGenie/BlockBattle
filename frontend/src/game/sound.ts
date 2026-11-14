/**
 * 효과음(REQ-023). 외부 음원 파일 없이 Web Audio oscillator·noise로 합성한다.
 * 브라우저 자동 재생 정책 때문에 사용자의 첫 키 입력·클릭 뒤에 AudioContext를 연다.
 */
import { getSettings } from './settings';

let ctx: AudioContext | null = null;
let master: GainNode | null = null;
let noiseBuffer: AudioBuffer | null = null;

function audio(): { ctx: AudioContext; out: GainNode } | null {
  const s = getSettings();
  if (s.muted || s.volume <= 0 || !ctx || !master) return null;
  if (ctx.state === 'suspended') ctx.resume().catch(() => {});
  master.gain.value = s.volume * 0.45;
  return { ctx, out: master };
}

/**
 * AudioContext를 미리 만든다(사용자 입력 전이면 멈춤 상태로 만들어진다).
 * 만드는 데 수십 ms가 걸려 첫 키 입력 때 만들면 그 프레임이 늦어지므로(NFR-06), 게임 화면이 열릴 때 부른다.
 */
export function prepareAudio(): void {
  if (ctx) return;
  try {
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AC) return;
    ctx = new AC();
    master = ctx.createGain();
    // 큰 소리가 겹쳐도 찢어지지 않게 마지막에 압축한다
    const comp = ctx.createDynamicsCompressor();
    master.connect(comp);
    comp.connect(ctx.destination);
    noiseBuffer = ctx.createBuffer(1, ctx.sampleRate * 0.5, ctx.sampleRate);
    const data = noiseBuffer.getChannelData(0);
    for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
  } catch {
    ctx = null;
  }
}

/** 첫 사용자 입력에서 부른다. 미리 만든 AudioContext를 깨우기만 한다 */
export function unlockAudio(): void {
  prepareAudio();
  if (ctx?.state === 'suspended') ctx.resume().catch(() => {});
}

if (typeof window !== 'undefined') {
  const once = () => {
    unlockAudio();
    window.removeEventListener('keydown', once);
    window.removeEventListener('pointerdown', once);
  };
  window.addEventListener('keydown', once);
  window.addEventListener('pointerdown', once);
}

interface ToneOptions {
  type?: OscillatorType;
  gain?: number;
  slideTo?: number;
  delay?: number;
}

function tone(freq: number, duration: number, { type = 'square', gain = 0.2, slideTo, delay = 0 }: ToneOptions = {}) {
  const a = audio();
  if (!a) return;
  const t = a.ctx.currentTime + delay;
  const osc = a.ctx.createOscillator();
  const g = a.ctx.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t);
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t + duration);
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(gain, t + 0.005);
  g.gain.exponentialRampToValueAtTime(0.0001, t + duration);
  osc.connect(g);
  g.connect(a.out);
  osc.start(t);
  osc.stop(t + duration + 0.02);
}

function noise(duration: number, { filter = 800, gain = 0.3, delay = 0, type = 'lowpass' as BiquadFilterType } = {}) {
  const a = audio();
  if (!a || !noiseBuffer) return;
  const t = a.ctx.currentTime + delay;
  const src = a.ctx.createBufferSource();
  src.buffer = noiseBuffer;
  const f = a.ctx.createBiquadFilter();
  f.type = type;
  f.frequency.value = filter;
  const g = a.ctx.createGain();
  g.gain.setValueAtTime(gain, t);
  g.gain.exponentialRampToValueAtTime(0.0001, t + duration);
  src.connect(f);
  f.connect(g);
  g.connect(a.out);
  src.start(t);
  src.stop(t + duration + 0.02);
}

const semis = (base: number, n: number) => base * Math.pow(2, n / 12);

export const sfx = {
  move() {
    tone(520, 0.03, { type: 'triangle', gain: 0.06 });
  },
  rotate() {
    tone(700, 0.045, { type: 'triangle', gain: 0.07, slideTo: 880 });
  },
  hold() {
    tone(440, 0.08, { type: 'sine', gain: 0.12, slideTo: 660 });
  },
  lock() {
    noise(0.05, { filter: 1800, gain: 0.12 });
    tone(180, 0.05, { type: 'sine', gain: 0.12 });
  },
  hardDrop(cells: number) {
    const strength = Math.min(1, 0.4 + cells / 20);
    noise(0.12, { filter: 600, gain: 0.35 * strength });
    tone(140, 0.12, { type: 'sine', gain: 0.3 * strength, slideTo: 60 });
  },
  /** 줄 삭제: 줄 수가 많을수록 높고 길게, 콤보마다 반음씩 올린다 */
  clear(lines: number, opts: { tSpin: boolean; b2b: boolean; combo: number; perfect: boolean }) {
    const base = semis(392, Math.min(Math.max(opts.combo, 0), 12));
    const steps = [0, 4, 7, 12, 16].slice(0, Math.max(2, lines + 1));
    steps.forEach((s, i) => tone(semis(base, s), 0.12 + lines * 0.03, { type: 'square', gain: 0.12, delay: i * 0.045 }));
    noise(0.18 + lines * 0.05, { filter: 3000, gain: 0.12, type: 'highpass' });
    if (lines >= 4 || opts.tSpin) {
      tone(semis(base, -12), 0.35, { type: 'sawtooth', gain: 0.1, slideTo: semis(base, 12) });
    }
    if (opts.b2b) tone(semis(base, 24), 0.2, { type: 'triangle', gain: 0.1, delay: 0.18 });
    if (opts.perfect) [0, 4, 7, 11, 14].forEach((s, i) => tone(semis(523, s), 0.6, { type: 'triangle', gain: 0.1, delay: 0.2 + i * 0.07 }));
  },
  tSpinNoClear() {
    tone(660, 0.12, { type: 'triangle', gain: 0.12, slideTo: 990 });
  },
  garbageRise(lines: number) {
    noise(0.25, { filter: 300, gain: 0.3 + Math.min(lines, 8) * 0.03 });
    tone(110, 0.25, { type: 'sawtooth', gain: 0.12, slideTo: 70 });
  },
  garbageIncoming() {
    tone(330, 0.08, { type: 'square', gain: 0.08 });
    tone(262, 0.1, { type: 'square', gain: 0.08, delay: 0.09 });
  },
  gameOver() {
    [0, -3, -7, -12].forEach((s, i) => tone(semis(330, s), 0.25, { type: 'triangle', gain: 0.14, delay: i * 0.14 }));
  },
  win() {
    [0, 4, 7, 12].forEach((s, i) => tone(semis(523, s), 0.22, { type: 'square', gain: 0.1, delay: i * 0.1 }));
  },
  /** 카운트다운 3·2·1은 낮게, 0(시작)은 높게 */
  countdown(n: number) {
    if (n > 0) tone(440, 0.12, { type: 'square', gain: 0.12 });
    else tone(880, 0.3, { type: 'square', gain: 0.14 });
  },
};
