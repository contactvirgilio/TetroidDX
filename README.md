# Tetroid DX

An Android port of **Tetris DX** (Game Boy Color), built by **statically
recompiling** the game into native code with
[GB Recompiled](https://github.com/arcanite24/gb-recompiled), not by emulating
it. The whole build runs in GitHub Actions; no PC needed.

This repository contains **no game data**. You supply your own legally
obtained ROM, and the finished app asks for it again on first launch, so the
APK itself never contains any part of the game.

## Features

**Gameplay toggles** (all off by default; with everything off and the Randomizer on Tetris DX,
the game runs its original code):
- **Hard Drop:** press Up to drop the piece instantly.
- **Ghost Piece:** Off, Light or Dark.
- **Don't Panic:** removes the game's danger reaction when the stack gets high.
- **Skip Celebrations:** skips the end-of-game celebration scenes.
- **Korobeiniki:** Music Type A plays the classic Tetris theme (both danger
  stages still change the music).
- **SRS Rotation:** Super Rotation System spawn orientation, wall kicks and
  next-piece preview.
- **Randomizer:** Tetris DX (original), 7-Bag or 35-Bag. The bag takes over
  from the second piece of each game.

**Display:**
- **Palettes:** Original, GBC LCD (colour corrected to look like the real Game
  Boy Color screen, default), Pastel, Swamp, Sunset, and Guideline and Sega
  piece colours by CreeperCraftYT.
- **Scanlines.**
- Portrait and landscape; in portrait the game sits at the top with the
  controls below.

**Controls and app:**
- Touch controls (D-pad, A, B, Select, Start, Menu) that hide when a gamepad is
  used and come back when you touch the screen.
- PATCHES card tiles to switch:
  Hard Drop, Ghost Piece, Don't Panic, Skip Celebrations, Korobeiniki, SRS
  Rotation and Randomizer. Opened with the Menu quick button, Select+Down, Android Back, Guide
  or a gamepad shortcut. The game pauses while it is open.
- Quick buttons top right (Mute, Palette, Scanlines, Menu) that fade after 15 s.
- Gamepad shortcuts (buttons held together): Menu, Toggle Hard Drop, Cycle
  Ghost Piece, Cycle Palette; set them on the Controls page.
- Touch A and B light up in the original Game Boy's wine red when pressed.
- Controller button remapping from the Controls page.
- Low-latency audio tuned for Android devices.
- The cartridge's own battery save (high scores) works. Save states are
  deliberately not included.
- With All files access (asked once, Android 11+), the save and settings are
  kept in a TetroidDX folder in your device's main storage, and a ROM placed there is
  found automatically.

## How the toggles work

The gameplay changes are small Game Boy assembly patches applied at build
time. Instead of changing the game permanently, each patch jumps to a short
routine that checks one bit of an options byte in unused ROM space and then
runs either the original code or the modified code. The app sets that byte
from its menu every frame, so toggles take effect immediately. See
`android/*.asm`.

## Building it yourself

You need the ROM **Tetris DX (World) (SGB Enhanced).gbc** (No-Intro, CRC32
`69989152`). Never commit a ROM to a public repository.

1. Make a **private** copy of this repository. Forks of a public repository
   stay public, so use GitHub's **Import repository** (or copy the files) into
   a new private repository instead.
2. Provide the ROM in one of two ways:
   - put the `.gbc` file in the root of your **private** copy, or
   - store a direct download link to it in a repository secret named
     `TDX_ROM_URL`.
3. Optional but recommended, so updates install over each other: add your own
   signing key as secrets: `TDX_KEYSTORE_BASE64` (the keystore file, base64),
   `TDX_STORE_PASS`, and optionally `TDX_KEY_PASS` and `TDX_KEY_ALIAS`
   (default `tetrisdx`). Without them, each build gets a throwaway key.
4. Actions → **Build Tetroid DX APK** → Run workflow, then download the
   `TetroidDX-apk` artifact.
5. Install it and pick your ROM when the app asks. It is checked against the
   ROM the build was made from.

Requires Android 8 or newer (arm64).

## Repository layout

- `.github/workflows/android.yml`: the whole build (ROM checks, patching,
  recompiling, Android build, a final check that the APK holds no ROM data).
- `android/patch_project.py`: every change to the generated project, each one
  asserting it matched exactly, so upstream changes fail loudly.
- `android/tdx_menu.inc`: the in-game menu (Dear ImGui).
- `android/tdx_platform.inc`: native additions (ROM loader, options byte,
  palettes, scanlines, Select+Down, touch-overlay bridge).
- `android/*.java`: ROM picker, game activity and touch controls.
- `android/*.asm`, `android/prepare_dontpanic.py`: the toggle patches.

## Credits

See [CREDITS.md](CREDITS.md). In short: hard drop and ghost piece by
lightbulb-sun (MIT), Don't Panic by DevEd, Skip Celebrations by llakssz, Korobeiniki (Music Fix) by Marc Robledo, and
GB Recompiled by arcanite24 (MIT), plus SDL2, Dear ImGui and RGBDS.

Tetris DX is a trademark of its respective owners. This is an unofficial fan
project, not affiliated with or endorsed by Nintendo or The Tetris Company.
