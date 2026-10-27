/* ---- Tetris DX Android additions (inserted by android/patch_project.py) ---- */
#include <math.h>
#if defined(__ANDROID__)
#include <jni.h>
#endif

/* Display options. Scanlines saved as display.scanlines; the palette
 * (including colour correction) is below, saved as display.palette. */
static bool g_tdx_scanlines = false;
static uint32_t* g_tdx_cc_lut = NULL;

/* Hard drop and ghost piece (see android/harddrop_toggle.asm, based on
 * lightbulb-sun's tetrisdx-harddrop, MIT License). The patched game code
 * reads one options byte at the end of ROM bank 1; we set it from these.
 * Saved as tdx.harddrop and tdx.ghost (0 off, 1 light, 2 dark). */
static bool g_tdx_harddrop = false;
/* Don't Panic by DevEd (romhacking.net #6879), see dontpanic_toggle.asm.
 * Saved as tdx.dontpanic. */
static bool g_tdx_dontpanic = false;
/* Skip Celebrations by llakssz (romhacking.net #4734), see skipceleb_toggle.asm.
 * Saved as tdx.skipceleb. */
static bool g_tdx_skipceleb = false;
static int g_tdx_ghost = 0;
#define TDX_OPTIONS_OFFSET 0x7FFFu

static uint8_t tdx_options_byte(void) {
    uint8_t v = 0;
    if (g_tdx_harddrop) v |= 0x01;
    if (g_tdx_ghost == 1) v |= 0x02;
    if (g_tdx_ghost == 2) v |= 0x06;
    if (g_tdx_dontpanic) v |= 0x08;
    if (g_tdx_skipceleb) v |= 0x10;
    return v;
}

/* Called every frame so menu changes take effect at once. */
static void tdx_sync_options(GBContext* ctx) {
    if (ctx && ctx->rom && ctx->rom_size > TDX_OPTIONS_OFFSET) {
        ctx->rom[TDX_OPTIONS_OFFSET] = tdx_options_byte();
    }
}

/* Bytes that turn the player's original ROM into the patched ROM the code
 * was generated from. Written by patch_project.py from a diff of the two;
 * it holds only the patch's own bytes, not game data. */
struct TdxRomPatchByte { uint32_t offset; uint8_t value; };
/*@TDX_ROM_PATCH@*/

/* GBC LCD colour correction (the widely used higan/bsnes curve).
 * The GBC screen was darker and less saturated than a modern panel, so raw
 * RGB555 values look too vivid. Indexed by 15-bit RGB555 colour. */
static void tdx_build_color_lut(void) {
    if (g_tdx_cc_lut) {
        return;
    }
    g_tdx_cc_lut = (uint32_t*)malloc(sizeof(uint32_t) * 32768u);
    if (!g_tdx_cc_lut) {
        return;
    }
    for (uint32_t i = 0; i < 32768u; ++i) {
        const int r = (int)(i & 31u);
        const int g = (int)((i >> 5) & 31u);
        const int b = (int)((i >> 10) & 31u);
        int R = r * 26 + g * 4 + b * 2;
        int G = g * 24 + b * 8;
        int B = r * 6 + g * 4 + b * 22;
        if (R > 960) R = 960;
        if (G > 960) G = 960;
        if (B > 960) B = 960;
        g_tdx_cc_lut[i] = 0xFF000000u | ((uint32_t)(R >> 2) << 16) |
                          ((uint32_t)(G >> 2) << 8) | (uint32_t)(B >> 2);
    }
}

/* Copies one row of ARGB8888 pixels, applying colour correction. */
static void tdx_copy_row_corrected(uint32_t* dst, const uint32_t* src, int count) {
    tdx_build_color_lut();
    if (!g_tdx_cc_lut) {
        memcpy(dst, src, (size_t)count * sizeof(uint32_t));
        return;
    }
    for (int x = 0; x < count; ++x) {
        const uint32_t p = src[x];
        const uint32_t idx = ((p >> 19) & 31u) | (((p >> 11) & 31u) << 5) |
                             (((p >> 3) & 31u) << 10);
        dst[x] = g_tdx_cc_lut[idx];
    }
}

