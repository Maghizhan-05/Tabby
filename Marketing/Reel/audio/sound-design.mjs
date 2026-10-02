#!/usr/bin/env node
// Synthesized sound design for the Tabby reel, timed to reel.html's timeline.
// Pure Node (no dependencies): writes a 48 kHz stereo 16-bit WAV.
//
//   node audio/sound-design.mjs out/tabby-reel-audio.wav [launch15|story30]
//
// Palette, all in A major so the whole film resolves home:
//   0.00  warm pluck (A4) + quiet rising air sweep          — the hook
//   1.55  soft tap · sheet whoosh · 3 keypad clicks · taps  — the small action
//   4.80  dry confirmation tick                             — "Lock it in"
//   5.50  soft resolved bell (A5/E5) + warm bass pulse in   — the payoff
//   8.44 / 9.94  two rhythmic accents · 10.36 tonal lift    — the bigger picture
//  12.10  two-note signature E5 → A5, left to ring out      — the brand close
import { writeFileSync } from 'node:fs';
import '../cuts.js';

const CUT = globalThis.TABBY_CUTS[process.argv[3] || 'launch15'];
if (!CUT) throw new Error(`unknown cut: ${process.argv[3]}`);
const T = CUT.T;
const SR = 48000, DUR = CUT.duration, N = SR * DUR;
const L = new Float32Array(N), R = new Float32Array(N);
const sendL = new Float32Array(N), sendR = new Float32Array(N);   // reverb bus
const TAU = Math.PI * 2;
const hz = midi => 440 * Math.pow(2, (midi - 69) / 12);
let seed = 7;
const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1;

function add(i, l, r, rev = 0) {
  if (i < 0 || i >= N) return;
  L[i] += l; R[i] += r; sendL[i] += l * rev; sendR[i] += r * rev;
}
const pan = (x, p) => [x * Math.cos((p + 1) * Math.PI / 4), x * Math.sin((p + 1) * Math.PI / 4)];

/** Plucked tone: few harmonics, upper ones decay faster (warm, not bright). */
function pluck(t0, f, { gain = 0.3, decay = 1.2, p = 0, rev = 0.35, len = 3.2, bright = 1 } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor(len * SR);
  const parts = [[1, 1, 1], [2, 0.42 * bright, 1.8], [3, 0.16 * bright, 2.8], [4, 0.07 * bright, 4]];
  for (let k = 0; k < n; k++) {
    const t = k / SR, att = Math.min(1, t / 0.004);
    let v = 0;
    for (const [m, a, dk] of parts) v += a * Math.sin(TAU * f * m * t) * Math.exp(-t * dk / decay);
    const [l, r] = pan(v * gain * att, p);
    add(s0 + k, l, r, rev);
  }
}

/** Soft bell: inharmonic partials, long tail. */
function bell(t0, f, { gain = 0.16, decay = 2.2, p = 0, rev = 0.55, len = 3.5 } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor(len * SR);
  const parts = [[1, 1, 1], [2.0, 0.5, 1.6], [2.76, 0.22, 2.4], [5.4, 0.08, 4.5]];
  for (let k = 0; k < n; k++) {
    const t = k / SR, att = Math.min(1, t / 0.006);
    let v = 0;
    for (const [m, a, dk] of parts) v += a * Math.sin(TAU * f * m * t + m) * Math.exp(-t * dk / decay);
    const [l, r] = pan(v * gain * att, p);
    add(s0 + k, l, r, rev);
  }
}

/** One-pole low/high pass helpers over noise for clicks and air. */
function noiseBurst(t0, { len = 0.012, gain = 0.2, hp = 0.6, tone = 0, toneF = 2400, p = 0, rev = 0.08, decay = 0.004 } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor(len * SR);
  let prev = 0, lp = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR, env = Math.exp(-t / decay) * Math.min(1, t / 0.0006);
    const x = rnd(); const h = x - prev * hp; prev = x;     // crude high-pass
    lp += (h - lp) * 0.55;
    const v = (lp * (1 - tone) + Math.sin(TAU * toneF * t) * tone) * env * gain;
    const [l, r] = pan(v, p);
    add(s0 + k, l, r, rev);
  }
}

