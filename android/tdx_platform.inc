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
static int g_tdx_skipceleb = 0;           /* 0 off, 1 always, 2 Start skips */
/* Korobeiniki: Music Type A plays the classic Tetris song, based on
 * "Tetris DX - Music Fix" by Marc Robledo, see korobeiniki_toggle.asm.
 * Saved as tdx.korobeiniki. */
static int g_tdx_korobeiniki = 0;   /* 0 Off, 1 Original, 2 Plus (own first panic song) */
/* Super Rotation System (see srs_toggle.asm). Saved as tdx.srs. */
static bool g_tdx_srs = false;
/* Lock delay (see lockdelay_toggle.asm). Saved as tdx.lock. */
static bool g_tdx_lockdelay = false;
/* Randomizer (see randomizer_toggle.asm): 0 Tetris DX, 1 7-bag, 2 35-bag.
 * Written to bank 0 $3FFF. Saved as tdx.rand. */
static int g_tdx_randomizer = 0;
#define TDX_RANDOMIZER_OFFSET 0x3FFFu
#define TDX_KORO_PLUS_OFFSET 0x3FFDu   /* bank 0: 1 = Korobeiniki (Plus) */
static int g_tdx_ghost = 0;
#define TDX_OPTIONS_OFFSET 0x7FFFu
#define TDX_OPTIONS_OFFSET_B5 0x17FFFu   /* bank-5 copy, read by the music hook */
#define TDX_GHOST_MODE_OFFSET 0x3900u    /* bank 0: ghost mode 1-4, read by the ghost patch */
#define TDX_NEXT_LOCK_OFFSET 0x3920u     /* bank 0: 1 = Select no longer hides the NEXT window */
/* Select hides the game's NEXT window only with vanilla settings (every patch
 * off; palette, music and Announcer do not count). With any patch on it stays
 * shown: hiding breaks the ghost and does not hide our own preview anyway. */
static bool tdx_next_locked(void);
#define TDX_NEXT_LOCKED() tdx_next_locked()

static uint8_t tdx_options_byte(void) {
    uint8_t v = 0;
    if (g_tdx_harddrop) v |= 0x01;
    if (g_tdx_ghost == 1) v |= 0x02;
    if (g_tdx_ghost >= 2) v |= 0x06;
    if (g_tdx_dontpanic) v |= 0x08;
    if (g_tdx_skipceleb == 1) v |= 0x10;
    if (g_tdx_korobeiniki) v |= 0x20;
    if (g_tdx_srs) v |= 0x40;
    if (g_tdx_lockdelay) v |= 0x80;
    return v;
}