/* Palettes. Saved as display.pal:
 *   0 Original  - the game's raw GBC colours
 *   1 GBC LCD   - colour corrected to look like the real GBC screen (default)
 *   2 Pastel    - colourful pixels lightened and softened (darks kept)
 *   3 Swamp     - deep-green-to-moss gradient chosen by brightness
 *   4 Sunset    - violet-to-cream gradient chosen by brightness
 * Only how the frame is drawn changes; the game itself is untouched. */
enum { TDX_PAL_ORIGINAL = 0, TDX_PAL_GBC_LCD = 1, TDX_PAL_PASTEL = 2, TDX_PAL_SWAMP = 3,
       TDX_PAL_SUNSET = 4, TDX_PAL_COUNT = 5 };
static int g_tdx_palette = TDX_PAL_GBC_LCD;
static uint32_t* g_tdx_pal_lut[TDX_PAL_COUNT] = {NULL, NULL, NULL, NULL, NULL};

static uint32_t tdx_mix(uint32_t a, uint32_t b, double t) {
    const int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
    const int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
    const int r = (int)(ar + (br - ar) * t + 0.5);
    const int g = (int)(ag + (bg - ag) * t + 0.5);
    const int bl = (int)(ab + (bb - ab) * t + 0.5);
    return 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)bl;
}

static uint32_t tdx_gradient(const uint32_t stops[4], double lum) {
    if (lum < 0.0) lum = 0.0;
    if (lum > 1.0) lum = 1.0;
    const double pos = lum * 3.0;
    int seg = (int)pos;
    if (seg > 2) seg = 2;
    return tdx_mix(stops[seg], stops[seg + 1], pos - seg);
}

/* HLS helpers (same maths as Python's colorsys). */
static double tdx_hls_v(double m1, double m2, double h) {
    h -= (double)(long)h;
    if (h < 0.0) h += 1.0;
    if (h < 1.0 / 6.0) return m1 + (m2 - m1) * h * 6.0;
    if (h < 0.5) return m2;
    if (h < 2.0 / 3.0) return m1 + (m2 - m1) * (2.0 / 3.0 - h) * 6.0;
    return m1;
}

/* Pastel ("Sherbet"): only colourful pixels are lightened and softened, with a
 * light pink warmth; blacks, greys and whites stay as they are, so the dark
 * playfield keeps its contrast. */
static uint32_t tdx_pastel(double r, double g, double b) {
    const double maxc = r > g ? (r > b ? r : b) : (g > b ? g : b);
    const double minc = r < g ? (r < b ? r : b) : (g < b ? g : b);
    const double l = (maxc + minc) / 2.0;
    double h = 0.0, s = 0.0;
    if (maxc != minc) {
        const double d = maxc - minc;
        s = (l <= 0.5) ? d / (maxc + minc) : d / (2.0 - maxc - minc);
        const double rc = (maxc - r) / d, gc = (maxc - g) / d, bc = (maxc - b) / d;
        if (r == maxc) h = bc - gc;
        else if (g == maxc) h = 2.0 + rc - bc;
        else h = 4.0 + gc - rc;
        h = h / 6.0;
        h -= (double)(long)h;
        if (h < 0.0) h += 1.0;
    }
    const double w = s * (1.0 - fabs(2.0 * l - 1.0));      /* colourfulness, 0 for greys */
    const double light_to = 0.80, sat_keep = 0.70, tint_amt = 0.15;
    const double l2 = (light_to > l) ? l + (light_to - l) * w * 0.8 : l;
    const double s2 = s * (1.0 - (1.0 - sat_keep) * w);
    double out[3];
    if (s2 == 0.0) {
        out[0] = out[1] = out[2] = l2;
    } else {
        const double m2 = (l2 <= 0.5) ? l2 * (1.0 + s2) : l2 + s2 - l2 * s2;
        const double m1 = 2.0 * l2 - m2;
        out[0] = tdx_hls_v(m1, m2, h + 1.0 / 3.0);
        out[1] = tdx_hls_v(m1, m2, h);
        out[2] = tdx_hls_v(m1, m2, h - 1.0 / 3.0);
    }
    static const double tint[3] = {255.0 / 255.0, 220.0 / 255.0, 235.0 / 255.0};
    uint32_t px = 0xFF000000u;
    for (int k = 0; k < 3; ++k) {
        double v = out[k] + (tint[k] - out[k]) * tint_amt * w;
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        px |= (uint32_t)(int)(v * 255.0 + 0.5) << (16 - 8 * k);
    }
    return px;
}