/** Filtered-noise sweep (state-variable band-pass with a moving centre). */
function sweep(t0, t1, f0, f1, { gain = 0.06, q = 0.9, p = 0, rev = 0.4, shape = 'swell' } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor((t1 - t0) * SR);
  let low = 0, band = 0;
  for (let k = 0; k < n; k++) {
    const u = k / n, fc = f0 * Math.pow(f1 / f0, u);
    const F = 2 * Math.sin(Math.PI * Math.min(fc, SR / 6) / SR);
    const x = rnd();
    low += F * band; const high = x - low - q * band; band += F * high;
    const env = shape === 'swell' ? Math.pow(Math.sin(Math.PI * Math.min(1, u * 1.15)), 2) * (u < 0.87 ? 1 : (1 - u) / 0.13)
      : Math.sin(Math.PI * u) ** 1.5;
    const [l, r] = pan(band * env * gain, p + (u - 0.5) * 0.5);
    add(s0 + k, l, r, rev);
  }
}

/** Sustained pad: detuned sines with slow swell, the quiet music bed. */
function pad(t0, t1, notes, { gain = 0.03, fadeIn = 1.0, fadeOut = 0.8, rev = 0.5 } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor((t1 - t0) * SR);
  for (let k = 0; k < n; k++) {
    const t = k / SR, env = Math.min(1, t / fadeIn) * Math.min(1, (t1 - t0 - t) / fadeOut);
    let l = 0, r = 0;
    notes.forEach((m, i) => {
      const f = hz(m), wob = 1 + 0.0025 * Math.sin(TAU * 0.21 * t + i);
      l += Math.sin(TAU * f * 0.998 * wob * t + i) + 0.25 * Math.sin(TAU * f * 2 * t);
      r += Math.sin(TAU * f * 1.002 * wob * t + i * 1.7) + 0.25 * Math.sin(TAU * f * 2.003 * t);
    });
    add(s0 + k, l * gain * env, r * gain * env, rev);
  }
}

/** Warm bass pulse: sine + soft 2nd harmonic with a gentle pitch drop. */
function bassHit(t0, f, { gain = 0.26, len = 0.55 } = {}) {
  const s0 = Math.floor(t0 * SR), n = Math.floor(len * SR);
  for (let k = 0; k < n; k++) {
    const t = k / SR, env = Math.min(1, t / 0.008) * Math.exp(-t / 0.2);
    const ff = f * (1 + 0.12 * Math.exp(-t / 0.03));
    const v = (Math.sin(TAU * ff * t) + 0.45 * Math.sin(TAU * 2 * ff * t)) * env * gain;
    add(s0 + k, v, v, 0.05);
  }
}

/* ───────────── Score (every cue derived from the cut's timeline in cuts.js) ───────────── */
const A2 = 45, E3 = 52, Cs4 = 61, E4 = 64, A4 = 69, B4 = 71, Cs5 = 73, E5 = 76, A5 = 81, Cs6 = 85, E6 = 88;
const tap = (t, p = 0, g = 0.12, f = 1900) => noiseBurst(t, { gain: g, tone: 0.3, toneF: f, decay: 0.005, p });
const key = (t, p = 0) => noiseBurst(t, { gain: 0.13, tone: 0.2, toneF: 3100, decay: 0.0035, p, rev: 0.04 });
const rise = (t0, t1, g = 0.026) => sweep(t0, t1, 700, 2300, { gain: g, q: 1.4, rev: 0.2, shape: 'arch' });
const fall = (t0, t1, g = 0.02) => sweep(t0, t1, 2400, 650, { gain: g, q: 1.3, rev: 0.25, shape: 'arch' });
const confirm = t => {
  noiseBurst(t, { gain: 0.32, tone: 0.55, toneF: 1850, decay: 0.008, rev: 0.02, len: 0.04 });
  noiseBurst(t + 0.018, { gain: 0.2, tone: 0.7, toneF: 3700, decay: 0.006, rev: 0.02, len: 0.03 });
};
const narrated = !!CUT.vo;          // leave room for the voice: a quieter bed
const bed = narrated ? 0.75 : 1;

