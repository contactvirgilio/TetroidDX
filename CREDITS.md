# Credits

Tetroid DX is an unofficial fan project. It contains no game data; each player
supplies their own ROM. Tetris DX is a trademark of its respective owners.
Every patch is off by default and switched in the app, so with all off the
game runs its original code.

## Patches

- **Hard Drop and Ghost Piece**: adapted from
  [tetrisdx-harddrop](https://github.com/lightbulb-sun/tetrisdx-harddrop) by
  **lightbulb-sun** (MIT, Copyright (c) 2023; licence at the top of
  `android/harddrop_toggle.asm`). Made after a
  [request](https://www.romhacking.net/forum/index.php?topic=37345.0) by
  **SpongeDan**; ghost piece idea by **[szechp](https://github.com/szechp)**.
- **Don't Panic**: after [hack #6879](https://www.romhacking.net/hacks/6879/)
  by **DevEd**.
- **Skip Celebrations**: after
  [hack #4734](https://www.romhacking.net/hacks/4734/) v1.0 by **llakssz**.
- **Korobeiniki**: after Tetris DX - Music Fix by **Marc Robledo** (2020).
  Music Type A plays the classic theme; the first danger song stays the
  game's own so both danger stages are heard.
- **SRS Rotation** and **Randomizer** (7-Bag, 35-Bag): written for Tetroid
  DX from [tetris.wiki](https://tetris.wiki/Super_Rotation_System) and the
  [Hard Drop wiki](https://harddrop.com/wiki/TGM_randomizer). 35-Bag is
  modelled on the Tetris The Grand Master 3 randomizer, not a byte-exact
  copy. The chosen bag takes over from the second piece of each game; the
  first piece still comes from the game's own setup. Random numbers: xorshift
  7/9/8 by John Metcalf, stirred with the Game Boy timer.
- **Guideline and Sega palettes**: piece colours by **CreeperCraftYT**.

The original patch files are not redistributed here; get them from the links
above. The `.asm` files re-create their changes as switchable routines.

## Recompiler, libraries and tools

- [GB Recompiled](https://github.com/arcanite24/gb-recompiled) by
  **arcanite24** (Brandon G. Neri), MIT: static recompiler and runtime.
- [SDL2](https://github.com/libsdl-org/SDL) (zlib),
  [Dear ImGui](https://github.com/ocornut/imgui) (MIT),
  [RGBDS](https://github.com/gbdev/rgbds) (assembles the patches at build time).
- BPreplay font by George Triantafyllakos, SIL Open Font License 1.1
  (`licenses/BPreplayBold-OFL.txt`).

Android app, menu and touch controls built with **Claude** (Anthropic)
together with the project owner. These credits also appear in the app.