static void tdx_build_palette_lut(int mode) {
    if (mode < TDX_PAL_PASTEL || mode >= TDX_PAL_COUNT || g_tdx_pal_lut[mode]) {
        return;
    }
    uint32_t* lut = (uint32_t*)malloc(sizeof(uint32_t) * 32768u);
    if (!lut) {
        return;
    }
    /* Swamp: deep murky green, forest, moss, pale lichen. */
    static const uint32_t swamp[4] = {0x0C1A0E, 0x2D5A2B, 0x7FA34C, 0xDCE9B5};
    /* Sunset: deep violet, magenta, coral, warm cream. */
    static const uint32_t sunset[4] = {0x24103F, 0x8E2C6E, 0xF0715A, 0xFFE9C4};
    for (uint32_t i = 0; i < 32768u; ++i) {
        const double r = (double)(i & 31u) / 31.0;
        const double g = (double)((i >> 5) & 31u) / 31.0;
        const double b = (double)((i >> 10) & 31u) / 31.0;
        const double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        if (mode == TDX_PAL_PASTEL) {
            lut[i] = tdx_pastel(r, g, b);
        } else {
            lut[i] = tdx_gradient(mode == TDX_PAL_SWAMP ? swamp : sunset, lum);
        }
    }
    g_tdx_pal_lut[mode] = lut;
}

/* Copies one screen row using the selected palette. */
static void tdx_copy_row(uint32_t* dst, const uint32_t* src, int count) {
    if (g_tdx_palette == TDX_PAL_GBC_LCD) {
        tdx_copy_row_corrected(dst, src, count);
        return;
    }
    if (g_tdx_palette >= TDX_PAL_PASTEL && g_tdx_palette < TDX_PAL_COUNT) {
        tdx_build_palette_lut(g_tdx_palette);
        const uint32_t* lut = g_tdx_pal_lut[g_tdx_palette];
        if (lut) {
            for (int x = 0; x < count; ++x) {
                const uint32_t p = src[x];
                dst[x] = lut[((p >> 19) & 31u) | (((p >> 11) & 31u) << 5) |
                             (((p >> 3) & 31u) << 10)];
            }
            return;
        }
    }
    memcpy(dst, src, (size_t)count * sizeof(uint32_t));
}

/* Battery save. Upstream only writes the .sav file when the game shuts down
 * cleanly, which rarely happens on Android (apps are killed in the
 * background). Save when the app goes to the background, and every 10 seconds
 * if the save RAM has changed. */
static uint8_t* g_tdx_saved_eram = NULL;
static size_t g_tdx_saved_eram_size = 0;

static void tdx_save_battery(GBContext* ctx, bool force) {
    static uint32_t frames = 0;
    if (!force && ++frames < 600) {
        return;
    }
    frames = 0;
    if (!ctx || !ctx->eram || !ctx->eram_size || ctx->persistence_load_failed) {
        return;
    }
    bool first = false;
    if (g_tdx_saved_eram_size != ctx->eram_size) {
        free(g_tdx_saved_eram);
        g_tdx_saved_eram = (uint8_t*)calloc(1, ctx->eram_size);
        g_tdx_saved_eram_size = g_tdx_saved_eram ? ctx->eram_size : 0;
        if (!g_tdx_saved_eram) {
            return;
        }
        first = true;   /* first check this session: always write once */
    }
    if (!first && memcmp(g_tdx_saved_eram, ctx->eram, ctx->eram_size) == 0) {
        return;
    }
    if (gb_context_save_ram(ctx)) {
        memcpy(g_tdx_saved_eram, ctx->eram, ctx->eram_size);
    }
}

