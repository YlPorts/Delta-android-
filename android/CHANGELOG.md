# 0.3.0-dev — continuation of 0.2.0-dev

- Correct the palette catalog path and Xcode component parser; use the authentic
  Purple, LightPurple and DarkGray instead of fallback colors.
- Reuse original game placeholder, settings PDF, pause icons and Standard skins,
  with source hashes and verified retrieval of Git LFS objects.
- Correct library covers/grid spacing/captions and inset grouped Settings;
  replace the pause list with a native grid and real actions.
- Fix portrait skin/game placement, legacy `gameScreenFrame`, representation
  fallbacks, per-edge hit inheritance, bounded asset decoding and skin preferences.
- Add a modular system registry, real Nestopia from NESDeltaCore and VBA-M from
  the original GBADeltaCore engine, with recorded Android portability fixes.
  Preserve Gambatte and every remaining original engine on the roadmap.
- Protect SRAM/RTC with atomic replacement; bind new states to ROM/core and check
  integrity, retaining legacy states and protecting current state on rejection.
- Fix lifecycle resumption behind pause, orientation layouts, audio focus,
  core-reported timing and analog gamepad axes. Implement screenshot export,
  manual cheats, fast forward, held buttons and Menu quick gestures.
- Pin native sources, check ARM64/16KB ELF packaging, include licenses/source,
  add real-core host diagnostics and Android runtime/screenshot CI jobs.

Development build only. Complete visual parity, iOS state/sync interoperability,
commercial game compatibility and physical Galaxy A15 tests remain unverified.
SNES, Genesis, N64 and DS are not yet playable. GBA battery-type/game coverage
remains broader than the authored SRAM diagnostic.
