#!/usr/bin/env python3
"""Patch a gbrecomp --android project for the Tetris DX Android build.

Usage: patch_project.py <generated_project_dir> <rom_sha256> <original_rom> <patched_rom>

<original_rom> is the player's ROM version (what the app checks).
<patched_rom> is that ROM with android/harddrop_toggle.asm applied; the C code
is generated from it, and the app applies the same bytes after loading.

Edits only the generated project in the CI workspace. Every edit asserts
how many times it matched, so a change upstream fails loudly instead of
silently doing nothing.
"""
import glob
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE = "org.tetrisdx.recomp"


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def sub_exact(text, pattern, repl, expected, label, flags=0):
    new, n = re.subn(pattern, repl, text, flags=flags)
    if n != expected:
        sys.exit(f"PATCH FAILED [{label}]: expected {expected} match(es), got {n}")
    print(f"patched [{label}] x{n}")
    return new


def replace_exact(text, old, new, expected, label):
    n = text.count(old)
    if n != expected:
        sys.exit(f"PATCH FAILED [{label}]: expected {expected} match(es), got {n}")
    print(f"patched [{label}] x{n}")
    return text.replace(old, new)


def patch_rom_stub(proj):
    """Replace the embedded ROM bytes with an empty buffer filled at startup."""
    rom_files = glob.glob(os.path.join(proj, "*_rom.c"))
    if len(rom_files) != 1:
        sys.exit(f"PATCH FAILED [rom file]: expected 1 *_rom.c, found {rom_files}")
    rom_c = rom_files[0]
    text = read(rom_c)
    m = re.search(r"const uint8_t (\w+)\[(\d+)\] = \{", text)
    if not m:
        sys.exit("PATCH FAILED [rom array]: array declaration not found")
    sym = m.group(1)
    text = sub_exact(
        text,
        r"const uint8_t " + re.escape(sym) + r"\[(\d+)\] = \{.*?\n\};",
        r"uint8_t " + sym + r"[\1]; /* filled from the player's ROM at startup */",
        1, "rom bytes removed", flags=re.S)
    write(rom_c, text)

    # The init function that copies the ROM into the runtime context.
    hits = []
    for path in glob.glob(os.path.join(proj, "*.c")):
        if path == rom_c:
            continue
        if f"extern const uint8_t {sym}[];" in read(path):
            hits.append(path)
    if len(hits) != 1:
        sys.exit(f"PATCH FAILED [rom extern]: expected 1 file, found {hits}")
    init_c = hits[0]
    text = read(init_c)
    text = replace_exact(
        text,
        f"extern const uint8_t {sym}[];",
        f"extern uint8_t {sym}[];\n"
        "extern int tdx_fill_rom(uint8_t* dst, size_t size);\n"
        "#include <stdlib.h>",
        1, "rom extern")
    text = sub_exact(
        text,
        r"( *)gb_context_load_rom\(ctx, " + re.escape(sym) + r", (\w+)\);",
        r"\1if (!tdx_fill_rom(" + sym + r", \2)) { exit(1); }\n"
        r"\1gb_context_load_rom(ctx, " + sym + r", \2);",
        1, "rom loader call")
    write(init_c, text)


def rom_patch_table(original_rom, patched_rom):
    a = open(original_rom, "rb").read()
    b = open(patched_rom, "rb").read()
    if len(a) != len(b):
        sys.exit("PATCH FAILED [rom diff]: patched ROM has a different size")
    diff = [(i, b[i]) for i in range(len(a)) if a[i] != b[i]]
    if not 1 <= len(diff) <= 400:
        sys.exit(f"PATCH FAILED [rom diff]: unexpected number of changed bytes: {len(diff)}")
    print(f"ROM patch: {len(diff)} changed bytes")
    rows = ",\n".join(f"    {{0x{o:06X}u, 0x{v:02X}u}}" for o, v in diff)
    return ("static const TdxRomPatchByte g_tdx_rom_patch[] = {\n" + rows + "\n};\n")