/* Start+Select (gamepad or touch) opens or closes the menu, as in the DKC builds.
 * buttons is the Game Boy button byte, active low: Select 0x04, Start 0x08. */
static void tdx_check_menu_combo(uint8_t buttons) {
    static bool held = false;
    const bool both = (buttons & 0x0Cu) == 0;
    if (both && !held) {
        g_show_menu = !g_show_menu;
    }
    held = both;
}

/* Darkens the lower part of each Game Boy row on screen. */
static void tdx_draw_scanlines(SDL_Renderer* renderer, const SDL_Rect* vp) {
    if (!g_tdx_scanlines || !renderer || !vp || vp->h < GB_SCREEN_HEIGHT * 2) {
        return;
    }
    static SDL_Rect rects[GB_SCREEN_HEIGHT];
    const double row_h = (double)vp->h / (double)GB_SCREEN_HEIGHT;
    int thick = (int)(row_h * 0.4);
    if (thick < 1) thick = 1;
    for (int i = 0; i < GB_SCREEN_HEIGHT; ++i) {
        const int row_end = vp->y + (int)((double)(i + 1) * row_h + 0.5);
        rects[i].x = vp->x;
        rects[i].y = row_end - thick;
        rects[i].w = vp->w;
        rects[i].h = thick;
    }
    SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 90);
    SDL_RenderFillRects(renderer, rects, GB_SCREEN_HEIGHT);
    SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_NONE);
}

/* In portrait, put the game near the top so the touch controls fit below. */
static int tdx_viewport_y(int window_w, int window_h, int viewport_h) {
    const int centred = (window_h - viewport_h) / 2;
#if defined(__ANDROID__)
    if (window_h > window_w) {
        int top = window_h / 14;
        if (top > centred) top = centred;
        if (top < 0) top = 0;
        return top;
    }
#else
    (void)window_w;
#endif
    return centred;
}

/* Fills the generated ROM buffer from the ROM the player picked.
 * The app copies it to its private files folder as rom.gbc. */
extern "C" int tdx_fill_rom(uint8_t* dst, size_t size) {
    std::string path;
#if defined(__ANDROID__)
    const char* dir = SDL_AndroidGetInternalStoragePath();
    path = std::string(dir ? dir : ".") + "/rom.gbc";
#else
    path = "rom.gbc";
#endif
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Tetroid DX",
                                 "ROM not found. Reopen the app and pick your ROM.", NULL);
        return 0;
    }
    fseek(f, 0, SEEK_END);
    const long file_size = ftell(f);
    fseek(f, 0, SEEK_SET);
    size_t got = 0;
    if (file_size == (long)size) {
        got = fread(dst, 1, size, f);
    }
    fclose(f);
    if (got != size) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Tetroid DX",
                                 "The ROM file has the wrong size.", NULL);
        return 0;
    }
    for (size_t i = 0; i < sizeof(g_tdx_rom_patch) / sizeof(g_tdx_rom_patch[0]); ++i) {
        if (g_tdx_rom_patch[i].offset < size) {
            dst[g_tdx_rom_patch[i].offset] = g_tdx_rom_patch[i].value;
        }
    }
    if (size > TDX_OPTIONS_OFFSET) {
        dst[TDX_OPTIONS_OFFSET] = tdx_options_byte();
    }
    return 1;
}

#if defined(__ANDROID__)
/* Lets the touch overlay know when the runtime menu is open. */
extern "C" JNIEXPORT jboolean JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeMenuOpen(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return g_show_menu ? JNI_TRUE : JNI_FALSE;
}

/* Lets the touch D-pad avoid accidental hard drops only while it is on. */
extern "C" JNIEXPORT jboolean JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeHardDropOn(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return g_tdx_harddrop ? JNI_TRUE : JNI_FALSE;
}
#endif
/* ---- end Tetris DX additions ---- */
