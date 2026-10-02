#!/usr/bin/env node
// Narration for a cut, synthesized with Kokoro-82M (Apache-2.0) via kokoro-js.
//
//   node audio/voiceover.mjs                       → out/vo-story30.wav + out/vo-story30.srt
//   node audio/voiceover.mjs --voice bf_emma       → any kokoro-js voice (af_heart, af_bella, bf_emma, bm_george…)
//   node audio/voiceover.mjs --speed 0.95          → base pace (lines that overrun their window are sped up)
//   node audio/voiceover.mjs --placeholder         → out/vo-story30-placeholder.wav: timing guide, no model needed
//
// The script lives in cuts.js (`vo`). Each line starts at `at` and must finish
// before the next line (or `until`). Voice files ship inside kokoro-js; the model
// and tokenizer download once from huggingface.co (onnx-community/Kokoro-82M-v1.0-ONNX).
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import '../cuts.js';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf(`--${n}`); return i < 0 ? d : args[i + 1]; };
const CUT = globalThis.TABBY_CUTS[opt('cut', 'story30')];
if (!CUT?.vo) throw new Error('this cut has no narration');
const VOICE = opt('voice', 'af_heart'), SPEED = Number(opt('speed', 1.0)), PLACEHOLDER = args.includes('--placeholder');
const SR = 24000, GAP = 0.12, MAX_SPEED = 1.25;
const outDir = join(here, '..', 'out');
mkdirSync(outDir, { recursive: true });

/* ── synthesis ── */
let synth;
if (PLACEHOLDER) {
  // Syllable-rate filtered noise sized at ~2.6 words/s — a guide track for timing and ducking only.
  synth = async (text, speed) => {
    const words = text.split(/\s+/).length, dur = words / 2.6 / speed, n = Math.round(dur * SR), a = new Float32Array(n);
    let lp = 0, seed = text.length * 7919;
    for (let i = 0; i < n; i++) {
      seed = (seed * 16807) % 2147483647; lp += ((seed / 2147483647) * 2 - 1 - lp) * 0.08;
      a[i] = lp * 0.9 * (0.55 + 0.45 * Math.sin(2 * Math.PI * 4.2 * i / SR)) * Math.min(1, i / 600, (n - i) / 600);
    }
    return a;
  };
} else {
  const { KokoroTTS } = await import('kokoro-js');
  const tts = await KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', { dtype: 'q8', device: 'cpu' });
  synth = async (text, speed) => (await tts.generate(text, { voice: VOICE, speed })).audio;
}

// Trim leading/trailing silence, keep 25 ms of air.
function trim(a) {
  const th = 0.012, pad = Math.round(0.025 * SR);
  let s = 0, e = a.length - 1;
  while (s < a.length && Math.abs(a[s]) < th) s++;
  while (e > s && Math.abs(a[e]) < th) e--;
  return a.slice(Math.max(0, s - pad), Math.min(a.length, e + pad));
}

/* ── fit every line into its window ── */
const track = new Float32Array(Math.round(CUT.duration * SR));
const cues = [];
for (const [i, line] of CUT.vo.entries()) {
  const next = CUT.vo[i + 1];
  const window = (line.until ?? (next ? next.at - GAP : CUT.duration - 0.4)) - line.at;
  let speed = SPEED, a = trim(await synth(line.text, speed));
  if (a.length / SR > window) {
    speed = Math.min(MAX_SPEED, SPEED * (a.length / SR) / window * 1.02);
    a = trim(await synth(line.text, speed));
  }
  const dur = a.length / SR;
  const fits = dur <= window + 1e-3;
  track.set(a.subarray(0, Math.min(a.length, track.length - Math.round(line.at * SR))), Math.round(line.at * SR));
  cues.push({ start: line.at, end: line.at + dur, text: line.text });
  console.log(`  ${line.at.toFixed(2).padStart(5)}s  ${dur.toFixed(2)}s / ${window.toFixed(2)}s  ×${speed.toFixed(2)}${fits ? '' : '  ⚠ overruns'}  ${line.text}`);
}

/* ── write: 24 kHz mono → broadcast-clean 48 kHz stereo ── */
const base = `vo-${CUT.name}${PLACEHOLDER ? '-placeholder' : ''}`;   // a guide track can never stand in for the real one
const raw = join(outDir, `${base}-raw.wav`), wav = join(outDir, `${base}.wav`);
const buf = Buffer.alloc(44 + track.length * 2);
buf.write('RIFF', 0); buf.writeUInt32LE(36 + track.length * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12);
buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(SR, 24);
buf.writeUInt32LE(SR * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(track.length * 2, 40);
let peak = 0; for (const v of track) peak = Math.max(peak, Math.abs(v));
const g = peak ? 0.89 / peak : 1;
track.forEach((v, i) => buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, v * g)) * 32767), 44 + i * 2));
writeFileSync(raw, buf);
execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', raw, '-af',
  'highpass=f=75,equalizer=f=3200:t=q:w=1.2:g=2,acompressor=threshold=-20dB:ratio=2.5:attack=6:release=120:makeup=2,aresample=48000,pan=stereo|c0=c0|c1=c0',
  '-c:a', 'pcm_s16le', wav]);
rmSync(raw);

/* ── captions (SRT), for platforms that take a caption file ── */
const ts = s => { const ms = Math.round(s * 1000); return `${String(Math.floor(ms / 3600000)).padStart(2, '0')}:${String(Math.floor(ms / 60000) % 60).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')},${String(ms % 1000).padStart(3, '0')}`; };
writeFileSync(join(outDir, `${base}.srt`), cues.map((c, i) => `${i + 1}\n${ts(c.start)} --> ${ts(c.end + 0.25)}\n${c.text}\n`).join('\n'));
console.log(`  voiceover → ${wav}${PLACEHOLDER ? '  (PLACEHOLDER guide track)' : ` (voice ${VOICE})`}`);