def patch_platform(proj, patch_table):
    path = os.path.join(proj, "runtime", "src", "platform_sdl.cpp")
    text = read(path)
    inc = read(os.path.join(HERE, "tdx_platform.inc"))
    inc = replace_exact(inc, "/*@TDX_ROM_PATCH@*/", patch_table, 1, "rom patch table")

    text = replace_exact(
        text, "static bool g_show_menu = false;\n",
        "static bool g_show_menu = false;\n" + inc, 1, "globals + helpers")

    text = replace_exact(
        text,
        "            memcpy(dst, src, GB_SCREEN_WIDTH * sizeof(uint32_t));\n"
        "            continue;\n",
        "            tdx_copy_row(dst, src, GB_SCREEN_WIDTH);\n"
        "            continue;\n",
        1, "colour correction + palettes")

    text = replace_exact(
        text,
        "    SDL_RenderCopy(g_renderer, g_texture, NULL, &g_game_viewport);\n",
        "    SDL_RenderCopy(g_renderer, g_texture, NULL, &g_game_viewport);\n"
        "    tdx_draw_scanlines(g_renderer, &g_game_viewport);\n",
        1, "scanlines")

    text = replace_exact(
        text,
        "static void render_frame_internal(const uint32_t* framebuffer, bool count_guest_frame) {\n",
        "static void render_frame_internal(const uint32_t* framebuffer, bool count_guest_frame) {\n"
        "    tdx_sync_options(g_registered_ctx);\n"
        "    if (count_guest_frame) tdx_save_battery(g_registered_ctx, false);\n",
        1, "options sync")

    text = replace_exact(
        text,
        "    g_game_viewport.y = (window_h - viewport_h) / 2;\n",
        "    g_game_viewport.y = tdx_viewport_y(window_w, window_h, viewport_h);\n",
        1, "portrait viewport")

    text = replace_exact(
        text,
        "        bool fullscreen = g_fullscreen;\n"
        "        if (ImGui::Checkbox(\"Fullscreen\", &fullscreen)) {\n",
        MENU_BLOCK +
        "        bool fullscreen = g_fullscreen;\n"
        "        if (ImGui::Checkbox(\"Fullscreen\", &fullscreen)) {\n",
        1, "menu toggles")

    text = replace_exact(
        text,
        "                if (strcmp(key, \"savestate.slot\") == 0) {\n",
        "                if (strcmp(key, \"display.scanlines\") == 0) {\n"
        "                    g_tdx_scanlines = (strcmp(value, \"0\") != 0);\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"display.color_correction\") == 0) {\n"
        "                    /* older builds: colour correction off meant raw colours */\n"
        "                    if (strcmp(value, \"0\") == 0 && g_tdx_palette == TDX_PAL_GBC_LCD) g_tdx_palette = TDX_PAL_ORIGINAL;\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"tdx.harddrop\") == 0) {\n"
        "                    g_tdx_harddrop = (strcmp(value, \"0\") != 0);\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"display.pal\") == 0) {\n"
        "                    long pv = strtol(value, NULL, 10);\n"
        "                    g_tdx_palette = (pv >= 0 && pv < TDX_PAL_COUNT) ? (int)pv : TDX_PAL_GBC_LCD;\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"tdx.skipceleb\") == 0) {\n"
        "                    g_tdx_skipceleb = (strcmp(value, \"0\") != 0);\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"tdx.dontpanic\") == 0) {\n"
        "                    g_tdx_dontpanic = (strcmp(value, \"0\") != 0);\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"tdx.ghost\") == 0) {\n"
        "                    long g = strtol(value, NULL, 10);\n"
        "                    g_tdx_ghost = (g >= 0 && g <= 2) ? (int)g : 0;\n"
        "                    continue;\n"
        "                }\n"
        "                if (strcmp(key, \"savestate.slot\") == 0) {\n",
        1, "prefs load")

    text = replace_exact(
        text,
        "    fprintf(file, \"savestate.slot=%d\\n\", g_savestate_slot);\n",
        "    fprintf(file, \"savestate.slot=%d\\n\", g_savestate_slot);\n"
        "    fprintf(file, \"display.scanlines=%d\\n\", g_tdx_scanlines ? 1 : 0);\n"
        "    fprintf(file, \"tdx.harddrop=%d\\n\", g_tdx_harddrop ? 1 : 0);\n"
        "    fprintf(file, \"tdx.ghost=%d\\n\", g_tdx_ghost);\n"
        "    fprintf(file, \"tdx.dontpanic=%d\\n\", g_tdx_dontpanic ? 1 : 0);\n"
        "    fprintf(file, \"tdx.skipceleb=%d\\n\", g_tdx_skipceleb ? 1 : 0);\n"
        "    fprintf(file, \"display.pal=%d\\n\", g_tdx_palette);\n",
        1, "prefs save")

    text = remove_savestates(text)
    text = install_menu(text)
    text = replace_exact(
        text,
        "        case SDL_APP_WILLENTERBACKGROUND:\n"
        "            set_app_suspended(true);\n",
        "        case SDL_APP_TERMINATING:\n"
        "        case SDL_APP_WILLENTERBACKGROUND:\n"
        "            tdx_save_battery(g_registered_ctx, true);\n"
        "            set_app_suspended(true);\n",
        1, "save on background")
    text = replace_exact(
        text,
        "    g_joypad_buttons = g_manual_joypad_buttons & g_script_joypad_buttons;\n",
        "    g_joypad_buttons = g_manual_joypad_buttons & g_script_joypad_buttons;\n"
        "    tdx_check_menu_combo(g_manual_joypad_buttons, g_manual_joypad_dpad);\n",
        1, "select+down menu")
    text = lower_audio_latency(text)
    # The Palette option only recolours original Game Boy (DMG) games; Tetris DX
    # runs in Game Boy Color mode, where it is greyed out. Remove it.
    text = sub_exact(
        text,
        r"        const bool cgb_colors = g_registered_ctx &&\n.*?"
        r"ImGui::TextDisabled\(\"Game Boy Color uses the game's color palettes\.\"\);\n        \}\n",
        "", 1, "remove palette option", flags=re.S)
    write(path, text)


