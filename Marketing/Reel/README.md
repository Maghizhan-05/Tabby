# Tabby launch reel: "Keep a tab."

15 s · 9:16 · 1080 × 1920 · 30 fps · H.264 + AAC (48 kHz stereo, −16 LUFS)

A code-driven social launch film. Every frame is a pure function of time, so a
render is frame-exact and repeatable. Retiming a beat is a one-line edit.

```
Marketing/Reel/
├── reel.html                 the stage: rebuilt Tabby screens, camera, typography, film treatment
├── render.mjs                Playwright → PNG frames → ffmpeg (video + audio mux)
├── audio/sound-design.mjs    synthesized SFX + music bed, timed to the same timeline
├── fonts/                    Nunito (SF Rounded stand-in) + Inter (SF Pro stand-in), SIL OFL 1.1
└── out/                      renders (git-ignored)
```

## Render

```bash
cd Marketing/Reel
npm install && npx playwright install chromium   # once
node render.mjs                                  # → out/tabby-reel.mp4 + out/tabby-reel-poster.png
node render.mjs --fps 60                         # 60 fps master
node render.mjs --stills 0.6,4.5,9.2             # review frames → out/still-*.png
node render.mjs --no-audio                       # silent version
```

You need Node 18+ and ffmpeg (`brew install ffmpeg`). A full render takes about 2–3 minutes.

**Preview:** open `reel.html` in Chrome. It loops with a scrubber, and `reel.html?t=8.4`
opens at a given second. Preview timing follows the wall clock; renders are frame-exact.

## Screenplay → timeline

| Time | Beat | On screen | Sound |
|---|---|---|---|
| 0.00–1.50 | **The hook** | Populated Home settles in; **Keep a tab.** rises; a gold dot draws a clockwise arc down to **Add spend** | warm A4 pluck, rising air sweep |
| 1.50–5.50 | **The small action** | Touch ring → quick-entry sheet springs up → cut close: keypad types **180** → cut: category picker, **Food** → cut: completed entry, **Lock it in** turns gold; **Log it.**; touch at 4.80 | tap, sheet whoosh, 3 keypad clicks, dry confirmation tick |
| 5.50–8.00 | **The payoff** | Sheet dismisses; Dynamic Island shows the real Live Activity (✓ ₹180.00); *Food expense* inserts at the top of Recent activity; Today rolls ₹215 → ₹395; slight push-in; gold outline traces the new row and fades; **See it.** | soft resolved bell, warm bass pulse joins (96 bpm) |
| 8.00–12.00 | **The bigger picture** | Push to the Daily donut; touch on Food → callout *Food ₹180.00 · 46%*; tap **Monthly** → *This Month ₹15,650.00* + gold weekly bars; pull back to the full analytics panel; **Know your spending.** | two rhythmic accents, light tonal lift |
| 12.00–15.00 | **The brand close** | Product dissolves to near-black; gold orbit draws; real gold-coin app icon scales in; **Tabby** / *Keep a tab on your spending.* / **Coming to the App Store**. Settled by 12.6 s and held | two-note signature E5 → A5, rings out to 15 s |

Every beat time lives in the `T` object at the top of the script in `reel.html`. Camera moves
are in `CAM`, and touch points in `TOUCHES`, which use screen points.

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
