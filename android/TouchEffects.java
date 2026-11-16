package org.tetrisdx.recomp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/** The Announcer (voice clips, volume dip) and the effects drawn over the game:
 *  flames, marquee lights, fireworks, the Easter eggs (see TouchControls). */
abstract class TouchEffects extends TouchBase {

    TouchEffects(Context context) {
        super(context);
    }

    /** Box outline plus a small label at the top; returns the label height. */
    boolean fireBoxDrawn = false;
    /** True once any Hold / Next box of ours was drawn this frame. */
    boolean anyBoxDrawn = false;
    /** 0..1 while the ON FIRE effect runs, else 0. */
    float fireLevel() {
        if (effKind != EF_FIRE || effAt == 0) return 0f;
        float t = (SystemClock.uptimeMillis() - effAt) / 1000f;
        return t > 2.6f ? 0f : Math.min(1f, (2.6f - t) / 0.5f);
    }

    /** Tiny fast flames along the top edge of a Hold / Next box while ON FIRE runs. */
    void fireOnBox(Canvas c, float l, float t, float r, float bt, float px) {
        anyBoxDrawn = true;
        float ll = luckyLevel();
        if (ll > 0f) { marquee(c, l, t, r, bt, px, ll); return; }
        float fl = fireLevel();
        if (fl <= 0f) return;
        fireBoxDrawn = true;
        long now = SystemClock.uptimeMillis();
        float step = 2f * px;
        int n = (int) ((r - l - 4f * px) / step);
        for (int i = 0; i < n; i++) fireTuft(c, l + 2f * px + i * step, t + px, px, fl, i, now);
    }

    /** 0..1 while the LUCKY 7 effect runs, else 0. */
    float luckyLevel() {
        if (effKind != EF_LUCKY || effAt == 0) return 0f;
        float t = (SystemClock.uptimeMillis() - effAt) / 1000f;
        return t > 2.6f ? 0f : Math.min(1f, (2.6f - t) / 0.5f);
    }

    /** Rainbow marquee lights chasing around a box. */
    void marquee(Canvas c, float l, float t, float r, float bt, float px, float fl) {
        fireBoxDrawn = true;
        float step = 4f * px, o = px, sz = 2f * px;
        int k = (int) (SystemClock.uptimeMillis() / 45);
        int i = 0;
        for (float x = l; x <= r; x += step, i++) { mq(c, x, t - o, sz, i - k, fl); }
        for (float y = t + step; y <= bt; y += step, i++) { mq(c, r + o, y, sz, i - k, fl); }
        for (float x = r - step; x >= l; x -= step, i++) { mq(c, x, bt + o, sz, i - k, fl); }
        for (float y = bt - step; y > t; y -= step, i++) { mq(c, l - o, y, sz, i - k, fl); }
    }

    void mq(Canvas c, float cx, float cy, float sz, int idx, float fl) {
        fillR(c, cx - sz / 2f, cy - sz / 2f, cx + sz / 2f, cy + sz / 2f, withAlpha(RB[((idx % 7) + 7) % 7], fl));
    }

    void fireTuft(Canvas c, float x, float b, float px, float fl, int i, long now) {
        float ph = now / 38f + i * 1.9f;
        float k = 1f + (float) (Math.sin(ph) + Math.sin(ph * 1.7f + i)) * 0.5f;   // 0..2, fast flicker
        float h = px * (2f + k * 1.5f);
        fillR(c, x, b - h, x + px * 1.6f, b, withAlpha(0xF03030, fl));
        fillR(c, x, b - h * 0.7f, x + px * 1.6f, b, withAlpha(0xF0A020, fl));
        fillR(c, x + px * 0.3f, b - h * 0.35f, x + px * 1.3f, b, withAlpha(0xFFE060, fl));
    }

    /** No Hold / Next box: flames around the game's own NEXT frame (game pixels 112-160 x 16-64). */
    void fireAroundNext(Canvas c) {
        float fl = Math.max(fireLevel(), luckyLevel());
        if (fl <= 0f) return;
        float px = gameW / 160f;
        float l = gameX + 112f * px, t = gameY + 16f * px, r = gameX + 160f * px, bt = gameY + 64f * px;   // the game's own NEXT frame
        long now = SystemClock.uptimeMillis();
        fireOnBox(c, l, t, r, bt, px);
        if (luckyLevel() > 0f) return;
        int rows = (int) ((bt - t) / (4f * px));
        for (int i = 1; i <= rows; i++) {
            float y = t + i * 4f * px;
            fireTuft(c, l - 1.5f * px, y, px, fl, i + 40, now);
            fireTuft(c, r - 2.2f * px, y, px, fl, i + 80, now);
        }
        int cols = (int) ((r - l - 4f * px) / (2f * px));
        for (int i = 0; i < cols; i++) fireTuft(c, l + 2f * px + i * 2f * px, bt + 2.5f * px, px, fl, i + 120, now);   // bottom edge
    }

    // ------------------------------------------------------------ Vanilla flower (Easter egg)

    static final int FL_N = 17;
    int[][] flowerPx;                 // {column, row, colour}
    float[][] flowerVel;              // {vx, vy} in flower cells per second
    long flowerAt = 0;

    void buildFlower() {
        java.util.ArrayList<int[]> list = new java.util.ArrayList<int[]>();
        final double cx = 8, cy = 6.5;
        for (int j = 0; j < FL_N; j++) {
            for (int i = 0; i < FL_N; i++) {
                double dx = i - cx, dy = j - cy, r = Math.sqrt(dx * dx + dy * dy), best = 0;
                for (int k = 0; k < 5; k++) {
                    double pa = -Math.PI / 2 + k * 2 * Math.PI / 5, ux = Math.cos(pa), uy = Math.sin(pa);
                    double qx = dx - ux * 3.2, qy = dy - uy * 3.2;
                    double along = qx * ux + qy * uy, across = -qx * uy + qy * ux;
                    double v = along * along / (3.4 * 3.4) + across * across / (2.1 * 2.1);
                    if (v < 1.0) best = Math.max(best, 1 - v);
                }
                if (r < 1.7) list.add(new int[]{i, j, 0xFFE0A43A});
                else if (best > 0.38) list.add(new int[]{i, j, 0xFFF7EEC9});
                else if (best > 0) list.add(new int[]{i, j, 0xFFE4CF86});
            }
        }
        for (int j = 10; j < FL_N; j++) list.add(new int[]{8, j, 0xFF4E8B3A});
        list.add(new int[]{7, 13, 0xFF4E8B3A}); list.add(new int[]{6, 12, 0xFF4E8B3A});
        list.add(new int[]{5, 12, 0xFF3D7A2E}); list.add(new int[]{9, 14, 0xFF4E8B3A});
        list.add(new int[]{10, 13, 0xFF4E8B3A}); list.add(new int[]{11, 13, 0xFF3D7A2E});
        flowerPx = list.toArray(new int[0][]);
    }

