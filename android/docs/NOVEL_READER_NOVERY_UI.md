# DeepSync novel reader — Novery features, DeepSync look

Applies to EPUB / novel reading only; manga reading is untouched.

## UI (flat Material, follows the app theme — no glass/gradients)
- `reader/ui/epub/NovelReaderChrome.kt`: top bar (chapter, "n / total • % • time left", highlight toggle, progress line),
  floating Chapters / Listen / Settings bar, inline settings panel with Presets / Text / Layout / Theme / Options tabs,
  "N changes from default" + Reset. Colours come from `MaterialTheme.colorScheme` (`ChromeColors`).
- DeepSync's old novel settings sheet is removed (`ReaderConfigSheet` is manga-only now). The reader menu action
  for novels opens the new Settings panel instead.
- Chapter header (Chapter N / title / "N of total") above each chapter in scroll mode (toggle in Options).

## Listen / TTS (`NovelTtsPlayer.kt`, `ReaderTts.kt`)
- Player replaces the bottom bar: progress (sentence i / n), previous/next sentence, previous/next chapter, play/pause,
  quick speed chips (0.75x–2x), stop.
- Settings: Voice (all installed voices for the book's language + Automatic + system TTS settings), Playback
  (speed 0.5–3x, pitch), Options (follow reading, continue to next chapter, sleep timer 15/30/45/60 min or end of chapter).
- Engine, sentence highlighting and the background service are still DeepSync's `ReaderTts` / `ReaderTtsService`,
  extended with: full voice list + voice by name, sleep timer, sentence count, auto-advance/follow switches.

## Other
- Typography: letter spacing, paragraph indent, hyphenation, text column width, extra themes (Sepia, Dusk, Forest),
  bundled fonts incl. Nunito.
- Credits: features and presets modelled on Novery 1.0.2 (emptycastle, GPL-3.0); fonts copied from Novery keep their own
  open-source licences (mostly SIL OFL 1.1).
- Written without a compiler available: please build and send any errors.
