# Credits

Tetroid DX is an unofficial fan project. It contains no game data; each player
supplies their own ROM. Tetris DX is a trademark of its respective owners.

## Gameplay patches

### Hard drop and ghost piece
`android/harddrop_toggle.asm` is adapted from
[tetrisdx-harddrop](https://github.com/lightbulb-sun/tetrisdx-harddrop) by
**lightbulb-sun**, MIT License, Copyright (c) 2023 lightbulb-sun. The full
license text is kept at the top of the file.
- The original patch was made following a
  [romhacking.net request](https://www.romhacking.net/forum/index.php?topic=37345.0)
  by **SpongeDan**.
- The ghost piece idea came from **[szechp](https://github.com/szechp)**.

Change for Tetroid DX: the separate patched ROMs (hard drop, light ghost, dark
ghost) became one patch whose features are switched in the app's menu.

### Don't Panic
Based on [Don't Panic](https://www.romhacking.net/hacks/6879/) by **DevEd**,
which replaces one instruction in bank 1 at `$4C68` with NOPs.
`android/dontpanic_toggle.asm` moves that instruction into a small routine so
it can be switched on and off. The original patch file is not redistributed
here; get it from the link above.

### Skip Celebrations
Based on [Skip Celebrations](https://www.romhacking.net/hacks/4734/) v1.0 by
**llakssz**, which changes the next-state value at bank 1 `$4569` and `$4570`
from the celebration states to `$08`. `android/skipceleb_toggle.asm` picks
between the original and patched values at runtime. The original patch file is
not redistributed here; get it from the link above.

## Recompiler, libraries and tools
- [GB Recompiled](https://github.com/arcanite24/gb-recompiled) by
  **arcanite24** (Brandon G. Neri), MIT License: static recompiler and runtime.
- [SDL2](https://github.com/libsdl-org/SDL), zlib License.
- [Dear ImGui](https://github.com/ocornut/imgui), MIT License (in-game menu).
- [RGBDS](https://github.com/gbdev/rgbds), used at build time to assemble the
  patches.
- BPreplay font (menu) by George Triantafyllakos, SIL Open Font License 1.1;
  licence text in `licenses/BPreplayBold-OFL.txt`.

These credits also appear in the app, under Credits in the menu.