def lower_audio_latency(text):
    """Upstream uses a 2048-sample SDL buffer (~46 ms) plus an 80 ms queue,
    which is audibly late on phones. Use 512 samples (~12 ms) on Android and a
    40 ms queue. The Audio menu's latency slider can still raise it."""
    text = replace_exact(
        text, "    want.samples = 2048;\n",
        "#if defined(__ANDROID__)\n    want.samples = 512;\n#else\n    want.samples = 2048;\n#endif\n",
        1, "audio device buffer")
    text = replace_exact(
        text, "static uint32_t g_audio_latency_ms = 80;",
        "static uint32_t g_audio_latency_ms = 40;", 1, "audio latency default")
    text = replace_exact(
        text, "    g_audio_latency_ms = 80;\n",
        "    g_audio_latency_ms = 40;\n", 1, "audio latency reset default")
    # Installs from the first build saved the old default; treat it as the new one.
    text = replace_exact(
        text, "                        g_audio_latency_ms = (uint32_t)parsed;\n",
        "                        g_audio_latency_ms = (parsed == 80) ? 40u : (uint32_t)parsed;\n",
        1, "audio latency old default")
    return text


def install_menu(text):
    """Replace the runtime's settings menu with the Tetroid DX menu
    (android/tdx_menu.inc), which also pauses the game while it is open."""
    menu = read(os.path.join(HERE, "tdx_menu.inc"))
    head = "static void render_frame_internal(const uint32_t* framebuffer, bool count_guest_frame) {\n"
    text = replace_exact(text, head, menu + head, 1, "menu code")
    text = replace_exact(
        text, "    if (g_show_menu) {\n",
        "    if (g_show_menu) {\n        tdx_draw_menu();\n    }\n    if (false) {  /* runtime menu replaced */\n",
        1, "menu replaces runtime menu")
    text = replace_exact(
        text,
        "            if (!handle_runtime_event(&event, ctx)) {\n"
        "                return false;\n"
        "            }\n"
        "        }\n"
        "\n"
        "        while (g_app_suspended) {\n",
        "            if (!handle_runtime_event(&event, ctx)) {\n"
        "                return false;\n"
        "            }\n"
        "        }\n"
        "        if (g_show_menu && ImGui::GetCurrentContext() != NULL && !tdx_menu_loop(ctx)) {\n"
        "            return false;\n"
        "        }\n"
        "\n"
        "        while (g_app_suspended) {\n",
        1, "menu pauses game")
    text = replace_exact(
        text, "    ImGui::StyleColorsDark();\n",
        "    ImGui::StyleColorsDark();\n    tdx_load_menu_font();\n",
        1, "menu font")
    return text


