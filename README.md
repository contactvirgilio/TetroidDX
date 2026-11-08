# Tetroid DX 1.0

Tetris DX (Game Boy Color) on Android, built by **statically recompiling** the
game with [GB Recompiled](https://github.com/arcanite24/gb-recompiled), not by
emulating it. The whole build runs in GitHub Actions; no PC needed.

This repository contains **no game data**. You supply your own legally
obtained ROM, and the app asks for it on first launch, so the APK never
contains any part of the game.

## Features
Everything is optional and off by default. With every patch off, the game runs
its original code.

- **Hard Drop** and **Ghost Piece** (light or dark)
- **Hold** (touch button, or L1 / R1)
- **Next-piece previews**: five layouts, plus **In game** for 4:3 handhelds
- **Lock Delay**
- **SRS Rotation**
- **Randomizer**: Tetris DX, 7-Bag or 35-Bag
- **Skip Celebrations**: Off, Always, or Start skips
- **Korobeiniki**: Original, or Plus (adds a first panic song)
- **Don't Panic**
- **Palettes**: Original, GBC LCD, Swamp, Guideline, Sega, Amber Night
- Scanlines and Mute

No save states, no widescreen.

## Controls
- Touch: on-screen buttons, with quick buttons on top (Mute, Palette, Scanlines, Menu).
- Controller: the touch game buttons hide; D-pad or left stick moves in the
  menu, A changes, B or Back closes. R3 or Mode opens the menu. L2 changes the palette.
- With All files access (asked once, Android 11+), the save and settings live
  in a TetroidDX folder in your device's main storage.

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

Requires Android 8 or newer (arm64). The APK is not published here.

## Repository layout

- `.github/workflows/android.yml`: the whole build (ROM checks, patching,
  recompiling, Android build, a final check that the APK holds no ROM data).
- `android/patch_project.py`: every change to the generated project, each one
  asserting it matched exactly, so upstream changes fail loudly.
- `android/tdx_menu.inc`: the app/game bridge and the pause menu loop.
- `android/tdx_platform.inc`: native additions (ROM loader, options byte,
  palettes, scanlines, Hold and preview queue, touch-overlay bridge).
- `android/*.java`: ROM picker, game activity and touch controls.
- `android/*.asm`, `android/prepare_dontpanic.py`: the toggle patches.

## Credits

See [CREDITS.md](CREDITS.md). Hard drop and ghost piece by lightbulb-sun (MIT),
Don't Panic by DevEd, Skip Celebrations by llakssz, Korobeiniki (Music Fix) by Marc Robledo,
Hold / Preview / Lock Delay idea, testing and the Guideline and Sega palettes by CreeperCraftYT,
and GB Recompiled by arcanite24 (MIT), plus SDL2, Dear ImGui and RGBDS.

Tetris DX is a trademark of its respective owners. This is an unofficial fan
project, not affiliated with or endorsed by Nintendo or The Tetris Company.
