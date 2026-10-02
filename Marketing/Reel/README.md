# Tabby launch reel: "Keep a tab."

9:16 · 1080 × 1920 · 30 fps · H.264 + AAC (48 kHz stereo, −16 LUFS)

| Cut | Length | What it is |
|---|---|---|
| `launch15` | 15 s | Silent-first launch reel: log → see → know → brand close |
| `story30` | 30 s | Narrated story cut: the same flow at a storytelling pace, plus the **Friends** ledger |

A code-driven social launch film. Every frame is a pure function of time, so a
render is frame-exact and repeatable. Retiming a beat is a one-line edit.

```
Marketing/Reel/
├── reel.html                 the stage: rebuilt Tabby screens, camera, typography, film treatment
├── cuts.js                   every cut's timeline: beats, camera, headlines, narration script
├── render.mjs                Playwright → PNG frames → ffmpeg (video + audio mux, voice ducking)
├── audio/sound-design.mjs    synthesized SFX + music bed, cued from the cut's timeline
├── audio/voiceover.mjs       narration with Kokoro-82M (kokoro-js) + SRT captions
├── fonts/                    Nunito (SF Rounded stand-in) + Inter (SF Pro stand-in), SIL OFL 1.1
└── out/                      renders (git-ignored)
```

## Render

```bash
cd Marketing/Reel
npm install && npx playwright install chromium   # once
node render.mjs                                  # → out/tabby-reel.mp4 + out/tabby-reel-poster.png
node render.mjs --cut story30                    # → out/tabby-reel-story30.mp4 (generates the voice on first run)
node render.mjs --fps 60                         # 60 fps master
node render.mjs --stills 0.6,4.5,9.2             # review frames → out/still-*.png
node render.mjs --no-audio                       # silent version
```

You need Node 18+ and ffmpeg (`brew install ffmpeg`). A full render takes about 2–3 minutes.

**Preview:** open `reel.html` in Chrome. It loops with a scrubber, and `reel.html?t=8.4`
opens at a given second. Add `?cut=story30` for the story cut. Preview timing follows the wall clock; renders are frame-exact.

## Screenplay → timeline (`launch15`)

| Time | Beat | On screen | Sound |
|---|---|---|---|
| 0.00–1.50 | **The hook** | Populated Home settles in; **Keep a tab.** rises; a gold dot draws a clockwise arc down to **Add spend** | warm A4 pluck, rising air sweep |
| 1.50–5.50 | **The small action** | Touch ring → quick-entry sheet springs up → cut close: keypad types **180** → cut: category picker, **Food** → cut: completed entry, **Lock it in** turns gold; **Log it.**; touch at 4.80 | tap, sheet whoosh, 3 keypad clicks, dry confirmation tick |
| 5.50–8.00 | **The payoff** | Sheet dismisses; Dynamic Island shows the real Live Activity (✓ ₹180.00); *Food expense* inserts at the top of Recent activity; Today rolls ₹215 → ₹395; slight push-in; gold outline traces the new row and fades; **See it.** | soft resolved bell, warm bass pulse joins (96 bpm) |
| 8.00–12.00 | **The bigger picture** | Push to the Daily donut; touch on Food → callout *Food ₹180.00 · 46%*; tap **Monthly** → *This Month ₹15,650.00* + gold weekly bars; pull back to the full analytics panel; **Know your spending.** | two rhythmic accents, light tonal lift |
| 12.00–15.00 | **The brand close** | Product dissolves to near-black; gold orbit draws; real gold-coin app icon scales in; **Tabby** / *Keep a tab on your spending.* / **Coming to the App Store**. Settled by 12.6 s and held | two-note signature E5 → A5, rings out to 15 s |

Every beat time lives in the cut's `T` object in `cuts.js`; camera moves are in its `cam`.
Touch points (`TOUCHES` in `reel.html`) are in screen points.

## Story cut (`story30`): narrated, with the widget and Friends