/* Called every frame so menu changes take effect at once. */
static void tdx_sync_options(GBContext* ctx) {
    if (ctx && ctx->rom && ctx->rom_size > TDX_OPTIONS_OFFSET) {
        ctx->rom[TDX_OPTIONS_OFFSET] = tdx_options_byte();
    }
    if (ctx && ctx->rom && ctx->rom_size > TDX_OPTIONS_OFFSET_B5) {
        ctx->rom[TDX_OPTIONS_OFFSET_B5] = tdx_options_byte();
    }
    if (ctx && ctx->rom && ctx->rom_size > TDX_GHOST_MODE_OFFSET) {
        ctx->rom[TDX_GHOST_MODE_OFFSET] = (uint8_t)g_tdx_ghost;
        ctx->rom[TDX_NEXT_LOCK_OFFSET] = TDX_NEXT_LOCKED() ? 1 : 0;
    }
    if (ctx && ctx->rom && ctx->rom_size > TDX_RANDOMIZER_OFFSET) {
        ctx->rom[TDX_RANDOMIZER_OFFSET] = (uint8_t)g_tdx_randomizer;
        ctx->rom[TDX_KORO_PLUS_OFFSET] = (g_tdx_korobeiniki == 2) ? 1 : 0;
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
 *   7 Amber Night - black-to-amber gradient, no blue light (night sessions)
 *   8 Graphite   - black-to-dim-grey only (night sessions)
 * Only how the frame is drawn changes; the game itself is untouched. */
enum { TDX_PAL_ORIGINAL = 0, TDX_PAL_GBC_LCD = 1, TDX_PAL_PASTEL = 2, TDX_PAL_SWAMP = 3,
       TDX_PAL_SUNSET = 4, TDX_PAL_GUIDELINE = 5, TDX_PAL_SEGA = 6, TDX_PAL_AMBER = 7, TDX_PAL_GRAPHITE = 8, TDX_PAL_COUNT = 9 };
static int g_tdx_palette = TDX_PAL_GBC_LCD;
static uint32_t* g_tdx_pal_lut[TDX_PAL_COUNT] = {};

/* Guideline and Sega piece colours by CreeperCraftYT: the game's own colour
 * palettes during play (work RAM $C280-$C2FF, 8 background then 8 sprite
 * palettes, copied to the screen by the game). These cannot be a screen
 * filter like the other palettes: the game uses the same orange for the Z
 * piece and for the L/O shading, so a colour-for-colour swap cannot tell them
 * apart. While one is chosen, only the piece colour slots (background 0-3,
 * sprite 0-5) are replaced whenever the rest of the palette RAM shows the
 * play screen, and the game's own values are put back when another palette
 * is chosen. Drawn with the GBC LCD colour correction. */
static const uint8_t kTdxPieceColours[2][128] = {
  { /* Guideline */
    0x1F, 0x02, 0xFF, 0x03, 0xFF, 0x06, 0x00, 0x00, 0x00, 0x75, 0xFF, 0x7F, 0x19, 0x58, 0x00, 0x00,
    0xE4, 0x13, 0xFF, 0x7F, 0x1F, 0x00, 0x00, 0x00, 0xE0, 0x5D, 0x77, 0x7F, 0xE0, 0x7F, 0x00, 0x00,
    0xA0, 0x00, 0xFF, 0x7F, 0x85, 0x3E, 0x42, 0x09, 0xC2, 0x21, 0x77, 0x7F, 0xE1, 0x21, 0xA0, 0x00,
    0x43, 0x45, 0xFF, 0x7F, 0x49, 0x6A, 0x00, 0x00, 0xA0, 0x7E, 0xBF, 0x03, 0xFF, 0x7F, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x03, 0x1F, 0x00, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0x40, 0x7D, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x7F, 0x19, 0x58, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0xE0, 0x7F, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x03, 0x1F, 0x02, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0xE4, 0x13, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x7F, 0xAD, 0x35, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0xAD, 0x35, 0x00, 0x00,
  },
  { /* Sega */
    0x1F, 0x02, 0xFF, 0x03, 0xFF, 0x06, 0x00, 0x00, 0x00, 0x75, 0xFF, 0x7F, 0xE0, 0x7F, 0x00, 0x00,
    0x19, 0x58, 0xFF, 0x7F, 0xE0, 0x03, 0x00, 0x00, 0x10, 0x00, 0x77, 0x7F, 0x1F, 0x00, 0x00, 0x00,
    0xA0, 0x00, 0xFF, 0x7F, 0x85, 0x3E, 0x42, 0x09, 0xC2, 0x21, 0x77, 0x7F, 0xE1, 0x21, 0xA0, 0x00,
    0x43, 0x45, 0xFF, 0x7F, 0x49, 0x6A, 0x00, 0x00, 0xA0, 0x7E, 0xBF, 0x03, 0xFF, 0x7F, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x03, 0xE0, 0x03, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0x40, 0x7D, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x7F, 0xE0, 0x7F, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0x1F, 0x00, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x03, 0x1F, 0x02, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0x19, 0x58, 0x00, 0x00,
    0x00, 0x7C, 0xFF, 0x7F, 0xAD, 0x35, 0x00, 0x00, 0x00, 0x7C, 0xFF, 0x7F, 0xAD, 0x35, 0x00, 0x00,
  },
};
static uint8_t g_tdx_pc_saved[128];
static bool g_tdx_pc_applied = false;

static bool tdx_pc_range(int i) { return i < 32 || (i >= 64 && i < 112); }

/* Writes only the piece colour slots (background palettes 0-3, sprite
 * palettes 0-5) to the game's palette buffer and the screen's palette RAM.
 * Everything else, such as the background colour that the game changes per
 * level and writes straight to the hardware, is left alone. */
static void tdx_pc_write(GBContext* ctx, const uint8_t* src) {
    uint8_t* buf = ctx->wram + 0x280;
    GBPPU* ppu = (GBPPU*)ctx->ppu;
    for (int i = 0; i < 128; ++i) {
        if (!tdx_pc_range(i)) continue;
        buf[i] = src[i];
        if (ppu) {
            if (i < 64) ppu->bg_palette_ram[i] = src[i];
            else ppu->obj_palette_ram[i - 64] = src[i];
        }
    }
}

/* Ghost styles Wireframe and Shadow: the ghost uses sprite palette 7, whose
 * outline colour is white. During play only, that one colour is darkened on
 * screen; when play ends the game's own colour is put back once. Outside play
 * nothing is touched, so menu sprites that share the palette fade normally.
 * The game's palette buffer is never changed. */
static bool tdx_in_play_now(void);
static void tdx_ghost_palette(GBContext* ctx) {
    static bool applied = false;
    if (!ctx || !ctx->wram || !ctx->ppu) return;
    GBPPU* ppu = (GBPPU*)ctx->ppu;
    const uint8_t* buf = ctx->wram + 0x280;
    const bool want = (g_tdx_ghost == 2 || g_tdx_ghost == 4) && tdx_in_play_now();
    if (want) {
        if (g_tdx_ghost == 2) {
            /* Wireframe adapts to the playfield colour (background palette 7, colour 1,
             * which the game changes with the level): black on light, grey on dark. */
            const unsigned bg = ppu->bg_palette_ram[58] | (ppu->bg_palette_ram[59] << 8);
            const unsigned luma = 299u * (bg & 31) + 587u * ((bg >> 5) & 31) + 114u * ((bg >> 10) & 31);
            const bool light_bg = luma >= 16000u;
            ppu->obj_palette_ram[58] = light_bg ? 0x00 : 0xEF;   /* black, or grey 0x3DEF (15/31) */
            ppu->obj_palette_ram[59] = light_bg ? 0x00 : 0x3D;
        } else {
            ppu->obj_palette_ram[58] = 0x94;   /* Shadow: grey 0x5294 (20/31), a soft line that */
            ppu->obj_palette_ram[59] = 0x52;   /* still completes each block's inner square */
        }
        applied = true;
    } else if (applied) {
        ppu->obj_palette_ram[58] = buf[64 + 58];
        ppu->obj_palette_ram[59] = buf[64 + 59];
        applied = false;
    }
}

static void tdx_piece_colours(GBContext* ctx) {
    if (!ctx || !ctx->wram) return;
    uint8_t* buf = ctx->wram + 0x280;
    const int which = g_tdx_palette == TDX_PAL_GUIDELINE ? 0 : (g_tdx_palette == TDX_PAL_SEGA ? 1 : -1);
    /* Play screen: background palette 6 and sprite palettes 14-15 as below. */
    static const uint8_t bg6[8] = {0x43, 0x45, 0xFF, 0x7F, 0x49, 0x6A, 0x00, 0x00};
    static const uint8_t obj7[8] = {0x00, 0x7C, 0xFF, 0x7F, 0xAD, 0x35, 0x00, 0x00};
    const bool play = memcmp(buf + 48, bg6, 8) == 0 && memcmp(buf + 112, obj7, 8) == 0 &&
                      memcmp(buf + 120, obj7, 8) == 0;
    /* Does the buffer hold one of our sets (Guideline or Sega)? If not, it
     * holds the game's own colours, freshly loaded. */
    bool any_ours = false;
    for (int k = 0; k < 2 && !any_ours; ++k) {
        any_ours = true;
        for (int i = 0; i < 128 && any_ours; ++i)
            if (tdx_pc_range(i) && buf[i] != kTdxPieceColours[k][i]) any_ours = false;
    }
    if (which >= 0 && play) {
        if (!any_ours) {
            memcpy(g_tdx_pc_saved, buf, 128);   /* the game's own colours */
            g_tdx_pc_applied = true;
        }
        if (g_tdx_pc_applied) tdx_pc_write(ctx, kTdxPieceColours[which]);
        return;
    }
    if (g_tdx_pc_applied && which < 0 && play && any_ours) {
        /* Another palette was chosen during play: put the game's colours back. */
        tdx_pc_write(ctx, g_tdx_pc_saved);
    }
    g_tdx_pc_applied = false;
}

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
    if (mode < TDX_PAL_PASTEL || mode >= TDX_PAL_COUNT || mode == TDX_PAL_GUIDELINE ||
        mode == TDX_PAL_SEGA || g_tdx_pal_lut[mode]) {
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
    static const uint32_t amber[4] = {0x080400, 0x3A1C00, 0x9A5600, 0xE2A24C};
    static const uint32_t graphite[4] = {0x040404, 0x1C1C1C, 0x4C4C4C, 0x9A9A9A};
    const uint32_t* stops = mode == TDX_PAL_SWAMP ? swamp : (mode == TDX_PAL_AMBER ? amber :
                            (mode == TDX_PAL_GRAPHITE ? graphite : sunset));
    for (uint32_t i = 0; i < 32768u; ++i) {
        const double r = (double)(i & 31u) / 31.0;
        const double g = (double)((i >> 5) & 31u) / 31.0;
        const double b = (double)((i >> 10) & 31u) / 31.0;
        const double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        if (mode == TDX_PAL_PASTEL) {
            lut[i] = tdx_pastel(r, g, b);
        } else {
            lut[i] = tdx_gradient(stops, lum);
        }
    }
    g_tdx_pal_lut[mode] = lut;
}

/* Copies one screen row using the selected palette. */
static void tdx_copy_row(uint32_t* dst, const uint32_t* src, int count) {
    if (g_tdx_palette == TDX_PAL_GBC_LCD || g_tdx_palette == TDX_PAL_GUIDELINE ||
        g_tdx_palette == TDX_PAL_SEGA) {
        tdx_copy_row_corrected(dst, src, count);
        return;
    }
    if (g_tdx_palette >= TDX_PAL_PASTEL && g_tdx_palette != TDX_PAL_GUIDELINE &&
        g_tdx_palette != TDX_PAL_SEGA && g_tdx_palette < TDX_PAL_COUNT) {
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

/* Select + D-pad Down (gamepad or touch) opens or closes the menu. Start+Select is left
 * to the game, where it quits to the title screen.
 * buttons is the Game Boy button byte, active low: Select 0x04. dpad is active low: Down 0x08. */
static void tdx_check_menu_combo(uint8_t buttons, uint8_t dpad) {
    static bool held = false;
    const bool both = (buttons & 0x04u) == 0 && (dpad & 0x08u) == 0;
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
    if (size > TDX_OPTIONS_OFFSET_B5) {
        dst[TDX_OPTIONS_OFFSET_B5] = tdx_options_byte();
    }
    if (size > TDX_GHOST_MODE_OFFSET) {
        dst[TDX_GHOST_MODE_OFFSET] = (uint8_t)g_tdx_ghost;
        dst[TDX_NEXT_LOCK_OFFSET] = TDX_NEXT_LOCKED() ? 1 : 0;
    }
    if (size > TDX_RANDOMIZER_OFFSET) {
        dst[TDX_RANDOMIZER_OFFSET] = (uint8_t)g_tdx_randomizer;
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

/* ===================== Randomizer queue and Hold (Tetroid DX) =====================
 *
 * Randomizer: with 7-Bag or 35-Bag the pieces are chosen here, so the app can
 * show several upcoming pieces. The game's hook (randomizer_toggle.asm) counts
 * each piece it takes in WRAM $CF80 and reads the piece from bank 0 $3FFE,
 * which this code keeps set to the head of the queue. Tetris DX mode runs the
 * game's own code; then only the game's next piece is known.
 *
 * Hold: while Hold is on, the touch HOLD button (above Select and Start) and
 * L1 / R1 on a controller hold the piece; Select keeps its normal use. The swap is done
 * between frames, only while a piece is falling (game state $AC0F = 3):
 *   - empty hold box: the piece goes in the box and the game is sent to its
 *     "spawn the next piece" state ($AC0F = 5, no lines), so the queue moves on;
 *   - otherwise the held piece replaces the falling one at the spawn position,
 *     if it fits there.
 * Once per piece, as in modern Tetris. Game RAM: $AF80 X, $AF81 Y, $AF82
 * rotation, $AF84 next, $AF85 current, board at $AD00 (empty cell $FB).
 */
#define TDX_QUEUE_PIECE_OFFSET 0x3FFEu
static bool g_tdx_hold = false;            /* saved as tdx.hold */
static int g_tdx_preview = 4;              /* queue layout 0 off, 1-5; tdx.preview */
static bool tdx_next_locked(void) {
    return g_tdx_harddrop || g_tdx_ghost != 0 || g_tdx_dontpanic || g_tdx_skipceleb != 0 || g_tdx_srs ||
           g_tdx_lockdelay || g_tdx_randomizer != 0 || g_tdx_hold || g_tdx_preview != 0;
}
static int g_tdx_hold_piece = -1;
static bool g_tdx_hold_used = false;
static std::atomic<bool> g_tdx_hold_req{false};
static bool g_tdx_hold_spawning = false;
static uint8_t g_tdx_q[8];
static int g_tdx_qn = 0;
static int g_tdx_q_mode = -1;
static uint8_t g_tdx_q_counter = 0;
static uint32_t g_tdx_rng = 0;
static uint8_t g_tdx_bag = 0;              /* 7-Bag: bits of pieces left */
static uint8_t g_tdx_hist[4];
static uint8_t g_tdx_pool[35];
static uint8_t g_tdx_order[7];
static int g_tdx_order_n = 0;
static std::atomic<bool> g_tdx_in_play{false};
static bool tdx_in_play_now(void) { return g_tdx_in_play.load(std::memory_order_relaxed); }

static uint32_t tdx_rand(void) {
    /* xorshift32 */
    uint32_t x = g_tdx_rng ? g_tdx_rng : 0x9E3779B9u;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    g_tdx_rng = x;
    return x;
}

static int tdx_gen_piece(void) {
    if (g_tdx_q_mode == 1) {            /* 7-Bag */
        if ((g_tdx_bag & 0x7F) == 0) g_tdx_bag = 0x7F;
        int p;
        do { p = (int)(tdx_rand() % 7u); } while (!(g_tdx_bag & (1u << p)));
        g_tdx_bag &= (uint8_t)~(1u << p);
        return p;
    }
    /* 35-Bag, modelled on TGM3: 35-piece pool, 4-piece history, up to 6 rolls,
     * the longest-unseen piece goes back into the pool. */
    int idx = 0, piece = 0;
    for (int roll = 0; roll < 6; ++roll) {
        idx = (int)(tdx_rand() % 35u);
        piece = g_tdx_pool[idx];
        bool in_hist = false;
        for (int k = 0; k < 4; ++k) if (g_tdx_hist[k] == piece) in_hist = true;
        if (!in_hist || roll == 5) break;
        if (g_tdx_order_n > 0) g_tdx_pool[idx] = g_tdx_order[0];
    }
    int w = 0;
    for (int k = 0; k < g_tdx_order_n; ++k) if (g_tdx_order[k] != piece) g_tdx_order[w++] = g_tdx_order[k];
    g_tdx_order_n = w;
    g_tdx_order[g_tdx_order_n++] = (uint8_t)piece;
    g_tdx_pool[idx] = g_tdx_order[0];
    g_tdx_hist[0] = g_tdx_hist[1]; g_tdx_hist[1] = g_tdx_hist[2]; g_tdx_hist[2] = g_tdx_hist[3];
    g_tdx_hist[3] = (uint8_t)piece;
    return piece;
}

static void tdx_queue_reset(int mode) {
    g_tdx_q_mode = mode;
    g_tdx_rng = (uint32_t)SDL_GetPerformanceCounter() ^ 0xA5A5F00Du;
    g_tdx_bag = 0;
    g_tdx_hist[0] = 3; g_tdx_hist[1] = 2; g_tdx_hist[2] = 3; g_tdx_hist[3] = 2;  /* S Z S Z */
    for (int i = 0; i < 35; ++i) g_tdx_pool[i] = (uint8_t)(i % 7);
    g_tdx_order_n = 0;
    g_tdx_qn = 0;
    if (mode == 0) return;
    while (g_tdx_qn < 6) g_tdx_q[g_tdx_qn++] = (uint8_t)tdx_gen_piece();
}

static void tdx_queue_pop(void) {
    for (int i = 1; i < g_tdx_qn; ++i) g_tdx_q[i - 1] = g_tdx_q[i];
    g_tdx_qn--;
    g_tdx_q[g_tdx_qn++] = (uint8_t)tdx_gen_piece();
}

/* Spawn position the game uses for piece t (DX rules, or SRS when on). */
static void tdx_spawn_pos(int t, int* x, int* y, int* rot) {
    *x = 5; *y = 1; *rot = 2;
    if (g_tdx_srs) {
        if (t == 0 || t == 6) *y = 0;
    } else {
        if (t != 2 && t != 3) *y = 0;
        if (t != 0 && t != 6) *rot = 0;
    }
}

/* The game's NEXT window: rebuild its 8 tile numbers and 8 attributes (the
 * buffer at $AFA0 that bank 1 $4B67 fills) for piece t and ask for a redraw. */
static void tdx_redraw_next(GBContext* ctx, int t) {
    static const uint8_t srs_tiles[7][8] = {
        {0xFF,0xFF,0xFF,0xFF, 0xB0,0xB1,0xB1,0xB2}, {0xFF,0x98,0xFF,0xFF, 0x98,0x98,0x98,0xFF},
        {0xA0,0xA0,0xFF,0xFF, 0xFF,0xA0,0xA0,0xFF}, {0xFF,0xA8,0xA8,0xFF, 0xA8,0xA8,0xFF,0xFF},
        {0x90,0xFF,0xFF,0xFF, 0x90,0x90,0x90,0xFF}, {0xFF,0xFF,0x88,0xFF, 0x88,0x88,0x88,0xFF},
        {0xFF,0x80,0x80,0xFF, 0xFF,0x80,0x80,0xFF}};
    static const uint8_t srs_attrs[7][8] = {
        {6,6,6,6, 3,3,3,3}, {6,1,6,6, 1,1,1,6}, {2,2,6,6, 6,2,2,6}, {6,2,2,6, 2,2,6,6},
        {1,6,6,6, 1,1,1,6}, {6,6,0,6, 0,0,0,6}, {6,0,0,6, 6,0,0,6}};
    if (!ctx->eram || t < 0 || t > 6) return;
    uint8_t* e = ctx->eram;
    for (int i = 0; i < 8; ++i) {
        e[0xFA0 + i] = g_tdx_srs ? srs_tiles[t][i] : ctx->rom[0x5F68 + t * 8 + i];
        e[0xFA8 + i] = g_tdx_srs ? srs_attrs[t][i] : ctx->rom[0x5FA0 + t * 8 + i];
    }
    e[0xF9C] = 1;
}

static void tdx_queue_frame(GBContext* ctx) {
    if (!ctx || !ctx->wram || !ctx->rom || ctx->rom_size <= TDX_QUEUE_PIECE_OFFSET) return;
    const int mode = g_tdx_randomizer;
    const uint8_t counter = ctx->wram[0xF80];
    static uint8_t prev_state = 0;
    const uint8_t st = ctx->eram ? ctx->eram[0xC0F] : 0;
    if (mode != g_tdx_q_mode) {
        tdx_queue_reset(mode);
        g_tdx_q_counter = counter;
    }
    /* A new game has just set up its first piece and its next piece with the
     * game's own randomizer ($AC0F goes to $12). Start a fresh bag and use its
     * first two pieces instead, so the bag is right from the first piece. */
    if (mode != 0 && ctx->eram && st == 0x12 && prev_state != 0x12) {
        tdx_queue_reset(mode);
        const int cur = g_tdx_q[0];
        tdx_queue_pop();
        const int next = g_tdx_q[0];
        tdx_queue_pop();
        int x, y, rot;
        tdx_spawn_pos(cur, &x, &y, &rot);
        uint8_t* e = ctx->eram;
        e[0xF85] = (uint8_t)cur;
        e[0xF84] = (uint8_t)next;
        e[0xF80] = (uint8_t)x;
        e[0xF81] = (uint8_t)y;
        e[0xF82] = (uint8_t)rot;
        tdx_redraw_next(ctx, next);
        g_tdx_q_counter = counter;
    }
    prev_state = st;
    if (mode != 0) {
        uint8_t taken = (uint8_t)(counter - g_tdx_q_counter);
        if (taken > 6) taken = 6;
        for (int t = 0; t < taken; ++t) tdx_queue_pop();
        g_tdx_q_counter = counter;
        ctx->rom[TDX_QUEUE_PIECE_OFFSET] = g_tdx_q[0];
    }
}

static bool tdx_piece_fits(GBContext* ctx, int t, int x, int y, int rot) {
    const uint8_t* e = ctx->eram;
    const uint8_t* rom = ctx->rom;
    if (!e || !rom || t < 0 || t > 6) return false;
    const unsigned ptr = rom[0x6202 + 2 * t] | (rom[0x6203 + 2 * t] << 8);
    const unsigned shape = ptr + 16u * (unsigned)rot;
    const unsigned base = 0xD00u + (unsigned)(((y & 0x0F) << 4) | x) + (unsigned)((y >> 4) << 8);
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            if (rom[shape + r * 4 + c] && e[(base + r * 16 + c) & 0x1FFF] != 0xFB) return false;
    return true;
}

static void tdx_hold_frame(GBContext* ctx) {
    if (!ctx || !ctx->eram) return;
    uint8_t* e = ctx->eram;
    static uint8_t prev = 0;
    const uint8_t st = e[0xC0F];
    /* In play: the play screen's palettes are up (see tdx_piece_colours). */
    const uint8_t* pb = ctx->wram ? ctx->wram + 0x280 : NULL;
    static const uint8_t bg6[8] = {0x43, 0x45, 0xFF, 0x7F, 0x49, 0x6A, 0x00, 0x00};
    const bool play_screen = pb && memcmp(pb + 48, bg6, 8) == 0;
    g_tdx_in_play.store(play_screen && st >= 3 && st <= 8, std::memory_order_relaxed);
    if (st == 0x12 || st < 3) {
        g_tdx_hold_piece = -1;               /* a new game starts with an empty box */
        g_tdx_hold_used = false;
    }
    if (st == 3 && prev != 3) {              /* a new piece came in */
        if (!g_tdx_hold_spawning) g_tdx_hold_used = false;
        g_tdx_hold_spawning = false;
    }
    prev = st;
    if (!g_tdx_hold_req.exchange(false)) return;
    if (!g_tdx_hold || st != 3 || g_tdx_hold_used || e[0xC0A] == 4) return;
    if (ctx->wram && ctx->wram[0x119]) return;     /* game over in progress */
    const int cur = e[0xF85];
    if (cur > 6) return;
    if (g_tdx_hold_piece < 0) {
        g_tdx_hold_piece = cur;
        g_tdx_hold_used = true;
        g_tdx_hold_spawning = true;
        e[0xC62] = 0;
        e[0xC0F] = 5;                        /* spawn the next piece */
        prev = 5;                            /* so the new piece's arrival (5 -> 3, even within one frame) is seen */
        return;
    }
    const int t = g_tdx_hold_piece;
    int x, y, rot;
    tdx_spawn_pos(t, &x, &y, &rot);
    if (!tdx_piece_fits(ctx, t, x, y, rot)) return;
    e[0xF85] = (uint8_t)t;
    e[0xF80] = (uint8_t)x;
    e[0xF81] = (uint8_t)y;
    e[0xF82] = (uint8_t)rot;
    e[0xC57] = 0x80;
    e[0xF86] = 0x20;
    e[0xF88] = 0;
    g_tdx_hold_piece = cur;
    g_tdx_hold_used = true;
}

/* Screen colour of a piece (for the hold box and queue): the colour of its
 * blocks in the game's background palettes, through the chosen palette. */
static uint32_t tdx_piece_argb(GBContext* ctx, int t) {
    static const uint8_t slot[7][2] = {{3, 2}, {1, 2}, {2, 2}, {2, 0}, {1, 0}, {0, 0}, {0, 1}};
    if (!ctx || !ctx->ppu || t < 0 || t > 6) return 0xFF9E9E9Eu;
    const GBPPU* ppu = (const GBPPU*)ctx->ppu;
    const int o = slot[t][0] * 8 + slot[t][1] * 2;
    const uint32_t c = ppu->bg_palette_ram[o] | (ppu->bg_palette_ram[o + 1] << 8);
    const uint32_t r = c & 31u, g = (c >> 5) & 31u, b = (c >> 10) & 31u;
    uint32_t px = 0xFF000000u | ((r * 255u / 31u) << 16) | ((g * 255u / 31u) << 8) | (b * 255u / 31u);
    uint32_t out = px;
    tdx_copy_row(&out, &px, 1);
    return out;
}

/* Piece pictures for the hold box and queue, made from the game's own block
 * tiles in video RAM and its current background palettes (as in its NEXT
 * window), passed through the chosen palette. Each piece is a 32 x 16 pixel
 * ARGB picture: 4 x 2 tiles in its spawn orientation, transparent where empty.
 * Nothing is copied from the ROM; the tiles are read live while a game runs. */
static const char* const kTdxShapeDX[7] = {
    "....####", "###..#..", "##...##.", ".##.##..", "###...#.", "###.#...", ".##..##."};
static const char* const kTdxShapeSRS[7] = {
    "....####", ".#..###.", "##...##.", ".##.##..", "#...###.", "..#.###.", ".##..##."};
static const uint8_t kTdxPieceTile[7] = {0xB1, 0x98, 0xA0, 0xA8, 0x90, 0x88, 0x80};
static const uint8_t kTdxPiecePal[7] = {3, 1, 2, 2, 1, 0, 0};

static bool tdx_piece_image(GBContext* ctx, int t, uint32_t* out /* 32*16 */) {
    if (!ctx || !ctx->vram || !ctx->ppu || t < 0 || t > 6) return false;
    const GBPPU* ppu = (const GBPPU*)ctx->ppu;
    const char* shape = (g_tdx_srs ? kTdxShapeSRS : kTdxShapeDX)[t];
    uint32_t pal[4];
    for (int k = 0; k < 4; ++k) {
        const int o = kTdxPiecePal[t] * 8 + k * 2;
        const uint32_t c = ppu->bg_palette_ram[o] | (ppu->bg_palette_ram[o + 1] << 8);
        const uint32_t r = c & 31u, g = (c >> 5) & 31u, b = (c >> 10) & 31u;
        uint32_t px = 0xFF000000u | ((r * 255u / 31u) << 16) | ((g * 255u / 31u) << 8) | (b * 255u / 31u);
        tdx_copy_row(&pal[k], &px, 1);
        pal[k] |= 0xFF000000u;
    }
    bool any = false;
    for (int i = 0; i < 32 * 16; ++i) out[i] = 0;
    for (int cell = 0; cell < 8; ++cell) {
        if (shape[cell] != '#') continue;
        const int cx = cell % 4, cy = cell / 4;
        uint8_t tile = kTdxPieceTile[t];
        if (t == 0) tile = cx == 0 ? 0xB0 : (cx == 3 ? 0xB2 : 0xB1);   /* I: end caps */
        const uint8_t* td = ctx->vram + (0x0000u + tile * 16u);         /* $8000 + id*16 */
        for (int y = 0; y < 8; ++y) {
            const uint8_t lo = td[y * 2], hi = td[y * 2 + 1];
            for (int x = 0; x < 8; ++x) {
                const int bit = 7 - x;
                const int ci = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                out[(cy * 8 + y) * 32 + cx * 8 + x] = pal[ci];
                if (lo | hi) any = true;
            }
        }
    }
    return any;
}

/* Skip Celebrations, "Start skips": a celebration is game state $10
 * (Marathon) or $11 (the other modes) in $C0A0. Pressing Start during one
 * does what the game does when there is nothing to celebrate: next state 8
 * ($C0A1) through the fade state 2 ($C0A0). The game's held buttons are in
 * HRAM $FF82 (Start = bit 3). */
static void tdx_celebration_frame(GBContext* ctx) {
    static bool prev_start = true;
    if (!ctx || !ctx->wram || !ctx->hram) return;
    const bool start = (ctx->hram[0x02] & 0x08) != 0;
    const uint8_t st = ctx->wram[0x0A0];
    if (g_tdx_skipceleb == 2 && (st == 0x10 || st == 0x11) && start && !prev_start) {
        if (st == 0x10) {
            /* The Marathon celebration (bank $0B) ends by clearing its step
             * counter $C0A8 and its fade timers $FF90/$FF91; do the same so
             * the next one starts from its first step. */
            ctx->wram[0x0A8] = 0;
            ctx->hram[0x10] = 0;
            ctx->hram[0x11] = 0;
        }
        /* The other celebration (bank $1C) ends exactly like this. */
        ctx->wram[0x0A1] = 0x08;
        ctx->wram[0x0A0] = 0x02;
    }
    prev_start = start;
}

/* Announcer: a 4-line clear (a Tetris) and a Tetris straight after another
 * (back-to-back). The game stores the lines cleared by the last drop in $AC62
 * and enters its line-clear state ($AC0F = 6) right after; both are read here
 * between frames, nothing in the ROM changes. The app plays the voice clip and
 * flashes the Hold and Preview boxes when the event counter changes. */
static bool g_tdx_voice = true;                   /* saved as tdx.voice; on by default, it never changes the game */
static std::atomic<int> g_tdx_tetris_event{0};    /* counts events */
static std::atomic<int> g_tdx_tetris_kind{0};     /* 1 Tetris, 2 back-to-back */
static bool tdx_board_empty(GBContext* ctx) {
    /* The playfield is 10 cells wide from $AD02 and 19 rows deep, 16 bytes per row. */
    for (int row = 0; row < 19; ++row)
        for (int col = 0; col < 10; ++col)
            if (ctx->eram[0xD02 + row * 16 + col] != 0xFB) return false;
    return true;
}
static std::atomic<int> g_tdx_milestone{0};
static int g_tdx_later_kind[4] = {0, 0, 0, 0};
static int g_tdx_later_wait[4] = {0, 0, 0, 0};
static void tdx_voice_event_later(int kind, int frames) {    /* so two events never share a frame */
    for (int i = 0; i < 4; ++i)
        if (!g_tdx_later_kind[i]) { g_tdx_later_kind[i] = kind; g_tdx_later_wait[i] = frames; return; }
}
static int g_tdx_ev_cool = 0;    /* frames left before the next event may go out (the app polls every 50 ms) */
static void tdx_voice_event(int kind) {
    if (g_tdx_ev_cool > 0) { tdx_voice_event_later(kind, g_tdx_ev_cool + 1); return; }   /* failsafe: a second event waits its turn */
    g_tdx_tetris_kind.store(kind, std::memory_order_relaxed);
    g_tdx_tetris_event.fetch_add(1, std::memory_order_relaxed);
    g_tdx_ev_cool = 8;
}
/* Also: a perfect clear (the board is empty once the clear has settled, the
 * game is back to a falling piece, state 3 or 5) and reaching level 30 ($AF97
 * is the level in BCD). Kinds: 1 Tetris, 2 back-to-back, 3 perfect clear,
 * 4 max level. */
/* T-Spin (SRS only, regular T-Spins only). While a piece falls (state 3) the
 * last move is tracked from $AF80 X, $AF81 Y, $AF82 rotation, $AF85 piece: a
 * change of rotation is a rotation, a change of X or Y is a move. When the T
 * locks, it is a T-Spin if the last move was a rotation, both corners on the
 * side the T points to are filled and at least one corner behind it is (walls
 * count as filled). The T's centre and nub are found from its shape in the ROM. */
static bool tdx_tspin_corners(GBContext* ctx, int t, int x, int y, int rot) {
    const uint8_t* rom = ctx->rom;
    const uint8_t* e = ctx->eram;
    if (!rom || !e || t < 0 || t > 6) return false;
    const unsigned ptr = rom[0x6202 + 2 * t] | (rom[0x6203 + 2 * t] << 8);
    const unsigned shape = ptr + 16u * (unsigned)(rot & 3);
    int cr = -1, cc = -1, dr = 0, dc = 0, cells = 0;
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c)
            if (rom[shape + r * 4 + c]) cells++;
    if (cells != 4) return false;
    auto has = [&](int r, int c) { return r >= 0 && r < 4 && c >= 0 && c < 4 && rom[shape + r * 4 + c] != 0; };
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c) {
            if (!has(r, c)) continue;
            int n = has(r - 1, c) + has(r + 1, c) + has(r, c - 1) + has(r, c + 1);
            if (n == 3) { cr = r; cc = c; }
        }
    if (cr < 0) return false;                      /* not a T */
    static const int D[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int k = 0; k < 4; ++k) {                  /* the nub: the neighbour with no other neighbour */
        int nr = cr + D[k][0], nc = cc + D[k][1];
        if (!has(nr, nc)) continue;
        int n = has(nr - 1, nc) + has(nr + 1, nc) + has(nr, nc - 1) + has(nr, nc + 1);
        if (n == 1) { dr = D[k][0]; dc = D[k][1]; }
    }
    if (!dr && !dc) return false;
    const int pr = dc ? 1 : 0, pc = dr ? 1 : 0;    /* sideways direction */
    const unsigned base = 0xD00u + (unsigned)y * 16u + (unsigned)x;
    auto filled = [&](int r, int c) { return e[(base + (unsigned)(r * 16 + c)) & 0x1FFF] != 0xFB; };
    int front = 0, back = 0;
    for (int sgn = -1; sgn <= 1; sgn += 2) {
        front += filled(cr + dr + sgn * pr, cc + dc + sgn * pc);
        back += filled(cr - dr + sgn * pr, cc - dc + sgn * pc);
    }
    return front == 2 && back >= 1;
}
/* Kinds: 1 Tetris, 2 back-to-back, 3 perfect clear, 4 max level,
 * 5/6/7 T-Spin Single/Double/Triple, 8 T-Spin without a line clear,
 * 9 maximum score (9,999,999), 10 maximum speed (level 20), 11 game over, 12 praise (Congratulations or
 * Excellent), 13 praise (Well done), 14 Vanilla, 15 Guideline (the GUIDELINE text), 16 Guideline with
 * its palette (the text sparkles); 11-16 have no flash.
 * Easter eggs: 17 Konami code, 18 one million points, 19 and 20 lines, 21 On fire (3 Tetrises in a
 * row), 22 Lucky seven (7 in a row), 23 a game starts (the app checks the date). */
static void tdx_tetris_frame(GBContext* ctx) {
    if (g_tdx_ev_cool > 0) --g_tdx_ev_cool;
    static uint8_t prev_state = 0, prev_level = 0;
    static bool streak = false, pending_pc = false, prev_maxed = false;
    static uint8_t prev_ws = 0, prev_held = 0, prev_hund = 0;
    static bool prev_mil = false;
    static int run = 0, konami = 0, start_quiet = 0;
    static bool prev_play = false;
    static int quiet_frames = 0;
    static int pt = -1, px = 0, py = 0, prot = 0;
    static int hist[5] = {0, 0, 0, 0, 0};   /* the last five line-clear sizes, for Crescendo, Waterfall and Korean Stacking */
    static bool last_rot = false, cand = false, locked_tspin = false;
    if (!ctx || !ctx->eram) return;
    const uint8_t st = ctx->eram[0xC0F];
    const uint8_t level = ctx->eram[0xF97];
    const bool play = g_tdx_in_play.load(std::memory_order_relaxed);
    if (!play) { streak = false; pending_pc = false; cand = false; run = 0; for (int k = 0; k < 5; ++k) hist[k] = 0; }
    if (st == 0x03) {
        const int t = ctx->eram[0xF85], x = ctx->eram[0xF80], y = ctx->eram[0xF81], rot = ctx->eram[0xF82];
        if (prev_state != 0x03 || t != pt) last_rot = false;
        else if (rot != prot) last_rot = true;
        else if (x != px || y != py) last_rot = false;
        pt = t; px = x; py = y; prot = rot;
        cand = last_rot && tdx_tspin_corners(ctx, t, x, y, rot);
    }
    if (prev_state == 0x03 && st == 0x04) {        /* locked: re-check at the final position (a hard drop may have moved it) */
        const int t = ctx->eram[0xF85], x = ctx->eram[0xF80], y = ctx->eram[0xF81], rot = ctx->eram[0xF82];
        cand = last_rot && t == pt && x == px && y == py && rot == prot
               && tdx_tspin_corners(ctx, t, x, y, rot);
    }
    if (prev_state == 0x04 && st != 0x04) {        /* the lock is resolved: a clear (6) or the next piece */
        if (st == 0x06) {
            const uint8_t lines = ctx->eram[0xC62];
            locked_tspin = cand && lines >= 1 && lines <= 3;
            if (locked_tspin && g_tdx_voice) tdx_voice_event(4 + lines);
        } else if (cand && (st == 0x05 || st == 0x03) && g_tdx_voice) {
            tdx_voice_event(8);
        }
        cand = false;
    }
    if (st == 0x06 && prev_state != 0x06) {
        const uint8_t lines = ctx->eram[0xC62];
        pending_pc = lines >= 1 && lines <= 4;
        if (lines >= 1 && lines <= 4) {
            for (int k = 0; k < 4; ++k) hist[k] = hist[k + 1];
            hist[4] = lines;
            if (g_tdx_voice) {
                bool up = hist[1] == 1 && hist[2] == 2 && hist[3] == 3 && hist[4] == 4;
                bool down = hist[1] == 4 && hist[2] == 3 && hist[3] == 2 && hist[4] == 1;
                bool five = hist[0] == 1 && hist[1] == 1 && hist[2] == 1 && hist[3] == 1 && hist[4] == 1;
                /* Crescendo ends on a Tetris, so it waits for the Tetris voice. */
                if (up) { tdx_voice_event_later(26, 90); for (int k = 0; k < 5; ++k) hist[k] = 0; }
                else if (down) { tdx_voice_event_later(27, 40); for (int k = 0; k < 5; ++k) hist[k] = 0; }
                else if (five) { tdx_voice_event_later(28, 40); for (int k = 0; k < 5; ++k) hist[k] = 0; }
            }
        }
        if (lines == 4) {
            if (g_tdx_voice) tdx_voice_event(streak ? 2 : 1);   /* On fire / Lucky 7 follow 1.2 s later: Tetris voice first, then theirs */
            streak = true;
            ++run;
            if (g_tdx_voice && run == 3) tdx_voice_event_later(21, 70);   /* On fire */
            if (g_tdx_voice && run == 7) tdx_voice_event_later(22, 70);   /* Lucky seven */
        } else if (lines >= 1 && lines <= 3) {
            if (g_tdx_voice && (lines == 2 || lines == 3)) {
                if (hist[2] == lines && hist[3] == lines && hist[4] == lines) { tdx_voice_event(lines == 2 ? 31 : 32); hist[2] = hist[3] = hist[4] = 0; }   /* Triple Doubles / Triple Triples: heartbeat glow */
                else tdx_voice_event(lines == 2 ? 29 : 30);
            }
            streak = locked_tspin;                 /* a T-Spin keeps a back-to-back streak alive */
            run = 0;
        }
    } else if (pending_pc && (st == 0x03 || st == 0x05)) {
        pending_pc = false;
        if (g_tdx_voice && tdx_board_empty(ctx)) tdx_voice_event(3);
    }
    if (play && g_tdx_voice && level == 0x30 && prev_level != 0x30 && prev_level != 0) tdx_voice_event(4);
    /* Easter eggs. Queued events go out a little later so they never share a frame. */
    for (int i = 0; i < 4; ++i)
        if (g_tdx_later_kind[i] && --g_tdx_later_wait[i] <= 0 && g_tdx_ev_cool == 0) {
            tdx_voice_event(g_tdx_later_kind[i]);
            g_tdx_later_kind[i] = 0;
        }
    /* A new game starts (a zero score at the start of play): kind 23 lets the app check the date. */
    if (start_quiet > 0) --start_quiet;
    if (play && !prev_play && start_quiet == 0 && g_tdx_voice &&
        ctx->eram[0xF89] == 0 && ctx->eram[0xF8A] == 0 && ctx->eram[0xF8B] == 0 && ctx->eram[0xF8C] == 0) {
        tdx_voice_event_later(23, 8);
        start_quiet = 1200;
    }
    /* Konami code on a menu or title screen: Up Up Down Down Left Right Left Right B A Start. */
    if (ctx->hram) {
        const uint8_t held = ctx->hram[0x02], pressed = (uint8_t)(held & ~prev_held);
        prev_held = held;
        static const uint8_t code[11] = {0x40, 0x40, 0x80, 0x80, 0x20, 0x10, 0x20, 0x10, 0x02, 0x01, 0x08};
        if (pressed && !play) {
            if (pressed == code[konami]) ++konami;
            else konami = (pressed == code[0]) ? 1 : 0;
            if (konami == 11) { if (g_tdx_voice) tdx_voice_event(17); konami = 0; }
        }
    }
    /* One million points ($AF8C is the two top digits of the score). */
    const bool mil = ctx->eram[0xF8C] != 0;
    if (mil && !prev_mil && play && g_tdx_voice) tdx_voice_event_later(18, 4);
    prev_mil = mil;
    /* 100 and 200 lines in the modes that count up ($AF8E is the hundreds digit). */
    {   /* Line milestones: every 100 to 1,000, every 1,000 to 5,000, then every 5,000 (3 BCD bytes at $AF8D-$AF8F). */
        static int prev_lines = 0;
        auto bcd = [](uint8_t b) { return (b >> 4) * 10 + (b & 15); };
        const int lines = bcd(ctx->eram[0xF8D]) + 100 * bcd(ctx->eram[0xF8E]) + 10000 * bcd(ctx->eram[0xF8F]);
        if (play && g_tdx_voice && ctx->eram[0xC0A] != 2 && lines > prev_lines && lines - prev_lines <= 4) {
            for (int m = prev_lines + 1; m <= lines; ++m) {
                const bool ms = m >= 100 && ((m <= 1000 && m % 100 == 0) || (m <= 5000 && m % 1000 == 0) || m % 5000 == 0);
                if (ms) { g_tdx_milestone.store(m, std::memory_order_relaxed); tdx_voice_event_later(25, 20); }
            }
        }
        /* Gunslinger: 40 Lines (mode 2, the line counter counts down) cleared in under 2 minutes by the game's own clock
         * ($AF93 seconds, $AF94 minutes, both BCD; the clock stops when the game pauses). */
        if (play && g_tdx_voice && ctx->eram[0xC0A] == 2 && prev_lines > 0 && lines == 0 && ctx->eram[0xF94] < 2) tdx_voice_event(33);
        prev_lines = lines;
    }
    /* "Vanilla": a new game starts (play begins with a zero score) while every patch that
     * changes the game is off and the palette is Original or GBC LCD. Korobeiniki (music) and the Announcer do not count. At most
     * once every 20 s, so pausing before the first points does not repeat it. */
    if (quiet_frames > 0) --quiet_frames;
    if (play && !prev_play && quiet_frames == 0 && g_tdx_voice && !g_tdx_harddrop && g_tdx_ghost == 0 &&
        !g_tdx_dontpanic && g_tdx_skipceleb == 0 && !g_tdx_srs && !g_tdx_lockdelay &&
        (g_tdx_palette == TDX_PAL_ORIGINAL || g_tdx_palette == TDX_PAL_GBC_LCD) &&
        g_tdx_randomizer == 0 && !g_tdx_hold && g_tdx_preview == 0 &&
        ctx->eram[0xF89] == 0 && ctx->eram[0xF8A] == 0 && ctx->eram[0xF8B] == 0 && ctx->eram[0xF8C] == 0) {
        tdx_voice_event(14);
        quiet_frames = 1200;
    }
    /* "Guideline": a new game starts with the modern guideline set on (SRS, Hold, 7-Bag, Hard
     * Drop, Ghost Piece, Lock Delay, Preview). With the Guideline palette also on, the app
     * adds the sparkling GUIDELINE text (kind 16 instead of 15). */
    if (play && !prev_play && quiet_frames == 0 && g_tdx_voice && g_tdx_srs && g_tdx_hold &&
        g_tdx_randomizer == 1 && g_tdx_harddrop && g_tdx_ghost != 0 && g_tdx_lockdelay &&
        g_tdx_preview != 0 &&
        ctx->eram[0xF89] == 0 && ctx->eram[0xF8A] == 0 && ctx->eram[0xF8B] == 0 && ctx->eram[0xF8C] == 0) {
        tdx_voice_event(g_tdx_palette == TDX_PAL_GUIDELINE ? 16 : 15);
        quiet_frames = 1200;
    }
    /* Quit (Start + Select) right after a start message: it was suppressed, so give the next game its message. */
    {
        static int since_start = 9999;
        if (quiet_frames > 1190 || start_quiet > 1190) since_start = 0; else if (since_start < 9999) ++since_start;
        if (!play && prev_play && since_start < 120) { quiet_frames = 0; start_quiet = 0; }
    }
    prev_play = play;
    /* End-of-game praise: a celebration is $C0A0 = $10 (Marathon, tier in $D900 from the
     * final score) or $11 (40 Lines / Ultra, rank in $C13B). Marathon tier 5 and rank 3 get
     * "Congratulations" or "Excellent" (kind 12), Marathon tier 4 and rank 2 get "Well done"
     * (kind 13); the app plays it about 1.5 s into the celebration. */
    if (ctx->wram) {
        const uint8_t ws = ctx->wram[0x0A0];
        static int praise_quiet = 0;
        if (praise_quiet > 0) --praise_quiet;
        if (ws != prev_ws && g_tdx_voice && praise_quiet == 0 && (ws == 0x10 || ws == 0x11)) {
            if (ws == 0x10) {
                const uint8_t tier = ctx->wram[0x1900];
                if (tier == 5) { tdx_voice_event(12); praise_quiet = 1800; } else if (tier == 4) { tdx_voice_event(13); praise_quiet = 1800; }   /* once per celebration */
            } else if (ws == 0x11) {
                const uint8_t rank = ctx->wram[0x13B];
                if (rank == 3) { tdx_voice_event(12); praise_quiet = 1800; } else if (rank == 2) { tdx_voice_event(13); praise_quiet = 1800; }
            }
        }
        prev_ws = ws;
    }
    /* Game over: the game enters its top-out state ($0A, the board fills up) from play. */
    {   /* $C119 is the game's "game over in progress" flag (1 on a loss; $80 on a VS win). */
        static uint8_t prev_over = 0;
        static int over_quiet = 0;
        const uint8_t over = ctx->wram[0x119];
        const bool top_out = (st == 0x0A && prev_state >= 0x03 && prev_state <= 0x08) ||
                             (over != 0 && prev_over == 0 && !(over & 0x80));
        if (over_quiet > 0) over_quiet--;
        if (top_out && over_quiet == 0 && g_tdx_voice) { tdx_voice_event(11); over_quiet = 300; }
        prev_over = over;
    }
    /* Easter egg: the score is held in BCD at $AF89-$AF8C and caps at 9,999,999. */
    const bool maxed = ctx->eram[0xF89] == 0x99 && ctx->eram[0xF8A] == 0x99 &&
                       ctx->eram[0xF8B] == 0x99 && ctx->eram[0xF8C] == 0x09;
    if (maxed && !prev_maxed && play && g_tdx_voice) tdx_voice_event(9);
    prev_maxed = maxed;
    if (play && g_tdx_voice && level == 0x20 && prev_level != 0x20 && prev_level != 0) tdx_voice_event(10);   /* max speed */
    prev_level = level;
    prev_state = st;
}

/* ---- end Tetris DX additions ---- */