def remove_savestates(text):
    """Tetris DX build: no save states. Remove every way to reach them."""
    acts = "(?:SAVE_STATE|LOAD_STATE|PREVIOUS_STATE_SLOT|NEXT_STATE_SLOT)"
    # Default key/controller bindings (F5-F8, controller X and Y).
    text = sub_exact(
        text,
        r"    g_(?:keyboard|controller)_bindings\[GB_INPUT_ACTION_" + acts +
        r"\]\[0\] = make_binding\([^;]*\);\n",
        "", 6, "savestate default bindings")
    # Shortcut actions do nothing.
    text = sub_exact(
        text,
        r"(                case GB_INPUT_ACTION_SAVE_STATE:\n).*?(?=                case GB_INPUT_ACTION_TOGGLE_OVERLAY:)",
        "                case GB_INPUT_ACTION_SAVE_STATE:\n"
        "                case GB_INPUT_ACTION_LOAD_STATE:\n"
        "                case GB_INPUT_ACTION_PREVIOUS_STATE_SLOT:\n"
        "                case GB_INPUT_ACTION_NEXT_STATE_SLOT:\n"
        "                    break; /* save states disabled */\n\n",
        1, "savestate shortcuts", flags=re.S)
    # Menu section.
    text = sub_exact(
        text,
        r"        ImGui::Separator\(\);\n        ImGui::TextDisabled\(\"Savestates\"\);\n.*?"
        r"(?=        ImGui::Separator\(\);\n        ImGui::TextDisabled\(\"Controls\"\);)",
        "", 1, "savestate menu section", flags=re.S)
    text = replace_exact(text, "max speed, savestates, overlay", "max speed, overlay",
                         1, "shortcut help text")
    # Hide the actions in the bindings editor.
    text = sub_exact(
        text,
        r"(for \(int action = \(int\)action_begin; action < \(int\)action_end; action\+\+\) \{\n)",
        r"\1        if (action >= (int)GB_INPUT_ACTION_SAVE_STATE &&\n"
        r"            action <= (int)GB_INPUT_ACTION_NEXT_STATE_SLOT) continue;\n",
        1, "hide savestate bindings")
    # Belt and braces: the functions themselves refuse.
    for fn in ("save_savestate_slot", "load_savestate_slot", "delete_savestate_slot"):
        text = replace_exact(
            text,
            f"static bool {fn}(GBContext* ctx, int slot) {{\n",
            f"static bool {fn}(GBContext* ctx, int slot) {{\n"
            "    if (ctx || !ctx) { (void)slot; return false; } /* save states disabled */\n",
            1, f"{fn} disabled")
    return text


