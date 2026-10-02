// Timelines for every cut of the Tabby reel, shared by reel.html (picture),
// audio/sound-design.mjs (SFX + music) and audio/voiceover.mjs (narration).
//
//   launch15 — the original 15 s silent-first launch reel
//   story30  — 30 s narrative cut with voiceover and the Friends ledger
//
// Times are seconds. Camera keys: [time, {fx, fy, s, X, Y}, ease | 'cut' | 'hold'];
// {fx, fy} is a focus point in screen pt that lands on stage point {X, Y} at scale s.
(function () {
  const HOME = { fx: 201, fy: 437, s: 1.50, X: 540, Y: 1122 };
  const at = (o, d) => Object.assign({}, o, d);

  const launch15 = {
    name: 'launch15',
    duration: 15,
    T: {
      tapAdd: 1.55, sheetIn: [1.66, 2.12], kbIn: [1.86, 2.24],
      keys: [[2.42, '1'], [2.70, '8'], [2.98, '0']],
      tapCat: 3.24, pickerIn: [3.30, 3.66], kbOut: [3.30, 3.56],
      tapFood: 3.84, foodSet: 3.90, pickerOut: [3.98, 4.26],
      tapLock: 4.80, sheetOut: [5.04, 5.44], island: [5.24, 7.6],
      rowIn: [5.52, 5.9], totalRoll: [5.62, 6.12], donutSwap: [5.56, 5.98],
      trace: [6.12, 6.84], traceOut: [7.30, 7.80],
      tapFoodSeg: 8.44, tapMonthly: 9.94, modeSwap: [10.0, 10.36],
      close: 12.0,
    },
    hook: { settle: 1.25, draw: [0.28, 1.38], fade: [1.42, 1.72] },
    heads: [['h1', 0.04, 1.42], ['h2', 4.08, 5.42], ['h3', 5.86, 7.84], ['h4', 8.18, 11.82]],
    cam: [
      [0.00, at(HOME, { s: 1.58, Y: 1150 })],
      [1.25, HOME, 'outCubic'],
      [2.20, HOME, 'hold'],
      [2.20, { fx: 201, fy: 455, s: 2.52, X: 540, Y: 1010 }, 'cut'],           // closer: amount + keypad
      [3.30, { fx: 201, fy: 452, s: 2.62, X: 540, Y: 1010 }, 'linear'],
      [3.30, { fx: 201, fy: 610, s: 2.10, X: 540, Y: 1090 }, 'cut'],           // the picker
      [4.05, { fx: 201, fy: 606, s: 2.16, X: 540, Y: 1090 }, 'linear'],
      [4.05, { fx: 201, fy: 437, s: 1.58, X: 540, Y: 1134 }, 'cut'],           // completed entry
      [5.00, { fx: 201, fy: 437, s: 1.62, X: 540, Y: 1128 }, 'linear'],
      [5.50, HOME, 'inOutCubic'],
      [5.90, HOME, 'hold'],
      [7.10, { fx: 201, fy: 400, s: 1.76, X: 540, Y: 1150 }, 'inOutSine'],     // push in: total + new row
      [8.00, { fx: 201, fy: 400, s: 1.79, X: 540, Y: 1150 }, 'linear'],
      [8.62, { fx: 201, fy: 336, s: 2.20, X: 540, Y: 1222 }, 'inOutCubic'],    // the donut
      [10.0, { fx: 201, fy: 336, s: 2.24, X: 540, Y: 1222 }, 'linear'],
      [10.3, { fx: 201, fy: 336, s: 2.24, X: 540, Y: 1222 }, 'hold'],
      [11.6, { fx: 201, fy: 330, s: 1.62, X: 540, Y: 1080 }, 'inOutCubic'],    // pull back: whole panel
      [12.6, { fx: 201, fy: 330, s: 1.58, X: 540, Y: 1080 }, 'linear'],
    ],
  };

  const story30 = {
    name: 'story30',
    duration: 30,
    T: {
      tapAdd: 2.60, sheetIn: [2.71, 3.17], kbIn: [2.91, 3.29],
      keys: [[3.62, '1'], [3.92, '8'], [4.22, '0']],
      tapCat: 4.62, pickerIn: [4.68, 5.04], kbOut: [4.68, 4.94],
      tapFood: 5.30, foodSet: 5.36, pickerOut: [5.46, 5.74],
      tapLock: 6.95, sheetOut: [7.20, 7.60], island: [7.40, 10.0],
      rowIn: [7.68, 8.06], totalRoll: [7.78, 8.28], donutSwap: [7.72, 8.14],
      trace: [8.40, 9.20], traceOut: [10.4, 10.9],
      tapFoodSeg: 12.55, tapMonthly: 14.55, modeSwap: [14.61, 14.97],
      // Friends ledger: populated list, edit Arjun — They owe you ₹650 → ₹1,250
      tapFriends: 17.55,
      tapArjun: 19.20, editIn: [19.30, 19.72],
      tapField: 20.00, kb2In: [20.05, 20.40],
      dels: [20.55, 20.67, 20.79],
      keys2: [[20.98, '1'], [21.14, '2'], [21.30, '5'], [21.46, '0']],
      tapSave: 21.90, editOut: [22.00, 22.38],
      aggRoll: [22.40, 22.90], trace2: [22.45, 23.15], traceOut2: [23.70, 24.10],
      close: 24.6,
    },
    hook: { settle: 1.6, draw: [0.30, 2.30], fade: [2.36, 2.66] },
    heads: [['h1', 0.04, 2.42], ['h2', 5.56, 7.50], ['h3', 7.90, 11.0], ['h4', 11.5, 17.2], ['h5', 17.85, 24.2]],
    cam: [
      [0.00, at(HOME, { s: 1.58, Y: 1150 })],
      [1.60, HOME, 'outCubic'],
      [3.30, HOME, 'hold'],
      [3.30, { fx: 201, fy: 455, s: 2.52, X: 540, Y: 1010 }, 'cut'],
      [4.68, { fx: 201, fy: 452, s: 2.64, X: 540, Y: 1010 }, 'linear'],
      [4.68, { fx: 201, fy: 610, s: 2.10, X: 540, Y: 1090 }, 'cut'],
      [5.52, { fx: 201, fy: 606, s: 2.16, X: 540, Y: 1090 }, 'linear'],
      [5.52, { fx: 201, fy: 437, s: 1.58, X: 540, Y: 1134 }, 'cut'],
      [7.20, { fx: 201, fy: 437, s: 1.64, X: 540, Y: 1126 }, 'linear'],
      [7.66, HOME, 'inOutCubic'],
      [8.00, HOME, 'hold'],
      [9.60, { fx: 201, fy: 400, s: 1.76, X: 540, Y: 1150 }, 'inOutSine'],
      [11.4, { fx: 201, fy: 400, s: 1.80, X: 540, Y: 1150 }, 'linear'],
      [12.2, { fx: 201, fy: 336, s: 2.20, X: 540, Y: 1222 }, 'inOutCubic'],
      [14.6, { fx: 201, fy: 336, s: 2.26, X: 540, Y: 1222 }, 'linear'],
      [14.9, { fx: 201, fy: 336, s: 2.26, X: 540, Y: 1222 }, 'hold'],
      [16.3, at(HOME, { s: 1.52, Y: 1118 }), 'inOutCubic'],                    // back out: tab bar in view
      [17.6, at(HOME, { s: 1.53, Y: 1118 }), 'linear'],
      [18.6, { fx: 201, fy: 290, s: 2.00, X: 540, Y: 1060 }, 'inOutCubic'],    // the ledger
      [19.3, { fx: 201, fy: 290, s: 2.04, X: 540, Y: 1060 }, 'linear'],
      [19.9, { fx: 201, fy: 330, s: 2.15, X: 540, Y: 1080 }, 'inOutCubic'],    // Edit Friend
      [21.8, { fx: 201, fy: 330, s: 2.20, X: 540, Y: 1080 }, 'linear'],
      [22.4, { fx: 201, fy: 300, s: 2.05, X: 540, Y: 1080 }, 'inOutCubic'],    // updated row + net
      [24.6, { fx: 201, fy: 300, s: 2.10, X: 540, Y: 1080 }, 'linear'],
    ],
    // Narration (second person). `at` = intended start; voiceover.mjs fits each
    // line inside its window (until the next line starts).
    vo: [
      { at: 0.35, text: 'Every rupee tells a story.' },
      { at: 2.85, text: 'One forty-two. Lunch, a hundred and eighty rupees.' },
      { at: 5.95, text: "One tap, and it's on your tab." },
      { at: 8.70, text: 'There it is. Your day, adding up.' },
      { at: 11.6, text: 'Tap the ring. See where it went.' },
      { at: 14.3, text: 'Zoom out, and your month comes into focus.' },
      { at: 17.3, text: 'And that dinner you covered on Friday?' },
      { at: 20.0, text: 'Tabby remembers who owes you, so friendships stay simple.' },
      { at: 24.95, text: 'Tabby.' },
      { at: 25.8, text: 'Keep a tab on your spending. Coming to the App Store.', until: 29.75 },
    ],
  };

  globalThis.TABBY_CUTS = { launch15, story30 };
})();