// The hook
pluck(0.0, hz(A4), { gain: 0.34, decay: 1.4, rev: 0.45 });
pluck(0.0, hz(A4 - 12), { gain: 0.12, decay: 1.6, rev: 0.3 });
sweep(0.12, T.tapAdd, 260, 3600, { gain: 0.05 * bed, q: 1.1, rev: 0.5 });
pad(0.05, T.rowIn[0] + 0.1, [A2 + 12, E3 + 12, Cs4, B4], { gain: 0.016 * bed, fadeIn: 1.2, fadeOut: 0.6 });

// The small action
tap(T.tapAdd, 0.2, 0.16, 1700);
rise(T.sheetIn[0] - 0.02, T.sheetIn[1]);
T.keys.forEach(([t], i) => key(t, -0.15 + i * 0.12));
tap(T.tapCat, 0.15);
rise(T.pickerIn[0] - 0.02, T.pickerIn[1], 0.022);
tap(T.tapFood, -0.1, 0.12, 2100);
fall(T.pickerOut[0], T.pickerOut[1], 0.018);
confirm(T.tapLock);
fall(T.sheetOut[0] - 0.02, T.sheetOut[1], 0.022);

// The payoff: soft resolved bell, then the warm bass pulse joins (96 bpm)
const pay = T.rowIn[0];
bell(pay, hz(A5), { gain: 0.11, decay: 2.0, p: 0.1 });
bell(pay + 0.01, hz(E5), { gain: 0.07, decay: 1.8, p: -0.2 });
pluck(pay, hz(Cs5), { gain: 0.06, decay: 1.0, bright: 0.6 });
const beat = 60 / 96, end = T.close - 0.1;
for (let t = pay, i = 0; t < end; t += beat, i++) bassHit(t, hz(i % 4 === 3 ? E3 - 24 : A2 - 12), { gain: (i === 0 ? 0.22 : 0.15) * bed });
pad(pay - 0.02, T.close + 0.15, [A2 + 12, E3 + 12, Cs4, E4, B4], { gain: 0.02 * bed, fadeIn: 0.5, fadeOut: 0.35 });

// The bigger picture: two rhythmic accents, then a light tonal lift
for (const t of [T.tapFoodSeg, T.tapMonthly]) {
  noiseBurst(t, { gain: 0.1, tone: 0.6, toneF: 980, decay: 0.03, len: 0.12, rev: 0.25 });
  pluck(t, hz(E5), { gain: 0.07, decay: 0.5, bright: 0.4, rev: 0.4 });
}
const lift = T.modeSwap[1];
[[lift, A5], [lift + 0.1, Cs6], [lift + 0.2, E6]].forEach(([t, m], i) => bell(t, hz(m), { gain: 0.045, decay: 1.4, p: -0.3 + i * 0.3 }));
sweep(lift - 0.06, lift + 1.14, 500, 5200, { gain: 0.022 * bed, q: 1.2, rev: 0.6 });

// Friends: tab, row, sheet, field, deletes + keys, Save tick, the ledger resolves
if (T.tapFriends !== undefined) {
  tap(T.tapFriends, 0.25, 0.13, 1600);
  pluck(T.tapFriends + 0.02, hz(Cs5), { gain: 0.05, decay: 0.6, bright: 0.5, rev: 0.4 });
  tap(T.tapArjun, -0.1);
  rise(T.editIn[0] - 0.02, T.editIn[1]);
  tap(T.tapField, -0.05, 0.1);
  T.dels.forEach((t, i) => key(t, 0.25 - i * 0.05));
  T.keys2.forEach(([t], i) => key(t, -0.15 + i * 0.1));
  confirm(T.tapSave);
  fall(T.editOut[0], T.editOut[1]);
  bell(T.aggRoll[0], hz(E5), { gain: 0.07, decay: 1.6, p: 0.15 });
  bell(T.aggRoll[0] + 0.12, hz(A5), { gain: 0.06, decay: 1.8, p: -0.15 });
}

