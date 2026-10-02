#!/usr/bin/env node
// Renders reel.html frame-by-frame with headless Chromium and encodes it with ffmpeg.
//
//   node render.mjs                     → out/tabby-reel.mp4 (+ out/tabby-reel-poster.png)
//   node render.mjs --fps 60            → 60 fps master
//   node render.mjs --stills 0.6,4.5    → out/still-0.60.png … for quick review
//   node render.mjs --plates plates.json → swap rebuilt screens for real recordings (README)
//   node render.mjs --no-audio          → silent video
import { spawn, execFileSync, execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (name, fallback) => { const i = args.indexOf(`--${name}`); return i < 0 ? fallback : args[i + 1]; };
const flag = name => args.includes(`--${name}`);

const FPS = Number(opt('fps', 30));
const OUT = resolve(here, opt('out', 'out/tabby-reel.mp4'));
const STILLS = opt('stills', null);
const PLATES = opt('plates', null);
mkdirSync(dirname(OUT), { recursive: true });

async function loadPlaywright() {
  try { return await import('playwright'); } catch {}
  const globalRoot = execSync('npm root -g').toString().trim();   // fall back to a global install
  return createRequire(join(globalRoot, 'noop.js'))('playwright');
}

// Plates: [{ "from": 1.5, "to": 5.5, "file": "recordings/log.mov", "in": 0.8 }]
// Each clip is decoded once to a PNG sequence at the render fps.
function preparePlates() {
  if (!PLATES) return [];
  const list = JSON.parse(readFileSync(resolve(here, PLATES), 'utf8'));
  return list.map((p, i) => {
    const dir = join(here, 'out', 'plates', String(i));
    mkdirSync(dir, { recursive: true });
    if (!readdirSync(dir).length) {
      execFileSync('ffmpeg', ['-v', 'error', '-ss', String(p.in ?? 0), '-i', resolve(here, p.file),
        '-t', String(p.to - p.from + 0.5), '-vf', `fps=${FPS},scale=1206:2622:flags=lanczos`, join(dir, '%05d.png')]);
    }
    return { ...p, dir };
  });
}
function plateFor(plates, t) {
  const p = plates.find(q => t >= q.from && t < q.to);
  if (!p) return null;
  const idx = Math.floor((t - p.from) * FPS) + 1;
  const file = join(p.dir, String(idx).padStart(5, '0') + '.png');
  return existsSync(file) ? pathToFileURL(file).href : null;
}

const { chromium } = await loadPlaywright();
const browser = await chromium.launch({ args: ['--allow-file-access-from-files', '--font-render-hinting=none', '--force-color-profile=srgb'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
page.on('pageerror', e => { console.error('page error:', e.message); process.exitCode = 1; });
await page.goto(pathToFileURL(join(here, 'reel.html')).href + '?render');
await page.evaluate(() => window.reelReady);
const duration = await page.evaluate(() => window.REEL.DURATION);
const plates = preparePlates();
const shoot = async t => {
  await page.evaluate(([tt, plate]) => window.renderFrame(tt, plate), [t, plateFor(plates, t)]);
  return page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 1080, height: 1920 } });
};

if (STILLS) {
  const { writeFileSync } = await import('node:fs');
  for (const s of STILLS.split(',').map(Number)) {
    const file = join(here, 'out', `still-${s.toFixed(2)}.png`);
    writeFileSync(file, await shoot(s));
    console.log(file);
  }
  await browser.close();
  process.exit();
}

// Audio first, so the mux has it.
const wav = join(here, 'out', 'tabby-reel-audio.wav');
const withAudio = !flag('no-audio');
if (withAudio) execFileSync(process.execPath, [join(here, 'audio', 'sound-design.mjs'), wav], { stdio: 'inherit' });

const frames = Math.round(duration * FPS);
const ff = spawn('ffmpeg', [
  '-y', '-v', 'error',
  '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
  ...(withAudio ? ['-i', wav] : []),
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '15', '-pix_fmt', 'yuv420p', '-profile:v', 'high',
  '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709',
  ...(withAudio ? ['-c:a', 'aac', '-b:a', '256k', '-ar', '48000', '-af', 'loudnorm=I=-16:TP=-1.5:LRA=9'] : []),
  '-t', String(duration), '-movflags', '+faststart', OUT,
], { stdio: ['pipe', 'inherit', 'inherit'] });
const ffDone = new Promise((ok, fail) => ff.on('close', code => code ? fail(new Error(`ffmpeg exited ${code}`)) : ok()));

const started = Date.now();
let poster;
for (let f = 0; f < frames; f++) {
  const t = f / FPS;
  const png = await shoot(t);
  if (Math.abs(t - 13.5) < 0.5 / FPS) poster = png;
  if (!ff.stdin.write(png)) await new Promise(r => ff.stdin.once('drain', r));
  if (f % FPS === 0) process.stdout.write(`\r  ${t.toFixed(0).padStart(2)}s / ${duration}s`);
}
ff.stdin.end();
await ffDone;
await browser.close();
if (poster) (await import('node:fs')).writeFileSync(OUT.replace(/\.mp4$/, '-poster.png'), poster);
console.log(`\n  ${frames} frames @ ${FPS} fps in ${((Date.now() - started) / 1000).toFixed(0)}s → ${OUT}`);