Second-person narration: the viewer is the hero. The cut is bookended on the iPhone Home
Screen with Tabby's **Spending Analytics** widget (medium, Daily mode). Headlines stay one
message per scene, so the film still reads with sound off.

| Time | Scene | On screen | Narration |
|---|---|---|---|
| 0.0–2.3 | Hook | Home Screen; widget shows **Today ₹215**; **Keep a tab.**; gold arc to the widget | *Every rupee tells a story.* |
| 2.3–7.2 | Log it | Tap the widget → app opens out of it straight into quick entry (the widget's `spendtracker://quick-entry` link) → type 180 → Food → **Lock it in** (6.55) | *One forty-two. Lunch, a hundred and eighty rupees.* · *One tap, and it's on your tab.* |
| 7.2–9.9 | See it | Live Activity, new row, Today ₹215 → ₹395, gold trace | *There it is. Your day, adding up.* |
| 9.9–14.7 | Know your spending | Donut → Food ₹180.00 · 46% → Monthly ₹15,650.00 → pull back | *Tap the ring. See where it went.* · *Zoom out, and your month comes into focus.* |
| 14.7–20.8 | **Settle up.** | Friends tab → ledger → Arjun → Edit Friend: They owe you 650 → 1250 → **Save** → row and aggregate net update, gold trace | *And that dinner you covered on Friday?* · *Tabby remembers who owes you, so friendships stay simple.* |
| 20.8–24.0 | **At a glance.** | Swipe home; the app shrinks into its icon; widget now **Today ₹395**, Food in gold; gold trace; push in | *And your day stays in sight, right on your Home Screen.* |
| 24.0–30.0 | Brand close | Orbit, coin icon, Tabby, tagline, **Coming to the App Store** (settled by 24.6) | *Tabby.* · *Keep a tab on your spending. Coming to the App Store.* |

The Home Screen's other icons are generic glyphs, so the frame doesn't imitate any
third-party app. Only the real Tabby icon and widget appear. The widget uses its own ring
palette (`AnalyticsRingsWidget.ringPalette`) and `WidgetCurrencyFormatter` amounts (₹215, ₹395).

### Friends data

The ledger is sorted by name, as the app's `@Query` does. Amounts use the app's
`WidgetCurrencyFormatter` format (₹, en_IN grouping):

| Friend | They owe | You owe | Net |
|---|---|---|---|
| Arjun | ₹650 → **₹1,250** | ₹0 | ₹650 → **₹1,250** |
| Kabir | ₹0 | ₹400 | −₹400 (red) |
| Meera | ₹1,200 | ₹300 | ₹900 |
| Priya | ₹250 | ₹250 | ₹0 |

**Aggregate net:** ₹1,150 → **₹1,750**.

### The voice

`audio/voiceover.mjs` speaks the `vo` lines in `cuts.js` with Kokoro-82M (Apache-2.0). The
default voice is `af_heart`, a warm female US-English voice. To try another:

```bash
node audio/voiceover.mjs --voice bf_emma         # or af_bella, bm_george, am_michael, bm_fable …
node render.mjs --cut story30                    # re-render with it
```

- **Fitting:** each line starts at its `at` time and is fitted before the next line, re-spoken
  up to 1.25× faster if needed. The script prints a fit report.
- **Ducking:** the music bed ducks under the voice through a sidechain compressor.
- **Captions:** `out/vo-story30.srt` is written alongside the voice, for platforms that accept
  a caption file.
- **Downloads:** voice files ship inside `kokoro-js`. The model (~90 MB) and tokenizer download
  once from `huggingface.co` and its CDN `*.hf.co`.

**Human voiceover:** record the script, aligned to the start times above, and pass it with
`node render.mjs --cut story30 --vo my-voice.wav`. Use `--placeholder` on `voiceover.mjs` to
get a timing-guide track to record against.

## Demo dataset (₹, en_IN), consistent throughout

Thursday 22 Oct 2026, 1:42 pm.

- **Today before:** Transport ₹120 (*Metro recharge*), Groceries ₹95 (*Milk & eggs*) = **₹215.00**
- **New entry:** ₹180 · Food, no note, so the row reads *Food expense*, as `RecentEntryPresentation` does.
- **Today after:** **₹395.00**. Food becomes the largest segment, so it takes the gold ring colour and is 46% of the day.
- **This month (1–22 Oct):** **₹15,650.00**. Weekly bars: W39 4,820 · W40 5,360 · W41 4,105 · W42 6,240 · W43 3,115.
- **Older rows:** *Electricity bill* ₹1,460 (21 Oct), *Movie night* ₹420 (20 Oct), *Weekend market* ₹640 (19 Oct).

## How faithful the rebuilt screens are

Layouts, copy, colours and behaviour come straight from the SwiftUI sources:

| Source file | What it supplies |
|---|---|
| `Theme.swift` | Tokens, ring colours, category accents, `TabbyOrbit`, `TabbyBackdrop` |
| `HomeView` | Card at 49% height, **Add spend** capsule |
| `AnalyticsView` | Mode selector, `.opacity + .scale(0.98)` transition |
| `DailyView` | Donut with 0.62 inner ratio, 1.5 pt angular inset, `SegmentSelectionBubble` placement maths |
| `MonthlyView` | Five-week gold bars |
| `RecentEntriesListView` | Row anatomy, "<Category> expense" title |
| `QuickEntrySheetView` | NEW TAB / Log a spend, 64 pt amount, **Lock it in** disabled until `canSubmit` |
| `CategoryPickerView` | Category list |
| `ExpenseConfirmationLiveActivity` | Compact Dynamic Island |

Known stand-ins:

- **Fonts:** Nunito and Inter stand in for SF Pro Rounded and SF Pro, because Apple's fonts can't be redistributed.
- **System glyphs:** SF Symbols, the keyboard and the status bar are redrawn.

The simulator recordings will be pixel-true; this rebuild is the version that ships today.

## Swapping in real simulator recordings (plates)

The rebuilt UI is one layer inside the camera. Camera moves, touch rings, the row outline,
headlines and the brand close all sit on top of it, so a recording can replace it for any
time window without touching the motion design.

1. Record on the **iPhone 18 Pro** simulator with the dataset above, acting each window as
   the timeline table describes:
   `xcrun simctl io booted recordVideo --codec h264 recordings/log.mov`
2. Describe the windows in `plates.json`. `in` is the recording's timestamp at `from`:
   ```json
   [
     { "from": 0.0,  "to": 1.66, "file": "recordings/home.mov",  "in": 0.5 },
     { "from": 1.66, "to": 5.50, "file": "recordings/log.mov",   "in": 2.1 },
     { "from": 5.50, "to": 12.3, "file": "recordings/see.mov",   "in": 0.0 }
   ]
   ```
3. Run `node render.mjs --plates plates.json`.

Plates are scaled to the 402 × 874 pt screen (1206 × 2622 px). Touch rings are drawn by the
film, not iOS, so act each tap at the time listed in `TOUCHES`, or retime `T` to match your
take. Any time not covered by a plate falls back to the rebuilt UI.

## Editing notes

- **CTA:** the copy is in `#cta`. It sits at y ≈ 1290–1380, well above the caption and controls zone (bottom ~25%).
- **Headlines:** the scrim appears only while a headline is up, so the close-ups stay clean.
- **Optional voiceover:** *"Log it. See it. Know your spending. Tabby. Keep a tab on your spending."*
  Lay it over the render; the film communicates fully with sound muted.
- **Fonts:** Nunito (© The Nunito Project Authors) and Inter (© The Inter Project Authors) are
  licensed under the [SIL Open Font License 1.1](https://openfontlicense.org).