// The brand close: warm two-note signature, final note resolves naturally at the end
const c0 = T.close, tail = CUT.duration - (c0 + 0.48);
pluck(c0 + 0.1, hz(E5), { gain: 0.26, decay: 1.2, rev: 0.5, p: -0.1 });
bell(c0 + 0.1, hz(E5), { gain: 0.05, decay: 1.4 });
pluck(c0 + 0.48, hz(A4), { gain: 0.3, decay: 1.7 * Math.max(1, tail / 2.52 * 0.8), rev: 0.55, len: tail, p: 0.05 });
pluck(c0 + 0.48, hz(A4 - 12), { gain: 0.12, decay: 1.9, rev: 0.4, len: tail });
bell(c0 + 0.48, hz(A5), { gain: 0.06, decay: 1.9, len: tail });
bassHit(c0 + 0.48, hz(A2 - 12), { gain: 0.24, len: 1.2 });
if (narrated) pad(c0 + 0.4, CUT.duration, [A2 + 12, E3 + 12, Cs4, E4], { gain: 0.012, fadeIn: 0.8, fadeOut: 2.2 });

/* ───────────── Reverb (Schroeder: parallel combs → series all-passes) ───────────── */
function reverb(input, combs, allpasses, fb = 0.8, damp = 0.25) {
  const out = new Float32Array(N);
  for (const d of combs) {
    const buf = new Float32Array(d); let idx = 0, lp = 0;
    for (let i = 0; i < N; i++) {
      const y = buf[idx]; lp = y * (1 - damp) + lp * damp;
      buf[idx] = input[i] + lp * fb; out[i] += y; idx = (idx + 1) % d;
    }
  }
  for (const d of allpasses) {
    const buf = new Float32Array(d); let idx = 0;
    for (let i = 0; i < N; i++) {
      const b = buf[idx], x = out[i]; const y = -x + b; buf[idx] = x + b * 0.5; out[i] = y; idx = (idx + 1) % d;
    }
  }
  return out;
}
const wetL = reverb(sendL, [1557, 1617, 1491, 1422].map(d => Math.round(d * SR / 44100)), [225, 556].map(d => Math.round(d * SR / 44100)));
const wetR = reverb(sendR, [1580, 1640, 1514, 1445].map(d => Math.round(d * SR / 44100)), [248, 579].map(d => Math.round(d * SR / 44100)));

/* ───────────── Mix, gentle tail fade, normalize to −1 dBFS ───────────── */
let peak = 0;
for (let i = 0; i < N; i++) {
  const tail = Math.min(1, (N - i) / (0.06 * SR));
  L[i] = (L[i] + wetL[i] * 0.07) * tail; R[i] = (R[i] + wetR[i] * 0.07) * tail;
  L[i] = Math.tanh(L[i] * 1.1) / 1.1; R[i] = Math.tanh(R[i] * 1.1) / 1.1;   // soft clip safety
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
const norm = Math.pow(10, -1 / 20) / (peak || 1);

const buf = Buffer.alloc(44 + N * 4);
buf.write('RIFF', 0); buf.writeUInt32LE(36 + N * 4, 4); buf.write('WAVE', 8);
buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(2, 22);
buf.writeUInt32LE(SR, 24); buf.writeUInt32LE(SR * 4, 28); buf.writeUInt16LE(4, 32); buf.writeUInt16LE(16, 34);
buf.write('data', 36); buf.writeUInt32LE(N * 4, 40);
for (let i = 0; i < N; i++) {
  buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, L[i] * norm)) * 32767), 44 + i * 4);
  buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, R[i] * norm)) * 32767), 46 + i * 4);
}
const out = process.argv[2] || 'tabby-reel-audio.wav';
writeFileSync(out, buf);
console.log(`  audio → ${out}`);