MENU_BLOCK = r"""        ImGui::TextDisabled("Tetroid DX");
        if (ImGui::Checkbox("Hard Drop (press Up)", &g_tdx_harddrop)) {
            save_runtime_preferences();
        }
        {
            static const char* tdx_ghost_names[] = {"Off", "Light", "Dark"};
            if (ImGui::Combo("Ghost Piece", &g_tdx_ghost, tdx_ghost_names, 3)) {
                save_runtime_preferences();
            }
        }
        if (ImGui::Checkbox("Don't Panic", &g_tdx_dontpanic)) {
            save_runtime_preferences();
        }
        if (ImGui::Checkbox("Skip Celebrations", &g_tdx_skipceleb)) {
            save_runtime_preferences();
        }
        {
            static const char* tdx_palette_names[] = {"Original", "GBC LCD", "Pastel", "Swamp", "Sunset"};
            if (ImGui::Combo("Palette", &g_tdx_palette, tdx_palette_names, TDX_PAL_COUNT)) {
                save_runtime_preferences();
            }
        }
        if (ImGui::Checkbox("Scanlines", &g_tdx_scanlines)) {
            save_runtime_preferences();
        }
        if (ImGui::CollapsingHeader("Credits")) {
            ImGui::TextWrapped("Hard drop and ghost piece: tetrisdx-harddrop by lightbulb-sun "
                               "(github.com/lightbulb-sun/tetrisdx-harddrop), MIT License, "
                               "Copyright (c) 2023 lightbulb-sun. Made following a "
                               "romhacking.net request by SpongeDan. Ghost piece idea by szechp.");
            ImGui::TextWrapped("Don't Panic by DevEd (romhacking.net/hacks/6879), "
                               "made switchable for this build.");
            ImGui::TextWrapped("Skip Celebrations v1.0 by llakssz (romhacking.net/hacks/4734), "
                               "made switchable for this build.");
            ImGui::TextWrapped("Recompiled with GB Recompiled by arcanite24 "
                               "(github.com/arcanite24/gb-recompiled), MIT License.");
            ImGui::TextWrapped("Uses SDL2 (zlib License) and Dear ImGui (MIT License).");
            ImGui::TextWrapped("Tetris DX is a trademark of its owners. Unofficial fan port; "
                               "you supply your own ROM.");
        }
        ImGui::Separator();
"""


def squares(off, size=17, pitch=18, x0=27, y0=36):
    cells = [(0, 0), (1, 0), (2, 0), (1, 1)]   # T tetromino
    return " ".join(f"M{x0 + cx * pitch + off:.1f},{y0 + cy * pitch + off:.1f}h{size}v{size}h-{size}z"
                    for cx, cy in cells)


ICON_BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:fillColor="#D9467F" android:pathData="M0,0h108v108h-108z" />
  <path android:fillColor="#F07BAA"
      android:pathData="M54,54m-44,0a44,44 0 1,0 88,0a44,44 0 1,0 -88,0" />
</vector>
"""

ICON_FOREGROUND = f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:fillColor="#1F7A35" android:pathData="{squares(1.5)}" />
  <path android:fillColor="#43D05E" android:pathData="{squares(0)}" />
</vector>
"""

ICON_ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@drawable/ic_launcher_background" />
  <foreground android:drawable="@drawable/ic_launcher_foreground" />
  <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""


MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.gamepad" android:required="false" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="false"
        android:hardwareAccelerated="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name">
        <meta-data android:name="SDL_ENV.SDL_ANDROID_TRAP_BACK_BUTTON" android:value="1" />

        <activity
            android:name=".SetupActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".GameActivity"
            android:alwaysRetainTaskState="true"
            android:configChanges="layoutDirection|locale|orientation|uiMode|screenLayout|screenSize|smallestScreenSize|keyboard|keyboardHidden|navigation"
            android:exported="false"
            android:launchMode="singleTask"
            android:preferMinimalPostProcessing="true"
            android:screenOrientation="fullUser"
            android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen" />
    </application>
</manifest>
"""

BUILD_GRADLE = """plugins {
    id 'com.android.application'
}

def sdl2SourceDir = providers.gradleProperty("SDL2_SOURCE_DIR").orNull ?: System.getenv("SDL2_SOURCE_DIR")
if (!sdl2SourceDir) {
    throw new GradleException("SDL2_SOURCE_DIR is required.")
}

