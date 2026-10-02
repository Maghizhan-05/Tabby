// Timelines for every cut of the Tabby reel, shared by reel.html (picture),
// audio/sound-design.mjs (SFX + music) and audio/voiceover.mjs (narration).
//
//   launch15 — the original 15 s silent-first launch reel
//   story30  — 30 s narrative cut: Home Screen widget bookend, voiceover, Friends ledger
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

  // Springboard framing: the whole phone, widget high in frame.
  const SPRING = { fx: 201, fy: 437, s: 1.62, X: 540, Y: 1150 };

  const story30 = {
    name: 'story30',
    duration: 30,
    T: {
      // Bookend: Home Screen widget → tap opens quick entry (spendtracker://quick-entry)
      widgetTap: 2.30, launch: [2.34, 2.84],
      sheetIn: [2.70, 3.12], kbIn: [2.92, 3.28],
      keys: [[3.55, '1'], [3.83, '8'], [4.11, '0']],
      tapCat: 4.45, pickerIn: [4.50, 4.86], kbOut: [4.50, 4.76],
      tapFood: 5.10, foodSet: 5.16, pickerOut: [5.26, 5.54],
      tapLock: 6.55, sheetOut: [6.80, 7.20], island: [7.00, 9.2],
      rowIn: [7.28, 7.66], totalRoll: [7.38, 7.88], donutSwap: [7.32, 7.74],
      trace: [7.95, 8.65], traceOut: [9.40, 9.90],
      tapFoodSeg: 10.70, tapMonthly: 12.20, modeSwap: [12.26, 12.62],
      // Friends ledger: populated list, edit Arjun — They owe you ₹650 → ₹1,250
      tapFriends: 14.75,
      tapArjun: 16.20, editIn: [16.30, 16.72],
      tapField: 16.95, kb2In: [17.00, 17.35],
      dels: [17.45, 17.57, 17.69],
      keys2: [[17.86, '1'], [18.02, '2'], [18.18, '5'], [18.34, '0']],
      tapSave: 18.70, editOut: [18.80, 19.18],
      aggRoll: [19.20, 19.70], trace2: [19.25, 19.95], traceOut2: [20.30, 20.70],
      // Back to the Home Screen: the widget has caught up
      homeOut: [20.86, 21.40], trace3: [21.75, 22.55], traceOut3: [23.25, 23.70],
      close: 24.0,
    },
    hook: {
      settle: 1.5, draw: [0.30, 2.05], fade: [2.10, 2.40],
      path: 'M 868 302 C 960 300, 1010 380, 1004 470 C 998 560, 950 620, 880 650',
    },
    heads: [['h1', 0.04, 2.20], ['h2', 5.34, 7.05], ['h3', 7.45, 9.85], ['h4', 9.95, 14.6],
            ['h5', 15.0, 20.55], ['h6', 21.1, 23.6]],
    cam: [
      [0.00, Object.assign({}, SPRING, { s: 1.70, Y: 1185 })],
      [1.50, SPRING, 'outCubic'],
      [2.30, SPRING, 'hold'],
      [2.84, HOME, 'inOutCubic'],                                              // app opens
      [3.30, HOME, 'hold'],
      [3.30, { fx: 201, fy: 455, s: 2.52, X: 540, Y: 1010 }, 'cut'],
      [4.50, { fx: 201, fy: 452, s: 2.64, X: 540, Y: 1010 }, 'linear'],
      [4.50, { fx: 201, fy: 610, s: 2.10, X: 540, Y: 1090 }, 'cut'],
      [5.30, { fx: 201, fy: 606, s: 2.16, X: 540, Y: 1090 }, 'linear'],
      [5.30, { fx: 201, fy: 437, s: 1.58, X: 540, Y: 1134 }, 'cut'],
      [6.80, { fx: 201, fy: 437, s: 1.64, X: 540, Y: 1126 }, 'linear'],
      [7.26, HOME, 'inOutCubic'],
      [7.50, HOME, 'hold'],
      [8.70, { fx: 201, fy: 400, s: 1.76, X: 540, Y: 1150 }, 'inOutSine'],
      [9.90, { fx: 201, fy: 400, s: 1.79, X: 540, Y: 1150 }, 'linear'],
      [10.5, { fx: 201, fy: 336, s: 2.20, X: 540, Y: 1222 }, 'inOutCubic'],
      [12.25, { fx: 201, fy: 336, s: 2.25, X: 540, Y: 1222 }, 'linear'],
      [12.6, { fx: 201, fy: 336, s: 2.25, X: 540, Y: 1222 }, 'hold'],
      [13.9, Object.assign({}, HOME, { s: 1.52, Y: 1118 }), 'inOutCubic'],     // back out: tab bar in view
      [14.8, Object.assign({}, HOME, { s: 1.53, Y: 1118 }), 'linear'],
      [15.7, { fx: 201, fy: 290, s: 2.00, X: 540, Y: 1060 }, 'inOutCubic'],    // the ledger
      [16.3, { fx: 201, fy: 290, s: 2.04, X: 540, Y: 1060 }, 'linear'],
      [16.9, { fx: 201, fy: 330, s: 2.15, X: 540, Y: 1080 }, 'inOutCubic'],    // Edit Friend
      [18.7, { fx: 201, fy: 330, s: 2.20, X: 540, Y: 1080 }, 'linear'],
      [19.25, { fx: 201, fy: 300, s: 2.05, X: 540, Y: 1080 }, 'inOutCubic'],   // updated row + net
      [20.6, { fx: 201, fy: 300, s: 2.08, X: 540, Y: 1080 }, 'linear'],
      [21.3, SPRING, 'inOutCubic'],                                            // home: whole phone
      [22.6, { fx: 201, fy: 153, s: 2.12, X: 540, Y: 860 }, 'inOutSine'],      // the widget, caught up
      [24.0, { fx: 201, fy: 153, s: 2.18, X: 540, Y: 860 }, 'linear'],
    ],
    // Narration (second person). `at` = intended start; voiceover.mjs fits each
    // line inside its window (until the next line starts).
    vo: [
      { at: 0.35, text: 'Every rupee tells a story.' },
      { at: 2.72, text: 'One forty-two. Lunch, a hundred and eighty rupees.' },
      { at: 5.85, text: "One tap, and it's on your tab." },
      { at: 8.00, text: 'There it is. Your day, adding up.' },
      { at: 10.2, text: 'Tap the ring. See where it went.' },
      { at: 12.3, text: 'Zoom out, and your month comes into focus.' },
      { at: 14.9, text: 'And that dinner you covered on Friday?' },
      { at: 17.0, text: 'Tabby remembers who owes you, so friendships stay simple.' },
      { at: 21.0, text: 'And your day stays in sight, right on your Home Screen.' },
      { at: 24.4, text: 'Tabby.' },
      { at: 25.2, text: 'Keep a tab on your spending. Coming to the App Store.', until: 29.4 },
    ],
  };

  globalThis.TABBY_CUTS = { launch15, story30 };
})();