    void startFlower() {
        if (flowerPx == null) buildFlower();
        flowerVel = new float[flowerPx.length][2];
        for (int q = 0; q < flowerPx.length; q++) {
            double a = Math.atan2(flowerPx[q][1] - 6.5, flowerPx[q][0] - 8) + (voiceRng.nextDouble() - 0.5) * 1.2;
            double sp = 3 + voiceRng.nextDouble() * 6;
            flowerVel[q][0] = (float) (Math.cos(a) * sp);
            flowerVel[q][1] = (float) (Math.sin(a) * sp) - 5f;
        }
        flowerAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** A pixel vanilla flower grows from the floor of the board and breaks apart, about 1.3 s in all. */
    void drawVanillaFlower(Canvas c) {
        if (flowerAt == 0 || flowerPx == null) return;
        float t = (SystemClock.uptimeMillis() - flowerAt) / 1000f;
        if (t > 1.4f) { flowerAt = 0; return; }
        float u = gameW / 160f * 2f;                       // one flower cell = 2 game pixels
        float x0 = gameX + (56f - FL_N) * (gameW / 160f);  // centred on the board
        float y0 = gameY + (144f - 2f * FL_N) * (gameW / 160f);  // stem on the floor
        for (int q = 0; q < flowerPx.length; q++) {
            int[] p = flowerPx[q];
            float d = (float) Math.hypot(p[0] - 8, p[1] - 6.5);
            float appear = 0.02f + (16f - p[1]) * 0.018f;   // grows upward from the floor
            if (t < appear) continue;
            float a = 1f, ox = 0f, oy = 0f;
            float k = t - 0.65f - d * 0.012f;
            if (k > 0f) {
                ox = flowerVel[q][0] * k * u;
                oy = (flowerVel[q][1] * k + 14f * k * k) * u;
                a = Math.max(0f, 1f - k / 0.6f);
            }
            if (a <= 0f) continue;
            int col = ((int) (a * 255f) << 24) | (p[2] & 0xFFFFFF);
            float l = Math.round(x0 + p[0] * u + ox), tp = Math.round(y0 + p[1] * u + oy);
            fillR(c, l, tp, l + (float) Math.ceil(u), tp + (float) Math.ceil(u), col);
        }
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------ Easter eggs

    static final int EF_RAIN = 1, EF_FIREWORKS = 2, EF_MILLION = 3, EF_LINES = 4, EF_FIRE = 5,
            EF_LUCKY = 6, EF_BIRTHDAY = 7, EF_SNOW = 8, EF_CAKE = 9, EF_CRESC = 10, EF_WATER = 11, EF_STACK = 12, EF_GUN = 13;
    static final int[] RB = {0xFFF03030, 0xFFF0A020, 0xFFF0E020, 0xFF30E030, 0xFF19E6E6, 0xFF4060FF, 0xFFB040F0};
    static final int[][][] TETRO = {
        {{0, 0}, {1, 0}, {2, 0}, {3, 0}}, {{0, 0}, {1, 0}, {0, 1}, {1, 1}}, {{0, 0}, {1, 0}, {2, 0}, {1, 1}},
        {{1, 0}, {2, 0}, {0, 1}, {1, 1}}, {{0, 0}, {1, 0}, {1, 1}, {2, 1}}, {{0, 0}, {0, 1}, {1, 1}, {2, 1}},
        {{2, 0}, {0, 1}, {1, 1}, {2, 1}}};
    static final String FONT_KEYS = "ABCDEFGHIKLMNOPRSTUVWXYZ0123456789 ";
    static final String[] FONT_BITS = {
        "010101111101101", "110101110101110", "011100100100011", "110101101101110", "111100110100111",
        "111100110100100", "011100101101011", "101101111101101", "111010010010111", "101101110101101",
        "100100100100111", "101111111101101", "110101101101101", "111101101101111", "110101110100100",
        "110101110101101", "011100010001110", "111010010010010", "101101101101111", "101101101101010",
        "101101111111101", "101101010101101", "101101010010010", "111001010100111", "111101101101111",
        "010110010010111", "110001010100111", "110001010001110", "101101111001001", "111100110001110",
        "011100110101010", "111001010010010", "010101010101010", "010101011001110", "000000000000000"};
    int effKind = 0, effNum = 0;
    long effAt = 0;
    float[][] effA;                   // per-effect random numbers
    static final String[] CAKE = {
        "....F...........", "....B...........", "....B.....RR....", "..WWWWWWWWWWWW..", "..WwWwWwWwWwWW..",
        "..PPPPPPPPPPPP..", "..PpPpPpPpPpPP..", "..WWWWWWWWWWWW..", ".SSSSSSSSSSSSSS.", ".SsSsSsSsSsSsSS.",
        ".SSSSSSSSSSSSSS.", "LLLLLLLLLLLLLLLL"};

    static int cakeColour(char ch, boolean flick) {
        switch (ch) {
            case 'F': return flick ? 0xFFFFE060 : 0xFFF0A020;
            case 'B': return 0xFF4060FF;
            case 'R': return 0xFFF03030;
            case 'W': return 0xFFFFFFFF;
            case 'w': return 0xFFE0E0E8;
            case 'P': return 0xFFF080B0;
            case 'p': return 0xFFD86090;
            case 'S': return 0xFFD8A050;
            case 's': return 0xFFB88038;
            case 'L': return 0xFFB0B8C0;
            default: return 0;
        }
    }

    /** VS Computer beaten on the top level: a cake rises, SELF DESTRUCT, 3-2-1, it bursts into pixels. */
    void drawCake(Canvas c, float t) {
        final float S = 3f, BURST = 5.7f;
        float rise = Math.min(1f, t / 1.2f);
        rise = 1f - (1f - rise) * (1f - rise);
        float top = 150f + (62f - 150f) * rise;
        boolean flick = ((int) (t * 12f)) % 2 == 0;
        float x0 = 56f - 8f * S;
        if (t < BURST) {
            for (int j = 0; j < CAKE.length; j++)
                for (int i = 0; i < 16; i++) {
                    char ch = CAKE[j].charAt(i);
                    if (ch != '.') dot(c, x0 + i * S, top + j * S, S, cakeColour(ch, flick));
                }
            if (t > 1.2f && t < 3.6f && ((int) (t * 3.5f)) % 2 == 0) pixText(c, "SELF DESTRUCT", 56f, top - 14f, 0xF03030, 1f);
            if (t >= 3.6f) {
                int n = 3 - (int) ((t - 3.6f) / 0.7f);
                if (n >= 1) pixText(c, String.valueOf(n), 56f, top - 14f, 0xFFFFFF, 1f);
            }
        } else {
            float b = t - BURST;
            for (int j = 0; j < CAKE.length; j++)
                for (int i = 0; i < 16; i++) {
                    char ch = CAKE[j].charAt(i);
                    if (ch == '.') continue;
                    double a = Math.sin((i * 12.9898 + j * 78.233) * 3.1) * 43758.5453;
                    float r1 = (float) (a - Math.floor(a));
                    float ang = r1 * 6.283f, sp = 30f + r1 * 50f;
                    float px = x0 + i * S + (float) Math.cos(ang) * sp * b;
                    float py = top + j * S + (float) Math.sin(ang) * sp * b + 40f * b * b;
                    dot(c, px, py, S, withAlpha(cakeColour(ch, true), 1f - b / 0.9f));
                }
            float w = 1f - b / 0.35f;
            if (w > 0f) fillR(c, gameX, gameY, gameX + gameW, gameY + 144f * gameW / 160f, withAlpha(0xFFFFFF, w * 0.9f));
        }
    }

    // ------------------------------------------------ Crescendo, Waterfall, Korean Stacking
    static final String[] NOTE = {"....11.", "....1.1", "....1.1", "....1..", "....1..", "....1..", ".1111..", "11111..", ".111..."};

    /** Four black eighth notes pop in 0.2 s apart, each bigger than the last, near the top of the play area. */
    void drawCrescendo(Canvas c, float t) {
        float fl = t > 1.2f ? Math.max(0f, (1.5f - t) / 0.3f) : 1f;
        final float[] sz = {1f, 1.5f, 2f, 2.5f};
        final float[] xs = {30f, 43f, 58f, 76f};
        for (int n = 0; n < 4; n++) {
            float at = n * 0.2f;
            if (t < at) continue;
            float k = Math.min(1f, (t - at) / 0.12f);
            float y = 44f - 9f * sz[n] - n * 5f - (float) Math.sin(k * Math.PI) * 3f;
            for (int j = 0; j < NOTE.length; j++)
                for (int i = 0; i < 7; i++)
                    if (NOTE[j].charAt(i) == '1') dot(c, xs[n] + i * sz[n], y + j * sz[n], sz[n], withAlpha(0x000000, fl));
        }
    }

    // The box the Waterfall and Korean Stacking effects draw in: the Hold box, or on compact screens the one
    // box over the game's NEXT window (or the game's own NEXT frame if there is no Hold at all).
    float fxS, fxOX, fxOY, fxT, fxB;                 // fxT / fxB: top and bottom of the usable area, in mock units
    final RectF fxClip = new RectF();

    boolean fxBegin(Canvas c) { return fxBegin(c, false); }

    /** inner: with no Hold / Next box of ours, stay inside the white middle of the game's own NEXT frame (not on its border). */
    boolean fxBegin(Canvas c, boolean inner) {
        float l, t, r, b;
        boolean nativeFrame = false;
        int style = previewStyle();
        if (style == 5 || (isCompact() && (style != 0 || nget(11) != 0))) {
            float px = gameW / 160f;
            l = gameX + 105f * px; t = gameY + 10f * px; r = gameX + 160f * px; b = gameY + 71f * px;
        } else if (nget(11) != 0 && holdRect.width() > 0f) {
            l = holdRect.left; t = holdRect.top; r = holdRect.right; b = holdRect.bottom;
        } else {
            float px = gameW / 160f;
            l = gameX + 112f * px; t = gameY + 16f * px; r = gameX + 160f * px; b = gameY + 64f * px;
            nativeFrame = true;
        }
        fxS = (r - l) / 120f;                       // the mock-ups used a 120 x 78 box
        fxOX = l - 10f * fxS;
        fxOY = (t + b) / 2f - 45f * fxS;
        if (inner && nativeFrame) {
            float px = gameW / 160f;
            fxClip.set(l + 8f * px, t + 8f * px, r - 8f * px, b - 8f * px);   // the white middle: game px 120..152 x 24..56
        } else {
            fxClip.set(l + fxS, t + 14f * fxS, r - fxS, b - fxS);
        }
        fxT = (fxClip.top - fxOY) / fxS;
        fxB = (fxClip.bottom - fxOY) / fxS;
        c.save();
        c.clipRect(fxClip);
        return true;
    }

    void fxR(Canvas c, float x, float y, float w, float h, int argb) {
        fillR(c, fxOX + x * fxS, fxOY + y * fxS, fxOX + (x + w) * fxS, fxOY + (y + h) * fxS, argb);
    }

    static float fxRnd(int i) {
        double a = Math.sin(i * 12.9898) * 43758.5453;
        return (float) (a - Math.floor(a));
    }

    /** Waterfall: a see-through curtain of water falls across the whole box (a held piece stays visible behind it). */
    void drawWaterfall(Canvas c, float t, float fadeIn) {
        fxBegin(c, true);
        final float D = 3.0f, top = fxT, floor = fxB;
        float fade = Math.min(fadeIn, t > D - 0.5f ? Math.max(0f, (D - t) / 0.5f) : 1f);
        float front = top + (floor - top) * Math.min(1f, t / 0.4f);
        float tail = t > D - 0.9f ? top + (floor - top) * Math.min(1f, (t - (D - 0.9f)) / 0.4f) : top;
        int r0 = (int) (t * 35f);
        for (int x = 11; x < 129; x += 2) {
            int ph = (x * 7) % 11;
            boolean gap = (x * 13) % 5 == 0;
            float y = tail;
            int row = 0;
            while (y < front) {
                int k = ((row - r0 + ph) % 6 + 6) % 6;          // 0 bright, 1-2 mid, 3-5 faint
                int len = k == 0 ? 1 : k < 3 ? 3 - k : 6 - k;
                float h = Math.min(len, front - y);
                if (k == 0) fxR(c, x, y, 2, h, withAlpha(0xE8F8FF, 0.85f * fade));
                else if (k < 3) { if (!gap || k == 1) fxR(c, x, y, 2, gap ? Math.min(1f, h) : h, withAlpha(0x7FD6F7, 0.5f * fade)); }
                else if (!gap) fxR(c, x, y, 2, h, withAlpha(0x40C0F0, 0.28f * fade));
                y += len; row += len;
            }
        }
        if (tail <= top) fxR(c, 10, top, 120, 2, withAlpha(0xE8F8FF, fade));
        if (front >= floor) {
            for (int i = 0; i < 22; i++) {
                float q = (t * 2.6f + i * 0.31f) % 1f;
                float sx = 12f + (i * 37) % 114;
                fxR(c, sx, floor - 2f - (float) Math.sin(q * Math.PI) * (3 + (i * 5) % 6), 2, 2, withAlpha(0xFFFFFF, fade * (1f - q) * 0.9f));
            }
            fxR(c, 10, floor - 2f, 120, 2, withAlpha(0xB5E8FF, fade * 0.7f));
        }
        c.restore();
    }

    static final int[] STACK_COL = {0x19E6E6, 0x30E030, 0xF0E020, 0xF0A020, 0xF03030};

    /** Korean Stacking (five singles in a row): five bars sweep across the Hold box and break into sparkles. */
    void drawKoreanStacking(Canvas c, float t, float fl) {   // staircase: five columns rise left to right, then shimmer (inside the white middle with no box of ours)
        fxBegin(c, true);
        for (int i = 0; i < 5; i++) {
            float st = i * 0.26f;
            if (t < st) continue;
            float p = Math.min(1f, (t - st) / 0.4f);
            p = 1f - (1f - p) * (1f - p) * (1f - p);
            float h = (i + 1) * 10f * p, x = 34f + i * 15f;
            fxR(c, x, 72f - h, 13, h, withAlpha(STACK_COL[i], fl));
            if (t > 1.3f) {
                float sh = (float) Math.max(0.0, Math.sin((t - 1.3f) * 14f - i * 1.2f));
                fxR(c, x, 72f - h, 13, h, withAlpha(0xFFFFFF, fl * 0.35f * sh));
            }
        }
        c.restore();
    }

    /** Korean Stacking, colour chase: five colour segments light around the frame one after another, then glow together. */
    void drawBorderChase(Canvas c, float t, float fl) {
        float l, tp, r, b;
        int style = previewStyle();
        float px = gameW / 160f;
        if (style == 5 || (isCompact() && (style != 0 || nget(11) != 0))) {
            l = gameX + 105f * px; tp = gameY + 10f * px; r = gameX + 160f * px; b = gameY + 71f * px;
        } else if (nget(11) != 0 && holdRect.width() > 0f) {
            l = holdRect.left; tp = holdRect.top; r = holdRect.right; b = holdRect.bottom;
        } else {
            l = gameX + 112f * px; tp = gameY + 16f * px; r = gameX + 160f * px; b = gameY + 64f * px;
        }
        float th = (r - l) / 48f * 2f;
        float x0 = l + th * 0.5f, y0 = tp + th * 0.5f, w = (r - l) - 2f * th, h = (b - tp) - 2f * th;
        float per = 2f * (w + h), seg = per / 5f;
        int n = (int) (per / th);
        for (int k = 0; k < n; k++) {
            float d = k * th;
            int i = Math.min(4, (int) (d / seg));
            float st = i * 0.2f;
            if (t < st) continue;
            float x, y;
            if (d < w) { x = x0 + d; y = y0; }
            else if (d < w + h) { x = x0 + w; y = y0 + d - w; }
            else if (d < 2f * w + h) { x = x0 + w - (d - w - h); y = y0 + h; }
            else { x = x0; y = y0 + h - (d - 2f * w - h); }
            float a = fl * (t > 1.3f ? 0.75f + 0.25f * (float) Math.sin(t * 30f - i) : 1f);
            fillR(c, x, y, x + th, y + th, withAlpha(STACK_COL[i], a));
        }
        postInvalidateOnAnimation();
    }

    static final String[] STAR_ROWS = {".....X.....", ".....X.....", "....XXX....", "XXXXXXXXXXX", ".XXXXXXXXX.", "..XXXXXXX..",
            "..XXXXXXX..", ".XXXX.XXXX.", ".XXX...XXX.", ".XX.....XX."};

    /** Gunslinger (40 Lines in under 2 minutes): three shots crack the frame, smoke drifts, a sheriff star stamps in (3 s). */
    void drawGunslinger(Canvas c, float t) {
        fxBegin(c, true);
        float fade = t > 2.55f ? Math.max(0f, (3f - t) / 0.45f) : 1f;
        final float[][] holes = {{50f, 25f}, {92f, 30f}, {58f, 64f}};
        for (int i = 0; i < 3; i++) {
            float st = 0.24f + i * 0.36f;
            if (t < st) continue;
            float u = t - st, hx = holes[i][0], hy = holes[i][1];
            if (u < 0.09f) {
                fxR(c, 30f, 5f, 80f, 80f, withAlpha(0xFFFFFF, 0.7f * (1f - u / 0.09f)));
                fxR(c, hx - 7f, hy - 7f, 14f, 14f, withAlpha(0xFFE9A0, 0.9f));
            }
            fxR(c, hx - 2.5f, hy - 2.5f, 5f, 5f, withAlpha(0x000000, fade));
            int crack = withAlpha(0x555555, fade);
            fxR(c, hx - 5f, hy, 2.5f, 2.5f, crack); fxR(c, hx + 2.5f, hy - 5f, 2.5f, 2.5f, crack);
            fxR(c, hx, hy + 5f, 2.5f, 2.5f, crack); fxR(c, hx + 5f, hy + 2.5f, 2.5f, 2.5f, crack);
            fxR(c, hx - 7.5f, hy - 2.5f, 2.5f, 2.5f, withAlpha(0x777777, fade * 0.8f));
            fxR(c, hx + 7.5f, hy, 2.5f, 2.5f, withAlpha(0x777777, fade * 0.8f));
            float sa = Math.max(0f, 0.5f - u * 1.4f) * fade;
            for (int k = 0; k < 4 && sa > 0f; k++) {
                float yy = hy - u * 45f * (0.6f + k * 0.15f) - k * 5f, xx = hx - 2.5f + (fxRnd(i * 7 + k) - 0.5f) * 7.5f;
                fxR(c, xx, yy, 5f, 5f, withAlpha(0xDDDDDD, sa));
            }
        }
        if (t > 1.35f) {
            float u = Math.min(1f, (t - 1.35f) / 0.36f), sc = u < 1f ? 1.5f - 0.5f * u : 1f, cell = 3f * sc;
            float x0 = 70f - 5.5f * cell, y0 = 45f - 5f * cell;
            for (int r = 0; r < STAR_ROWS.length; r++)
                for (int q = 0; q < 11; q++)
                    if (STAR_ROWS[r].charAt(q) == 'X')
                        fxR(c, x0 + q * cell, y0 + r * cell, cell, cell, withAlpha(0xF4C430, u * fade));
        }
        c.restore();
    }

    void startEffect(int kind, int num) {
        effKind = kind; effNum = num; effAt = SystemClock.uptimeMillis();
        int n = kind == EF_RAIN ? 14 : kind == EF_FIREWORKS ? 78 : kind == EF_LINES ? 20
                : kind == EF_FIRE ? 36 : kind == EF_BIRTHDAY ? 30 : kind == EF_SNOW ? 40 : 0;
        effA = new float[n][4];
        for (int i = 0; i < n; i++) {
            float[] a = effA[i];
            a[0] = voiceRng.nextFloat(); a[1] = voiceRng.nextFloat(); a[2] = voiceRng.nextFloat(); a[3] = voiceRng.nextFloat();
        }
        invalidate();
    }

    void pixText(Canvas c, String s, float cx, float y, int rgb, float alpha) {
        float u = gameW / 160f;
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f) << 24;
        float x0 = gameX + (cx - (s.length() * 4 - 1) / 2f) * u, y0 = gameY + y * u;
        for (int i = 0; i < s.length(); i++) {
            int k = FONT_KEYS.indexOf(s.charAt(i));
            if (k < 0) continue;
            String g = FONT_BITS[k];
            for (int j = 0; j < 15; j++)
                if (g.charAt(j) == '1') {
                    float l = Math.round(x0 + (i * 4 + j % 3) * u), t = Math.round(y0 + (j / 3) * u);
                    fillR(c, l, t, l + (float) Math.ceil(u), t + (float) Math.ceil(u), a | (rgb & 0xFFFFFF));
                }
        }
    }

    void dot(Canvas c, float gx, float gy, float size, int argb) {
        float u = gameW / 160f;
        float l = Math.round(gameX + gx * u), t = Math.round(gameY + gy * u), s = (float) Math.ceil(size * u);
        fillR(c, l, t, l + s, t + s, argb);
    }

    static int withAlpha(int rgb, float a) {
        return ((int) (Math.max(0f, Math.min(1f, a)) * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    void drawEffect(Canvas c) {
        if (effAt == 0) return;
        float t = (SystemClock.uptimeMillis() - effAt) / 1000f;
        float dur = effKind == EF_RAIN ? 4f : effKind == EF_MILLION ? 3.4f : effKind == EF_BIRTHDAY ? 3.4f
                : effKind == EF_SNOW ? 6f : effKind == EF_CAKE ? 6.6f
                : effKind == EF_CRESC ? 1.5f : effKind == EF_WATER ? 3.0f : effKind == EF_STACK ? 2.1f : effKind == EF_GUN ? 3.0f : 2.6f;
        if (t > dur) { effAt = 0; return; }
        float fade = Math.min(1f, (dur - t) / 0.5f);
        switch (effKind) {
            case EF_RAIN:
                for (float[] a : effA) {
                    float x = 16f + (float) Math.floor(a[0] * 70f), s = 44f + a[1] * 56f;
                    float y = ((a[2] * 200f + t * s) % 190f) - 20f;
                    int k = (int) (a[3] * 7f) % 7;
                    for (int[] cell : TETRO[k]) dot(c, x + cell[0] * 4f, y + cell[1] * 4f, 4f, withAlpha(RB[k], fade));
                }
                if (t > 1.2f && t < 3.4f) pixText(c, "CODE OK", 56f, 64f, 0xFFFFFF, Math.min(1f, (3.4f - t) / 0.4f));
                break;
            case EF_FIREWORKS: {
                float[][] ctr = {{40f, 50f}, {70f, 40f}, {52f, 70f}};
                for (int i = 0; i < effA.length; i++) {
                    int b = i / 26;
                    float s = t - 0.2f - b * 0.8f;
                    if (s < 0f || s > 1.4f) continue;
                    double ang = effA[i][0] * 6.283, sp = 10f + effA[i][1] * 22f;
                    float x = ctr[b][0] + (float) Math.cos(ang) * (float) sp * s;
                    float y = ctr[b][1] + (float) Math.sin(ang) * (float) sp * s + 14f * s * s;
                    dot(c, x, y, 2f, withAlpha(RB[(int) (effA[i][2] * 7f) % 7], 1f - s / 1.4f));
                }
                if (t > 0.3f) pixText(c, "PERFECT CLEAR", 56f, 100f, 0xFFFFFF, fade);
                break;
            }
            case EF_MILLION: {
                for (int i = 0; i < 160; i += 4) {
                    int col = RB[((i / 4) + (int) (t * 8f)) % 7];
                    dot(c, i, 0f, 3f, withAlpha(col, fade)); dot(c, i, 141f, 3f, withAlpha(col, fade));
                }
                for (int j = 0; j < 144; j += 4) {
                    int col = RB[((j / 4) + (int) (t * 8f)) % 7];
                    dot(c, 0f, j, 3f, withAlpha(col, fade)); dot(c, 157f, j, 3f, withAlpha(col, fade));
                }
                for (int i = 0; i < 18; i++) {
                    float ph = (float) Math.sin(t * 6f + i * 1.7f);
                    if (ph < 0.3f) continue;
                    float x = 20f + (i * 53) % 120, y = 10f + (i * 37) % 110;
                    dot(c, x, y, 1f, withAlpha(0xFFFFFF, fade));
                    if (ph > 0.75f) {
                        dot(c, x - 1f, y, 1f, withAlpha(0xFFFFFF, fade)); dot(c, x + 1f, y, 1f, withAlpha(0xFFFFFF, fade));
                        dot(c, x, y - 1f, 1f, withAlpha(0xFFFFFF, fade)); dot(c, x, y + 1f, 1f, withAlpha(0xFFFFFF, fade));
                    }
                }
                break;
            }
            case EF_LINES:
                for (int i = 0; i < effA.length; i++) {
                    double ang = i * 0.314;
                    dot(c, 56f + (float) Math.cos(ang) * t * 24f, 64f + (float) Math.sin(ang) * t * 24f, 2f,
                            withAlpha(RB[i % 7], 1f - t / 1.2f));
                }
                pixText(c, effNum + " LINES", 56f, 60f, 0xFFFFFF, fade);
                break;
            case EF_FIRE:
                if (!fireBoxDrawn) fireAroundNext(c);   // no Hold / Next box on screen
                break;
            case EF_LUCKY:
                if (!fireBoxDrawn) fireAroundNext(c);   // no Hold / Next box on screen
                break;
            case EF_CRESC:
                drawCrescendo(c, t);
                break;
            case EF_WATER:
                drawWaterfall(c, t, fade);
                break;
            case EF_GUN:
                drawGunslinger(c, t);
                break;
            case EF_STACK:
                // chase only (user, 14 Nov): the staircase is parked, drawKoreanStacking is kept
                drawBorderChase(c, t, t > 1.7f ? Math.max(0f, (2.1f - t) / 0.4f) : 1f);
                break;
            case EF_CAKE:
                drawCake(c, t);
                break;
            case EF_BIRTHDAY:
                for (float[] a : effA) {
                    float y = (a[0] * 144f + t * (12f + a[1] * 16f)) % 144f;
                    dot(c, 16f + a[2] * 80f, y, 2f, withAlpha(RB[(int) (a[3] * 7f) % 7], fade));
                }
                if (t > 0.2f) {
                    pixText(c, "HAPPY BIRTHDAY", 56f, 50f, 0xFFFFFF, fade);
                    pixText(c, "TETRIS", 56f, 58f, 0xFFD040, fade);
                }
                break;
            case EF_SNOW:
                for (float[] a : effA) {
                    float y = (a[0] * 144f + t * (6f + a[1] * 10f)) % 144f;
                    dot(c, 16f + a[2] * 80f + (float) Math.sin(t * 2f + a[3] * 6f) * 2f, y, 1f, withAlpha(0xFFFFFF, Math.min(1f, fade * 2f)));
                }
                break;
            default:
                break;
        }
        postInvalidateOnAnimation();
    }

    /** A game started: on June 6 or April 21 a birthday message, on Dec 24-26 snow; once per day each. */
    void dateEgg() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int m = cal.get(java.util.Calendar.MONTH), d = cal.get(java.util.Calendar.DAY_OF_MONTH);
        String today = cal.get(java.util.Calendar.YEAR) + "-" + m + "-" + d;
        try {
            android.content.SharedPreferences sp = getContext().getSharedPreferences("tdx_eggs", 0);
            if ((m == 5 && d == 6) || (m == 3 && d == 21)) {
                if (today.equals(sp.getString("bday", ""))) return;
                sp.edit().putString("bday", today).apply();
                postDelayed(new Runnable() { @Override public void run() {
                    startEffect(EF_BIRTHDAY, 0);
                    playClip(sndBirthday);
                } }, 1000);
            } else if (m == 11 && d >= 24 && d <= 26) {
                if (today.equals(sp.getString("snow", ""))) return;
                sp.edit().putString("snow", today).apply();
                postDelayed(new Runnable() { @Override public void run() { startEffect(EF_SNOW, 0); } }, 1000);
            }
        } catch (Throwable t) {
            // no preferences: skip the egg
        }
    }

    void playClip(int id) {
        if (voicePool != null && id != 0 && nget(6) == 0) {
            voicePool.play(id, 0.9f, 0.9f, 1, 0, 1f);
            Long ms = clipMs.get(id);
            duck(ms != null ? ms : 2500L);
        }
    }

    /** Easter egg events (kinds 17-23 from the game side). */
    void eggEvent(int kind) {
        switch (kind) {
            case 17: playClip(sndKonami); startEffect(EF_RAIN, 0); break;
            case 18: playClip(sndMillion); startEffect(EF_MILLION, 0); break;
            case 19: playClip(sndUnlock100); startEffect(EF_LINES, 100); break;
            case 20: startEffect(EF_LINES, 200); break;
            case 21: playClip(sndOnFire); startEffect(EF_FIRE, 0); break;
            case 22: playClip(sndLucky); startEffect(EF_LUCKY, 0); break;
            case 23: dateEgg(); break;
            case 25: {
                int n = nget(46);
                if (n != 200 && n != 300) playClip(n == 100 ? sndUnlock100 : n < 1000 ? sndUnlock200 : sndUnlockBig);   // 200 and 300 are silent: Max speed / Max level voices land there
                startEffect(EF_LINES, n);
                break;
            }
            case 26: playClip(sndCrescendo); startEffect(EF_CRESC, 0); break;
            case 27: playClip(sndWaterfall); startEffect(EF_WATER, 0); break;
            case 28: playClip(sndKorean); startEffect(EF_STACK, 0); break;
            case 33: playClip(sndGun); startEffect(EF_GUN, 0); break;   // Gunslinger: 40 Lines cleared in under 2 minutes
            case 24: playClip(sndCake); startEffect(EF_CAKE, 0); break;
            default: break;
        }
    }

    // ------------------------------------------------------------ GUIDELINE text (Easter egg)

    static final String[][] GL_FONT = {
        {"G", ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."},
        {"U", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
        {"I", "###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"},
        {"D", "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."},
        {"E", "#####", "#....", "#....", "####.", "#....", "#....", "#####"},
        {"L", "#....", "#....", "#....", "#....", "#....", "#....", "#####"},
        {"N", "#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"}};
    static final int[] GL_COLORS = {0xFF19E6E6, 0xFFF0E020, 0xFFB040F0, 0xFF30E030,
            0xFFF03030, 0xFF4060FF, 0xFFF0A020};
    static final int[] GL_COLORS_DARK = {0xFF0A9AB0, 0xFFC04000, 0xFF7020C0, 0xFF108020,
            0xFFC01020, 0xFF1030C0, 0xFF8A5000};
    int[][] glCells;                  // {x, y, colour, letter}
    float[][] glSparks;               // {x, y, phase}
    int glWidth;
    long glAt = 0;

    boolean glSparkle = false;

    void startGuidelineText(boolean sparkle) {
        glSparkle = sparkle;
        java.util.ArrayList<int[]> list = new java.util.ArrayList<int[]>();
        String word = "GUIDELINE";
        int px = 0;
        for (int i = 0; i < word.length(); i++) {
            String[] g = null;
            for (String[] f : GL_FONT) if (f[0].charAt(0) == word.charAt(i)) g = f;
            int w = g[1].length();
            for (int j = 0; j < 7; j++)
                for (int k = 0; k < w; k++)
                    if (g[1 + j].charAt(k) == '#') list.add(new int[]{px + k, j, GL_COLORS[i % 7], i});
            px += w + 1;
        }
        glWidth = px - 1;
        glCells = list.toArray(new int[0][]);
        glSparks = new float[14][3];
        for (float[] s : glSparks) {
            s[0] = voiceRng.nextInt(glWidth); s[1] = voiceRng.nextInt(9) - 1; s[2] = voiceRng.nextFloat() * 6f;
        }
        glAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** GUIDELINE in the guideline piece colours with white sparkles, about 2.2 s, at the bottom of the board. */
    void drawGuidelineText(Canvas c) {
        if (glAt == 0 || glCells == null) return;
        float t = (SystemClock.uptimeMillis() - glAt) / 1000f;
        if (t > 2.2f) { glAt = 0; return; }
        float u = gameW / 160f;
        float x0 = gameX + (56f - glWidth / 2f) * u, y0 = gameY + 118f * u;
        float fade = t > 1.8f ? Math.max(0f, 1f - (t - 1.8f) / 0.4f) : 1f;
        int a = (int) (fade * 255f) << 24;
        if (!glSparkle) {   // without the Guideline palette the board can be yellow: white outer and dark inner outline
            for (int e = 2; e >= 1; e--) {
                int col = e == 2 ? 0xFFFFFF : 0x2A1800;
                for (int[] p : glCells) {
                    if (t < 0.08f + p[3] * 0.045f) continue;
                    float l = Math.round(x0 + (p[0] - e) * u), tp = Math.round(y0 + (p[1] - e) * u);
                    float s = (float) Math.ceil((1 + 2 * e) * u);
                    fillR(c, l, tp, l + s, tp + s, a | col);
                }
            }
        }
        for (int[] p : glCells) {
            if (t < 0.08f + p[3] * 0.045f) continue;
            float l = Math.round(x0 + p[0] * u), tp = Math.round(y0 + p[1] * u);
            fillR(c, l, tp, l + (float) Math.ceil(u), tp + (float) Math.ceil(u), a | (p[2] & 0xFFFFFF));
        }
        if (glSparkle && t > 0.5f) {
            for (float[] s : glSparks) {
                float ph = (float) Math.sin(t * 5f + s[2]);
                if (ph < 0.2f) continue;
                int len = ph > 0.7f ? 2 : 1;
                float cx = Math.round(x0 + s[0] * u), cy = Math.round(y0 + s[1] * u);
                int w = a | 0xFFFFFF;
                fillR(c, cx, cy, cx + (float) Math.ceil(u), cy + (float) Math.ceil(u), w);
                for (int d = 1; d <= len; d++) {
                    fillR(c, cx - d * u, cy, cx - d * u + (float) Math.ceil(u), cy + (float) Math.ceil(u), w);
                    fillR(c, cx + d * u, cy, cx + d * u + (float) Math.ceil(u), cy + (float) Math.ceil(u), w);
                    fillR(c, cx, cy - d * u, cx + (float) Math.ceil(u), cy - d * u + (float) Math.ceil(u), w);
                    fillR(c, cx, cy + d * u, cx + (float) Math.ceil(u), cy + d * u + (float) Math.ceil(u), w);
                }
            }
        }
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------ Announcer

    SoundPool voicePool;
    int sndTetris = 0, sndB2b = 0, sndAll = 0, sndPerfect = 0, sndMax = 0, sndTs = 0, sndTs1 = 0, sndTs2 = 0, sndTs3 = 0, sndMaxOut = 0, sndMaxSpeed = 0, sndGameOver = 0, sndCongrats = 0, sndExcellent = 0, sndWellDone = 0, sndVanilla = 0, sndGuideline = 0, sndKonami = 0, sndMillion = 0, sndUnlock100 = 0, sndUnlock200 = 0,
            sndOnFire = 0, sndLucky = 0, sndBirthday = 0, sndCake = 0, sndUnlockBig = 0, sndCrescendo = 0, sndWaterfall = 0, sndKorean = 0, sndChimeTD = 0, sndChimeTT = 0, sndGun = 0;
    final java.util.Random voiceRng = new java.util.Random();
    int lastTetrisEvent = 0;
    long flashAt = 0;
    int flashKind = 0;

    /** Flash colour: yellow, or cycling colours for the maximum-score Easter egg. */
    int flashColor(int shift) {
        if (flashKind != 9) return 0xFFFFE060;
        float hue = ((SystemClock.uptimeMillis() - flashAt) * 0.36f + shift) % 360f;
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.75f, 1f});
    }

    void initVoice() {
        if (voicePool != null) return;
        try {
            voicePool = new SoundPool.Builder().setMaxStreams(4)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            AssetFileDescriptor a = getContext().getAssets().openFd("tetris_voice.wav");
            sndTetris = voicePool.load(a, 1);
            a.close();
            AssetFileDescriptor b = getContext().getAssets().openFd("b2b_voice.wav");
            sndB2b = voicePool.load(b, 1);
            b.close();
            sndAll = loadClip("allclear_voice.wav");
            sndPerfect = loadClip("perfect_voice.wav");
            sndMax = loadClip("maxlevel_voice.wav");
            sndTs = loadClip("tspin_voice.wav");
            sndTs1 = loadClip("tspin1_voice.wav");
            sndTs2 = loadClip("tspin2_voice.wav");
            sndTs3 = loadClip("tspin3_voice.wav");
            sndMaxOut = loadClip("maxout_voice.wav");
            sndMaxSpeed = loadClip("maxspeed_voice.wav");
            sndGameOver = loadClip("gameover_voice.wav");
            sndCongrats = loadClip("congratulations_voice.wav");
            sndExcellent = loadClip("excellent_voice.wav");
            sndWellDone = loadClip("welldone_voice.wav");
            sndVanilla = loadClip("vanilla_voice.wav");
            sndGuideline = loadClip("guideline_voice.wav");
            sndKonami = loadClip("konami_jingle.wav");
            sndMillion = loadClip("onemillion_voice.wav");
            sndUnlock100 = loadClip("unlock100.wav");
            sndUnlock200 = loadClip("unlock200.wav");
            sndOnFire = loadClip("onfire_voice.wav");
            sndLucky = loadClip("luckyseven_voice.wav");
            sndCake = loadClip("selfdestruct_voice.wav");
            sndUnlockBig = loadClip("unlock_big.wav");
            sndCrescendo = loadClip("crescendo_unlock.wav");
            sndWaterfall = loadClip("waterfall_unlock.wav");
            sndKorean = loadClip("korean_stacking_chime.wav");
            sndGun = loadClip("gunslinger.wav");
            sndChimeTD = loadClip("tripledouble_chime.wav");
            sndChimeTT = loadClip("tripletriple_chime.wav");
            sndBirthday = loadClip("birthday_voice.wav");
        } catch (Throwable t) {
            voicePool = null;
        }
        lastTetrisEvent = nget(33);
    }

    int loadClip(String name) throws java.io.IOException {
        AssetFileDescriptor a = getContext().getAssets().openFd(name);
        int id = voicePool.load(a, 1);
        long ms = 2500;
        try {
            java.io.InputStream in = getContext().getAssets().open(name);
            byte[] h = new byte[44];
            int n = 0, k;
            while (n < 44 && (k = in.read(h, n, 44 - n)) > 0) n += k;
            in.close();
            long byteRate = (h[28] & 0xFFL) | (h[29] & 0xFFL) << 8 | (h[30] & 0xFFL) << 16 | (h[31] & 0xFFL) << 24;
            if (n == 44 && byteRate > 0) ms = (a.getLength() - 44) * 1000L / byteRate;
        } catch (Exception ignored) { }
        clipMs.put(id, ms);
        a.close();
        return id;
    }

    final java.util.HashMap<Integer, Long> clipMs = new java.util.HashMap<Integer, Long>();
    long duckUntil = 0;
    int duckVol = 50;
    boolean ducked = false;
    final Runnable duckTick = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() < duckUntil) duckVol = 25; else duckVol += 6;
            if (duckVol >= 50) { duckVol = 50; ducked = false; TouchControls.nativeSet(45, 50); return; }
            TouchControls.nativeSet(45, duckVol);
            postDelayed(this, 60);
        }
    };

    /** The game drops to half its volume (50% -> 25%) while a clip plays, then eases back. */
    void duck(long ms) {
        duckUntil = Math.max(duckUntil, SystemClock.uptimeMillis() + ms + 150);
        duckVol = 25;
        TouchControls.nativeSet(45, 25);
        if (!ducked) { ducked = true; postDelayed(duckTick, 60); }
    }

    /** Called every 50 ms: a new Tetris event plays its clip and starts the flash. */
    void tetrisVoicePoll() {
        int ev = nget(33);
        if (ev == lastTetrisEvent) return;
        lastTetrisEvent = ev;
        if (nget(32) == 0) return;
        final boolean noVoice = nget(34) >= 34;   // the 3rd and 7th Tetris in a row: lightning only, On fire / Lucky 7 bring their own voice
        final int kind = noVoice ? nget(34) - 33 : nget(34);
        if (kind >= 29 && kind <= 32) {
            flashAt = SystemClock.uptimeMillis(); flashKind = kind;
            if (kind >= 31) {   // heartbeat: one small chime per beat (soft bell x2 for Triple Doubles, glass ping x3 for Triple Triples)
                final int beats = kind == 31 ? 2 : 3;
                for (int i = 0; i < beats; i++)
                    postDelayed(new Runnable() { @Override public void run() { playClip(kind == 31 ? sndChimeTD : sndChimeTT); } }, 1488L * i / beats);
            }
            return;
        }   // Double / Triple: lights round the frame, no sound
        if (kind >= 17) { eggEvent(kind); return; }
        if (kind == 3) startEffect(EF_FIREWORKS, 0);
        if (kind < 11) {    // game over and the end-of-game praise: sound only
            flashAt = SystemClock.uptimeMillis();
            flashKind = kind;
        }
        if (noVoice) { invalidate(); return; }
        if (voicePool != null && nget(6) == 0) {
            if (kind == 16) {   // the sparkling GUIDELINE text only with the Guideline palette; kind 15 is the clip alone
                final boolean sparkle = kind == 16;
                postDelayed(new Runnable() {
                    @Override public void run() { if (nget(19) != 0) startGuidelineText(sparkle); }
                }, 1000);
            }
            if (kind == 14) {   // a new game with every patch off: the flower and the clip, 1 s in
                postDelayed(new Runnable() {
                    @Override public void run() { if (nget(19) != 0) startFlower(); }
                }, 1000);
            }
            if (kind == 12 || kind == 13) return;   // DRAWER: Congratulations / Excellent / Well done (parked, user 14 Nov)
            if (kind >= 12) {   // praise about 1.5 s into a celebration; Vanilla 1 s into a new game
                final int snd = kind >= 15 ? sndGuideline : kind == 14 ? sndVanilla : kind == 13 ? sndWellDone
                        : (voiceRng.nextBoolean() ? sndCongrats : sndExcellent);
                final boolean newGame = kind >= 14;
                postDelayed(new Runnable() {
                    @Override public void run() {
                        if (newGame && nget(19) == 0) return;   // quit (Start + Select) before the clip: stay silent
                        if (voicePool != null && nget(6) == 0) voicePool.play(snd, 0.9f, 0.9f, 1, 0, 1f);
                    }
                }, kind >= 14 ? 1000 : 1500);
                return;
            }
            int snd = kind == 2 ? sndB2b : kind == 3 ? (voiceRng.nextBoolean() ? sndAll : sndPerfect)
                    : kind == 4 ? sndMax : kind == 5 ? sndTs1 : kind == 6 ? sndTs2
                    : kind == 7 ? sndTs3 : kind == 8 ? sndTs : kind == 9 ? sndMaxOut : kind == 10 ? sndMaxSpeed : kind == 11 ? sndGameOver : sndTetris;
            voicePool.play(snd, 0.9f, 0.9f, 1, 0, 1f);
        }
        invalidate();
    }

    /** 0..1 strength of the Hold / Preview flash: three quick pulses over 0.8 s. */
    float flashLevel() {
        if (flashAt == 0 || isLineFx()) return 0f;
        float p = (SystemClock.uptimeMillis() - flashAt) / (flashKind == 9 ? 3000f : 800f);
        if (p >= 1f) return 0f;
        postInvalidateOnAnimation();
        if (flashKind == 9) return p < 0.8f ? 1f : (1f - p) / 0.2f;
        return (1f - p) * (0.5f + 0.5f * (float) Math.cos(p * 6.0 * Math.PI));
    }

    /** The game's NEXT-window frame (black, red, yellow, white, blue studs) at game pixels l..r, t..b;
     *  colours are read from the game picture so palettes and level colours carry over. */
    void drawFrameBox(Canvas c, float l, float t, float r, float bt, float px) {
        int black = sampleOr(112, 40, 0xFF000000), red = sampleOr(150, 20, 0xFFFF0000),
            yellow = sampleOr(150, 18, 0xFFFFFF00), white = sampleOr(120, 24, 0xFFFFFFFF),
            blue = sampleOr(113, 18, 0xFF4A94D5);
        fillR(c, l, t, r, bt, black);
        fillR(c, l + px, t + px, r - px, bt - px, red);
        fillR(c, l + 3f * px, t + 3f * px, r - 3f * px, bt - 3f * px, yellow);
        fillR(c, l + 4f * px, t + 4f * px, r - 4f * px, bt - 4f * px, white);
        float[][] corners = {{l, t}, {r - 4f * px, t}, {l, bt - 4f * px}, {r - 4f * px, bt - 4f * px}};
        for (float[] k : corners) {
            fillR(c, k[0], k[1], k[0] + 4f * px, k[1] + 4f * px, black);
            fillR(c, k[0] + px, k[1] + px, k[0] + 3f * px, k[1] + 3f * px, blue);
        }
    }

    /** Double and Triple (running lights) and Tetris (lightning) on the border of a box, clockwise, once round (1.8 s). */
    boolean isLineFx() { return flashKind == 1 || flashKind == 2 || flashKind == 29 || flashKind == 30 || flashKind == 31 || flashKind == 32; }

    float[] lineFxPos(float d, float x0, float y0, float w, float h) {
        float per = 2f * (w + h), p = ((d % per) + per) % per;
        if (p < w) return new float[]{x0 + p, y0, 0f, -1f};
        if (p < w + h) return new float[]{x0 + w, y0 + p - w, 1f, 0f};
        if (p < 2f * w + h) return new float[]{x0 + w - (p - w - h), y0 + h, 0f, 1f};
        return new float[]{x0, y0 + h - (p - 2f * w - h), -1f, 0f};
    }

    void lineFx(Canvas c, float l, float t, float r, float b) {
        if (flashAt == 0 || !isLineFx()) return;
        float tt = (SystemClock.uptimeMillis() - flashAt) / 1000f;
        final float D = (flashKind == 31 || flashKind == 32) ? 2.4f : 1.8f;
        if (tt >= D) return;
        postInvalidateOnAnimation();
        if (flashKind == 31 || flashKind == 32) {   // Triple Doubles / Triple Triples: the frame glows like a heartbeat, 2 or 3 beats
            int beats = flashKind == 31 ? 2 : 3;
            int[] cols = flashKind == 31 ? new int[]{0x22D3EE, 0x3B82F6, 0xA78BFA} : new int[]{0xFACC15, 0xFB923C, 0xEF4444};
            float ff = tt / D, bt = 0.62f, pf = ff / bt * beats, a;
            int ci = Math.min(beats - 1, (int) Math.floor(pf));
            if (ff < bt) {
                float uu = pf - (int) Math.floor(pf);
                a = uu < 0.25f ? uu / 0.25f : Math.max(0f, 1f - (uu - 0.25f) / 0.75f);
                if (ci < beats - 1) a *= 0.85f;
            } else {
                a = Math.max(0f, 0.25f * (1f - (ff - bt) / 0.2f));
                ci = beats - 1;
            }
            float un = (r - l) / 48f;
            for (int ring = 0; ring < 3; ring++) {
                float o = ring * un, w = un * (1f + ring * 0.4f);
                int col = withAlpha(cols[ci], Math.min(1f, a * (1f - ring * 0.3f)));
                fillR(c, l - o, t - o, r + o, t - o + w, col);
                fillR(c, l - o, b + o - w, r + o, b + o, col);
                fillR(c, l - o, t - o + w, l - o + w, b + o - w, col);
                fillR(c, r + o - w, t - o + w, r + o, b + o - w, col);
            }
            return;
        }
        float f = tt / D, fade = f > 0.75f ? (1f - f) / 0.25f : 1f;
        float u = (r - l) / 48f;
        float x0 = l + 2f * u, y0 = t + 2f * u, w = (r - l) - 4f * u, h = (b - t) - 4f * u, per = 2f * (w + h);
        if (flashKind == 1 || flashKind == 2) {
            int fr = (int) (tt * 24f);
            float head = f * per * 1.15f;
            for (float dd = head - 60f * u; dd <= head; dd += u) {
                float[] q = lineFxPos(dd, x0, y0, w, h);
                int key = (int) Math.floor(dd / u);
                float j = (fxRnd(fr * 7 + key) - 0.5f) * 6f * u, a = fade * Math.max(0f, 1f - (head - dd) / (60f * u));
                float nx = q[2] == 0f ? j : 0f, ny = q[3] == 0f ? j : 0f;
                float cx = q[0] + nx, cy = q[1] + ny;
                fillR(c, cx - 1.5f * u, cy - 1.5f * u, cx + 2.5f * u, cy + 2.5f * u, withAlpha(0x9FD0FF, a * 0.25f));
                fillR(c, cx - 0.5f * u, cy - 0.5f * u, cx + 1.5f * u, cy + 1.5f * u, withAlpha(0xFFFFFF, a));
                if (fxRnd(fr * 3 + key * 5) > 0.93f) {
                    for (int m = 1; m < 5; m++) {
                        float bx = q[2] == 0f ? 0f : (q[2] == 1f ? -m : m) * u, by = q[2] == 0f ? (q[3] == -1f ? m : -m) * u : 0f;
                        fillR(c, cx + bx - 0.5f * u, cy + by - 0.5f * u, cx + bx + u, cy + by + u, withAlpha(0xCFE6FF, a * 0.7f));
                    }
                }
            }
            if (f < 0.12f) fillR(c, l, t, r, b, withAlpha(0xFFFFFF, (1f - f / 0.12f) * 0.12f));
            return;
        }
        int lights = flashKind == 29 ? 1 : 2, tail = flashKind == 29 ? 7 : 11;
        float th = (flashKind == 29 ? 1.5f : 2f) * u;
        for (int k = 0; k < lights; k++) {
            float head = f * per + k * per / lights;
            for (int j = 0; j < tail; j++) {
                float[] q = lineFxPos(head - j * 1.5f * u, x0, y0, w, h);
                fillR(c, q[0], q[1], q[0] + th, q[1] + th, withAlpha(j < 3 ? 0xFFFFFF : 0xFFF3B0, fade * (1f - j / (float) tail)));
            }
        }
    }

    /** No Hold / Next box of ours on screen: a quiet version of the box flash on the game's own NEXT frame.
     *  A short white pulse on the border, then two small lights run once round it (1.3 s). */
    void flashNextFrame(Canvas c) {
        if (flashAt == 0) return;
        if (isLineFx()) { float px0 = gameW / 160f; lineFx(c, gameX + 112f * px0, gameY + 16f * px0, gameX + 160f * px0, gameY + 64f * px0); return; }
        float tt = (SystemClock.uptimeMillis() - flashAt) / 1000f;
        final float D = 1.3f;
        if (tt >= D) return;
        postInvalidateOnAnimation();
        float px = gameW / 160f;
        float l = gameX + 112f * px, t = gameY + 16f * px;
        final float W = 48f, side = W - 4f, per = 4f * side;
        float p = tt / D, fade = p > 0.75f ? (1f - p) / 0.25f : 1f;
        float pulse = Math.max(0f, 1f - tt / 0.25f) * 0.6f;
        if (pulse > 0f) {
            int col = withAlpha(0xFFFFFF, pulse);
            fillR(c, l, t, l + W * px, t + 2f * px, col);
            fillR(c, l, t + (W - 2f) * px, l + W * px, t + W * px, col);
            fillR(c, l, t + 2f * px, l + 2f * px, t + (W - 2f) * px, col);
            fillR(c, l + (W - 2f) * px, t + 2f * px, l + W * px, t + (W - 2f) * px, col);
        }
        for (int h = 0; h < 2; h++) {
            float head = p * per + h * per / 2f;
            for (int j = 0; j < 14; j++) {
                float d = ((head - j * 2f) % per + per) % per, x, y;
                if (d < side) { x = 1f + d; y = 1f; }
                else if (d < 2f * side) { x = W - 3f; y = 1f + d - side; }
                else if (d < 3f * side) { x = W - 3f - (d - 2f * side); y = W - 3f; }
                else { x = 1f; y = W - 3f - (d - 3f * side); }
                fillR(c, l + x * px, t + y * px, l + (x + 2f) * px, t + (y + 2f) * px,
                        withAlpha(j < 3 ? 0xFFFFFF : 0xFFF3B0, fade * (1f - j / 14f)));
            }
        }
    }

    /** In game box: only the frame flashes (red band, yellow band, corner studs), the pieces stay as they are. */
    void flashFrame(Canvas c, float l, float t, float r, float bt, float px) {
        if (isLineFx()) { lineFx(c, l, t, r, bt); return; }
        if (flashLevel() <= 0f) return;
        boolean a = ((SystemClock.uptimeMillis() - flashAt) / 130) % 2 == 0;
        int outer = a ? 0xFFFFE060 : 0xFFFF3050, inner = a ? 0xFFFFFFFF : 0xFFFFE060;
        if (flashKind == 9) { outer = flashColor(0); inner = flashColor(120); }
        for (int ring = 0; ring < 2; ring++) {
            float o = (ring == 0 ? 1f : 3f) * px, w = (ring == 0 ? 2f : 1f) * px;
            int col = ring == 0 ? outer : inner;
            fillR(c, l + o, t + o, r - o, t + o + w, col);
            fillR(c, l + o, bt - o - w, r - o, bt - o, col);
            fillR(c, l + o, t + o + w, l + o + w, bt - o - w, col);
            fillR(c, r - o - w, t + o + w, r - o, bt - o - w, col);
        }
        float[][] corners = {{l, t}, {r - 4f * px, t}, {l, bt - 4f * px}, {r - 4f * px, bt - 4f * px}};
        for (float[] k : corners) fillR(c, k[0] + px, k[1] + px, k[0] + 3f * px, k[1] + 3f * px, inner);
    }

    void drawPixelWord(Canvas c, String[][] word, float x0, float y0, float px, int colour) {
        for (int g = 0; g < word.length; g++) {
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (word[g][row].charAt(col) == '1') {
                        float gx = x0 + (g * 4 + col) * px, gy = y0 + row * px;
                        fillR(c, gx, gy, gx + px, gy + px, colour);
                    }
                }
            }
        }
    }
}