android {
    namespace "org.tetrisdx.recomp"
    compileSdk 34
    ndkVersion System.getenv("TDX_NDK_VERSION") ?: "26.3.11579264"

    defaultConfig {
        applicationId "org.tetrisdx.recomp"
        minSdk 26
        targetSdk 34
        versionCode 16
        versionName "1.1.5"
        externalNativeBuild {
            cmake {
                arguments "-DANDROID_STL=c++_shared", "-DSDL2_SOURCE_DIR=${sdl2SourceDir}"
                abiFilters "arm64-v8a"
            }
        }
    }

    signingConfigs {
        tdx {
            storeFile file(System.getenv("TDX_KEYSTORE"))
            storePassword System.getenv("TDX_STORE_PASS")
            keyAlias System.getenv("TDX_KEY_ALIAS")
            keyPassword System.getenv("TDX_KEY_PASS")
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.tdx
        }
        debug {
            signingConfig signingConfigs.tdx
        }
    }

    externalNativeBuild {
        cmake {
            path file("jni/CMakeLists.txt")
        }
    }

    sourceSets {
        main {
            java.srcDirs += [file("${sdl2SourceDir}/android-project/app/src/main/java")]
        }
    }

    lint {
        abortOnError false
        checkReleaseBuilds false
    }
}
"""


def patch_android_cmake(proj):
    """Upstream's Android CMakeLists leaves out runtime sources the desktop
    build compiles (gbrt_port.c, gbrt_data_mod.c, ...), so libmain.so fails to
    link. Add every runtime source the desktop build uses that is missing."""
    desktop = read(os.path.join(proj, "CMakeLists.txt"))
    want = sorted(set(re.findall(r"\$\{GBRT_DIR\}/src/(\w+\.(?:cpp|c))\b", desktop)))
    path = os.path.join(proj, "android", "app", "jni", "src", "CMakeLists.txt")
    text = read(path)
    have = set(re.findall(r"\$\{GBRT_DIR\}/src/(\w+\.(?:cpp|c))\b", text))
    missing = [f for f in want if f not in have]
    if not want or "platform_sdl.cpp" not in want:
        sys.exit("PATCH FAILED [android cmake]: could not read desktop source list")
    if missing:
        lines = "".join(f"    ${{GBRT_DIR}}/src/{f}\n" for f in missing)
        text = replace_exact(
            text,
            "    ${GBRT_DIR}/src/platform_sdl.cpp\n",
            "    ${GBRT_DIR}/src/platform_sdl.cpp\n" + lines,
            1, f"android cmake +{len(missing)} runtime sources ({', '.join(missing)})")
        write(path, text)
    else:
        print("android cmake: no missing runtime sources")


def patch_android(proj, rom_sha256):
    app = os.path.join(proj, "android", "app")
    if not os.path.isdir(app):
        sys.exit("PATCH FAILED [android dir]: generator did not write android/app")
    write(os.path.join(app, "src", "main", "AndroidManifest.xml"), MANIFEST)
    write(os.path.join(app, "build.gradle"), BUILD_GRADLE)
    font = os.path.join(HERE, "BPreplayBold.otf")
    if not os.path.isfile(font):
        sys.exit("PATCH FAILED [font]: android/BPreplayBold.otf missing")
    assets = os.path.join(app, "src", "main", "assets")
    os.makedirs(assets, exist_ok=True)
    shutil.copyfile(font, os.path.join(assets, "BPreplayBold.otf"))
    res = os.path.join(app, "src", "main", "res")
    write(os.path.join(res, "drawable", "ic_launcher_background.xml"), ICON_BACKGROUND)
    write(os.path.join(res, "drawable", "ic_launcher_foreground.xml"), ICON_FOREGROUND)
    write(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), ICON_ADAPTIVE)
    print("wrote manifest, app/build.gradle and launcher icon")

    java_dir = os.path.join(app, "src", "main", "java", *PACKAGE.split("."))
    if os.path.isdir(java_dir):
        shutil.rmtree(java_dir)
    os.makedirs(java_dir)
    for name in ("SetupActivity.java", "GameActivity.java", "TouchControls.java"):
        src = os.path.join(HERE, name)
        if not os.path.isfile(src):
            sys.exit(f"PATCH FAILED [java]: android/{name} missing from repo (check the upload name)")
        text = read(src)
        if name == "SetupActivity.java":
            text = replace_exact(text, "@ROM_SHA256@", rom_sha256.lower(), 1, "rom sha in SetupActivity")
        write(os.path.join(java_dir, name), text)
    print("copied Java sources")


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    proj, rom_sha256, original_rom, patched_rom = sys.argv[1:5]
    if not re.fullmatch(r"[0-9a-fA-F]{64}", rom_sha256):
        sys.exit("PATCH FAILED: second argument must be a SHA-256 hex string")
    patch_rom_stub(proj)
    patch_platform(proj, rom_patch_table(original_rom, patched_rom))
    patch_android_cmake(proj)
    patch_android(proj, rom_sha256)
    print("All patches applied.")


if __name__ == "__main__":
    main()
