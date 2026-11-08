package org.tetrisdx.recomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * Everything drawn over the game:
 * - the Game Boy pad (D-pad, A, B, Select, Start). Presses are sent to SDL as
 *   key events, which the runtime's default keyboard bindings map to the Game
 *   Boy buttons (arrows, Z = A, X = B, Backspace = Select, Enter = Start);
 * - the quick buttons, top right (Mute, Palette, Scanlines, Menu), which fade
 *   after 15 s, as in the DKC ports (Lemuroid style);
 * - the centre feedback card (title, value, one bar per choice);
 * - the PATCHES card: the main menu, opened by the Menu quick button, the
 *   Select + D-pad Down, Guide or Android Back. The game is
 *   paused while it is open;
 * - the hold box and the piece queue, and the HOLD button.
 */
public class TouchControls extends View {

    static native boolean nativeMenuOpen();
    static native boolean nativeHardDropOn();
    /** ids: 0 Hard Drop, 1 Ghost, 2 Don't Panic, 3 Skip Celebrations, 4 Palette,
     *  5 Scanlines, 6 Mute, 7 menu (-1 closed, 0 PATCHES card open),
     *  8 Korobeiniki, 9 SRS, 10 Randomizer (0 DX, 1 7-Bag, 2 35-Bag), 11 Hold;
     *  read only: 12 held piece (-1 none), 13 queue length, 14-18 queue,
     *  19 in play, 20-26 piece colours (ARGB), 27 SRS on, 28 hold used,
     *  29 preview (0 off, 1-4 layout), 30 Lock Delay. */
    static native int nativeGet(int id);
    static native void nativeSet(int id, int value);
    /** The game's own picture of piece t (32 x 16 ARGB, 4 x 2 tiles in its spawn
     *  orientation), read live from video RAM; false when not available. */
    static native boolean nativePieceImage(int t, int[] out);
    static native int nativeSample(int x, int y);
    /** bits: 1 open the PATCHES card, 2 close, 4 Controls page, 8 Credits page. */
    static native void nativeRequest(int bits);

    private static final int REQ_OPEN = 1, REQ_CLOSE = 2, REQ_HOLD = 32;

    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3,
            A = 4, B = 5, SELECT = 6, START = 7, COUNT = 8;

    private static final int[] KEYCODES = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER,
    };

    private static final int OUTLINE = 0xFF8A8A8A;
    private static final int FILL = 0x808A8A8A;
    private static final int LABEL = 0xFFBDBDBD;
    // A and B in the original Game Boy's wine red.
    private static final int WINE = 0xFF8C2148;
    private static final int WINE_LABEL = 0xFFF2DCE4;

    private static final String[] GHOST_NAMES = {"Off", "Light", "Dark"};
    private static final String[] SKIP_NAMES = {"Off", "Always", "Start skips"};
    private static final String[] RAND_NAMES = {"Tetris DX", "7-Bag", "35-Bag"};
    // Pastel (2), Sunset (4) and Graphite (8) were removed; the ids stay so saved settings still line up.
    private static final int[] PALETTE_IDS = {0, 1, 3, 5, 6, 7};
    private static final String[] PALETTE_NAMES = {"Original", "GBC LCD", "Pastel", "Swamp", "Sunset", "Guideline", "Sega", "Amber Night", "Graphite"};

    private final boolean[] pressed = new boolean[COUNT];
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;


    private static TouchControls instance;   // for the centre card, posted from native code
    private boolean menuOpen = false;     // the PATCHES card is open
    private boolean hardDropOn = false;
    private boolean gamepadMode = false;
    private boolean controllerPresent = false;   // a physical controller is connected: no touch controls at all
    private int lastPage = -1;

    // Layout, recomputed in onSizeChanged.
    private float dpadX, dpadY, dpadR;
    private float aX, aY, bX, bY, btnR;
    private final RectF selectRect = new RectF(), startRect = new RectF();
    /** Touch Hold button (shown while Hold is on): a pill just above Select and
     *  Start in portrait, above Start in landscape. Presses go to native code. */
    private final RectF holdBtnRect = new RectF();
    private boolean holdBtnDown = false;
    private boolean holdBtnHit = false;
    private final Path dpadPath = new Path();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            int page = menuPage();
            try {
                hardDropOn = nativeHardDropOn();
            } catch (Throwable ignored) {
            }
            int sig = holdQueueSignature();
            if (sig != lastHqSig) {
                lastHqSig = sig;
                invalidate();
            }
            if (page != lastPage) {
                if (page == 0 && lastPage != 0) openedCard();
                if (page >= 0 && lastPage < 0) releaseAll();
                lastPage = page;
                menuOpen = page >= 0;
                invalidate();
            }
            handler.postDelayed(this, 50);
        }
    };

    public TouchControls(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        stroke.setColor(OUTLINE);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(FILL);
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.CENTER);
        // Button labels (A, B, SELECT, START, HOLD) and the HOLD/NEXT boxes use
        // the same face as the PATCHES card's tile names.
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        instance = this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        releaseAll();
        super.onDetachedFromWindow();
    }

    private static int menuPage() {
        try {
            return nativeGet(7);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int nget(int id) {
        try {
            return nativeGet(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void nset(int id, int v) {
        try {
            nativeSet(id, v);
        } catch (Throwable ignored) {
        }
    }

    private static void nreq(int bits) {
        try {
            nativeRequest(bits);
        } catch (Throwable ignored) {
        }
    }

    /** With a controller connected the game buttons (D-pad, A/B, Start/Select, Hold) are never
     *  shown or used; the quick buttons at the top stay. */
    public void setControllerPresent(boolean on) {
        if (controllerPresent == on) return;
        controllerPresent = on;
        if (on) releaseAll();
        invalidate();
    }

    public void cyclePalette() {
        if (menuPage() < 0) quickAct(Q_PALETTE);
    }

    public void openMenu() {
        if (menuPage() < 0) nreq(REQ_OPEN);
    }

    public void setGamepadMode(boolean on) {
        if (gamepadMode == on) return;
        gamepadMode = on;
        if (on) releaseAll();
        invalidate();
    }

    private void buzz() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float dp = density;

        // Same pixel-perfect game rectangle the runtime uses.
        int scale = Math.max(1, Math.min(w / 160, h / 144));
        float gw = 160f * scale, gh = 144f * scale;
        gameW = gw;
        gameH = gh;
        float gx = (w - gw) / 2f;
        float gy;
        if (h > w) {
            gy = Math.max(0f, Math.min(h / 14f, (h - gh) / 2f));
        } else {
            gy = (h - gh) / 2f;
        }

        if (h > w) {
            // Portrait: controls in the space below the game.
            float top = gy + gh;
            float area = h - top;
            dpadR = Math.min(w * 0.17f, area * 0.30f);
            dpadR = Math.max(dpadR, 40 * dp);
            float rowY = top + area * 0.42f;
            dpadX = w * 0.25f;
            dpadY = rowY;
            btnR = dpadR * 0.42f;
            aX = w * 0.82f;
            aY = rowY - btnR * 0.9f;
            bX = w * 0.82f - btnR * 2.6f;
            bY = rowY + btnR * 0.9f;
            float pillW = dpadR * 0.95f, pillH = dpadR * 0.32f;
            float pillY = Math.min(h - pillH - 24 * dp, top + area * 0.80f);
            selectRect.set(w / 2f - pillW - 10 * dp, pillY, w / 2f - 10 * dp, pillY + pillH);
            startRect.set(w / 2f + 10 * dp, pillY, w / 2f + pillW + 10 * dp, pillY + pillH);
            float hy = pillY - pillH - 32 * dp;
            holdBtnRect.set(w / 2f - pillW / 2f, hy, w / 2f + pillW / 2f, hy + pillH);
        } else {
            // Landscape: controls in the side areas next to the game.
            float side = Math.max(gx, w * 0.18f);
            dpadR = Math.min(side * 0.36f, h * 0.20f);
            dpadR = Math.max(dpadR, 40 * dp);
            dpadX = side / 2f;
            dpadY = h * 0.58f;
            btnR = dpadR * 0.42f;
            float rx = w - side / 2f;
            aX = rx + btnR * 1.3f;
            aY = h * 0.58f - btnR * 0.9f;
            bX = rx - btnR * 1.3f;
            bY = h * 0.58f + btnR * 0.9f;
            float pillW = dpadR * 0.95f, pillH = dpadR * 0.32f;
            float pillY = h - pillH - 20 * dp;
            selectRect.set(dpadX - pillW / 2f, pillY, dpadX + pillW / 2f, pillY + pillH);
            startRect.set(rx - pillW / 2f, pillY, rx + pillW / 2f, pillY + pillH);
            float hy = pillY - pillH - 12 * dp;
            holdBtnRect.set(rx - pillW / 2f, hy, rx + pillW / 2f, hy + pillH);
        }

        gameX = gx;
        gameY = gy;
        layoutHoldQueue(w, h);

        // Plus-shaped D-pad outline.
        float arm = dpadR * 0.33f;
        dpadPath.reset();
        dpadPath.moveTo(dpadX - arm, dpadY - dpadR);
        dpadPath.lineTo(dpadX + arm, dpadY - dpadR);
        dpadPath.lineTo(dpadX + arm, dpadY - arm);
        dpadPath.lineTo(dpadX + dpadR, dpadY - arm);
        dpadPath.lineTo(dpadX + dpadR, dpadY + arm);
        dpadPath.lineTo(dpadX + arm, dpadY + arm);
        dpadPath.lineTo(dpadX + arm, dpadY + dpadR);
        dpadPath.lineTo(dpadX - arm, dpadY + dpadR);
        dpadPath.lineTo(dpadX - arm, dpadY + arm);
        dpadPath.lineTo(dpadX - dpadR, dpadY + arm);
        dpadPath.lineTo(dpadX - dpadR, dpadY - arm);
        dpadPath.lineTo(dpadX - arm, dpadY - arm);
        dpadPath.close();

        layoutQuick(w);
    }

    // --------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        int page = menuPage();
        boolean cardVisible = page == 0 && SystemClock.uptimeMillis() - closingAt > 400;
        if (cardVisible) {
            drawPatches(c);
            drawCard(c);
            return;
        }
        if (page >= 0) return;    // closing: nothing on top
        if (!gamepadMode && !controllerPresent) drawPad(c);
        drawHoldQueue(c);
        drawQuick(c);                       // Mute / Palette / Scanlines / Menu stay
        drawCard(c);
        // keep the fade and the cards moving
        if (quickFading() || cardTitle != null) postInvalidateOnAnimation();
        else postInvalidateDelayed(500);
    }

    private void drawPad(Canvas c) {
        // D-pad: light only the pressed arm(s).
        float arm = dpadR * 0.33f;
        if (pressed[UP]) c.drawRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY - arm, fill);
        if (pressed[DOWN]) c.drawRect(dpadX - arm, dpadY + arm, dpadX + arm, dpadY + dpadR, fill);
        if (pressed[LEFT]) c.drawRect(dpadX - dpadR, dpadY - arm, dpadX - arm, dpadY + arm, fill);
        if (pressed[RIGHT]) c.drawRect(dpadX + arm, dpadY - arm, dpadX + dpadR, dpadY + arm, fill);
        c.drawPath(dpadPath, stroke);

        // A and B: grey outline like the other buttons, filled in the original
        // Game Boy's wine red only while pressed.
        text.setTextSize(btnR * 0.75f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(WINE);
        if (pressed[A]) c.drawCircle(aX, aY, btnR, paint);
        if (pressed[B]) c.drawCircle(bX, bY, btnR, paint);
        c.drawCircle(aX, aY, btnR, stroke);
        c.drawCircle(bX, bY, btnR, stroke);
        text.setColor(pressed[A] ? WINE_LABEL : LABEL);
        drawLabel(c, "A", aX, aY);
        text.setColor(pressed[B] ? WINE_LABEL : LABEL);
        drawLabel(c, "B", bX, bY);
        text.setColor(LABEL);

        float pr = selectRect.height() / 2f;
        text.setTextSize(Math.max(10 * density, selectRect.height() * 0.42f));
        if (pressed[SELECT]) c.drawRoundRect(selectRect, pr, pr, fill);
        c.drawRoundRect(selectRect, pr, pr, stroke);
        drawLabel(c, "SELECT", selectRect.centerX(), selectRect.centerY());
        if (pressed[START]) c.drawRoundRect(startRect, pr, pr, fill);
        c.drawRoundRect(startRect, pr, pr, stroke);
        drawLabel(c, "START", startRect.centerX(), startRect.centerY());
        if (holdButtonShown()) {
            if (holdBtnDown) c.drawRoundRect(holdBtnRect, pr, pr, fill);
            c.drawRoundRect(holdBtnRect, pr, pr, stroke);
            drawLabel(c, "HOLD", holdBtnRect.centerX(), holdBtnRect.centerY());
        }
    }

    private void drawLabel(Canvas c, String s, float x, float y) {
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
    }


    // ------------------------------------------------- shared drawing helpers

    private static final int YELLOW = 0xFFFFC107;

    private void fillC(int color, float a) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha((int) (android.graphics.Color.alpha(color) * a));
    }

    private void strokeC(int color, float a, float w) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setAlpha((int) (android.graphics.Color.alpha(color) * a));
    }

    private void textC(Canvas c, String t, float cx, float cy, float size, int color, float a,
                       Typeface face) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha((int) (android.graphics.Color.alpha(color) * a));
        paint.setTypeface(face);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(t, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.DEFAULT);
    }

    // ------------------------------------------------ hold box and piece queue

    // Shapes in their spawn orientation, 4 x 2 cells ("#" = block), by piece
    // number (0 I, 1 T, 2 Z, 3 S, 4 J, 5 L, 6 O). DX shows T, J and L flat side
    // up; with SRS on they spawn flat side down.
    private static final String[] SHAPES_DX = {
            "....####", "###..#..", "##...##.", ".##.##..", "###...#.", "###.#...", ".##..##."};
    private static final String[] SHAPES_SRS = {
            "....####", ".#..###.", "##...##.", ".##.##..", "#...###.", "..#.###.", ".##..##."};
    private float gameX, gameY, gameW, gameH;
    private final RectF holdRect = new RectF();
    private final RectF[] queueRect = {new RectF(), new RectF(), new RectF()};
    private final RectF nextRect = new RectF();   // one box holding all three
    private boolean landscape = false;
    private int layoutW, layoutH, layoutStyle = -1;
    private int lastHqSig = 0;
    private final Bitmap[] pieceBmp = new Bitmap[7];
    private final int[] pieceBuf = new int[32 * 16];
    private long pieceBmpTime = 0;
    private int pieceBmpSig = 0;
    private final android.graphics.Rect bmpSrc = new android.graphics.Rect();
    private final RectF bmpDst = new RectF();
    private final Paint bmpPaint = new Paint();   // no filtering: sharp pixels

    /** The game's own piece picture, refreshed when the palette or rotation
     *  system changes and every half second (null until a game has drawn it). */
    private Bitmap pieceBitmap(int t) {
        long now = SystemClock.uptimeMillis();
        int sig = nget(4) * 3 + nget(27);
        if (sig != pieceBmpSig || now - pieceBmpTime > 500) {
            pieceBmpSig = sig;
            pieceBmpTime = now;
            for (int i = 0; i < 7; i++) {
                boolean ok = false;
                try {
                    ok = nativePieceImage(i, pieceBuf);
                } catch (Throwable ignored) {
                }
                if (!ok) {
                    pieceBmp[i] = null;
                    continue;
                }
                if (pieceBmp[i] == null) {
                    pieceBmp[i] = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
                }
                pieceBmp[i].setPixels(pieceBuf, 0, 32, 0, 0, 32, 16);
            }
        }
        return pieceBmp[t];
    }

    /** Preview setting (native id 29): 0 off, 1-5 layout. In landscape, layout 1
     *  stacks three boxes down the right margin and 2-4 use one box there.
     *  Layout 5 draws inside the game screen, over the game's NEXT window, so it
     *  also works where there is no room around the game (RPC). */
    /** 4:3 screens (e.g. Retroid Pocket Classic): only Preview Off / In game make sense. */
    private boolean isCompact() {
        float aspect = getHeight() > 0 ? (float) getWidth() / getHeight() : 0f;
        return aspect > 1.0f && aspect < 1.5f;   // 4:3 = 1.33, Retroid Pocket Classic 1240x1080 = 1.15
    }

    private int previewStyle() {
        return Math.max(0, Math.min(5, nget(29)));
    }

    private void layoutHoldQueue(int w, int h) {
        layoutW = w;
        layoutH = h;
        int style = previewStyle();
        layoutStyle = style;
        float d = density;
        landscape = w >= h;
        if (!landscape) {
            float underTop = gameY + gameH + 8f * d;
            float underBottom = Math.min(dpadY - dpadR, aY - btnR) - 8f * d;
            float uh = Math.max(28f * d, Math.min(56f * d, underBottom - underTop));
            float topH = Math.max(30f * d, Math.min(56f * d, gameY - 16f * d));
            float left = Math.max(8f * d, gameX);
            float right = Math.min(w - 8f * d, gameX + gameW);
            // Hold box: top left for layouts 1-2 (and Off), under the game on the
            // left for 3 and 4 (3 gives the top bar to the queue).
            if (style == 3 || style == 4) {
                float hw = uh * 1.6f;
                holdRect.set(left, underTop, left + hw, underTop + uh);
            } else {
                float hw = topH * 1.6f;
                holdRect.set(left, 8f * d, left + hw, 8f * d + topH);
            }
            if (style == 1) {
                float qw = uh * 1.6f;
                float[] cxs = {w * 0.17f, w * 0.5f, w * 0.83f};
                for (int i = 0; i < 3; i++) {
                    queueRect[i].set(cxs[i] - qw / 2f, underTop, cxs[i] + qw / 2f, underTop + uh);
                }
            } else if (style == 2) {
                float nw = Math.min(right - left, uh * 4.2f);
                nextRect.set(w / 2f - nw / 2f, underTop, w / 2f + nw / 2f, underTop + uh);
            } else if (style == 3) {
                float nl = left;
                float nr = quickLeft() - 8f * d;
                nextRect.set(nl, 8f * d, Math.max(nl + 40f * d, nr), 8f * d + topH);
            } else if (style == 4) {
                float nw = Math.min(right - holdRect.right - 16f * d, uh * 3.6f);
                nextRect.set(right - nw, underTop, right, underTop + uh);
            }
        } else {
            float side = gameX;
            float hw = Math.min(side - 16f * d, 110f * d), hh = hw / 1.6f;
            holdRect.set((side - hw) / 2f, 12f * d, (side + hw) / 2f, 12f * d + hh);
            float right0 = gameX + gameW;
            float qw = Math.min(w - right0 - 16f * d, 96f * d), qh = qw / 1.6f;
            float qTop = 8f * d + 46f * d + 12f * d;
            float x0 = right0 + (w - right0 - qw) / 2f;
            if (style == 1) {
                for (int i = 0; i < 3; i++) {
                    float y0 = qTop + i * (qh + 6f * d);
                    queueRect[i].set(x0, y0, x0 + qw, y0 + qh);
                }
            } else {
                float nh = Math.min(qw * 1.1f, aY - btnR - 12f * d - qTop);
                nextRect.set(x0, qTop, x0 + qw, qTop + nh);
            }
        }
    }

    private float quickLeft() {
        float d = density;
        return getWidth() - 8f * d - 4 * 46f * d - 3 * 8f * d;
    }

    private int holdQueueSignature() {
        int sig = nget(19) * 31 + nget(11) * 7 + (nget(12) + 2) * 131 + nget(28) * 5 + nget(13) * 17
                + nget(4) * 1009 + nget(27) * 3 + nget(29) * 7919 + (nget(31) + 2) * 389;
        for (int i = 0; i < 3; i++) sig = sig * 37 + (nget(14 + i) + 2);
        return sig;
    }

    /** One block like the game's: black outline, piece colour, light top-left corner, darker edge. */
    private void drawBlock(Canvas c, float l, float t, int s, int colour, float a) {
        int al = (int) (255 * a) << 24;
        int o = Math.max(1, Math.round(s / 8f)), hl = Math.max(1, Math.round(s / 5f));
        int r = (colour >> 16) & 255, g = (colour >> 8) & 255, b = colour & 255;
        int light = (Math.min(255, r + (255 - r) * 6 / 10) << 16) | (Math.min(255, g + (255 - g) * 6 / 10) << 8) | Math.min(255, b + (255 - b) * 6 / 10);
        int dark = ((r * 7 / 10) << 16) | ((g * 7 / 10) << 8) | (b * 7 / 10);
        fillR(c, l, t, l + s, t + s, al);
        fillR(c, l + o, t + o, l + s - o, t + s - o, dark | al);
        fillR(c, l + o, t + o, l + s - 2 * o, t + s - 2 * o, (colour & 0x00FFFFFF) | al);
        fillR(c, l + o, t + o, l + o + hl, t + o + hl, light | al);
    }

    /** Draws a piece centred at (cx, cy); cells are drawn by us at whole screen pixels. */
    private void drawPieceAt(Canvas c, float cx, float cy, float cell, int piece, boolean dim) {
        if (piece < 0 || piece > 6) return;
        String shape = (nget(27) != 0 ? SHAPES_SRS : SHAPES_DX)[piece];
        int minC = 4, maxC = -1, minR = 2, maxR = -1;
        for (int i = 0; i < 8; i++) {
            if (shape.charAt(i) != '#') continue;
            int r = i / 4, col = i % 4;
            minC = Math.min(minC, col); maxC = Math.max(maxC, col);
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
        }
        int cols = maxC - minC + 1, rows = maxR - minR + 1;
        int cs = Math.max(4, Math.round(cell));
        float ox = Math.round(cx - cols * cs / 2f), oy = Math.round(cy - rows * cs / 2f);
        int colour = nget(20 + piece);
        for (int i = 0; i < 8; i++) {
            if (shape.charAt(i) != '#') continue;
            drawBlock(c, ox + (i % 4 - minC) * cs, oy + (i / 4 - minR) * cs, cs, colour, dim ? 0.35f : 1f);
        }
    }

    /** Box outline plus a small label at the top; returns the label height. */
    private float drawBox(Canvas c, RectF box, String label) {
        float d = density;
        fillC(0xFF000000, 0.35f);
        c.drawRoundRect(box, 8f * d, 8f * d, paint);
        strokeC(OUTLINE, 0.8f, 1.5f * d);
        c.drawRoundRect(box, 8f * d, 8f * d, paint);
        float lh = Math.max(9f * d, Math.min(13f * d, box.height() * 0.2f));
        if (label != null) {
            // same pixel letters as the in-game box
            float u = Math.max(2f, Math.round(lh * 0.62f / 5f));
            drawPixelText(c, label, box.centerX(), box.top + lh * 0.35f, u, (LABEL & 0x00FFFFFF) | 0xFF000000);
        }
        return lh * 1.3f;
    }

    /** Layout 5: an opaque box over the game's NEXT window (game pixels
     *  105-160 x 10-71, just above SCORE) with the next piece, the queue and the
     *  held piece. */
    // 3x5 pixel letters for the HOLD label, drawn in game pixels like the game's own text.
    private static final String[] GLYPH_H = {"1.1", "1.1", "111", "1.1", "1.1"};
    private static final String[] GLYPH_O = {"111", "1.1", "1.1", "1.1", "111"};
    private static final String[] GLYPH_L = {"1..", "1..", "1..", "1..", "111"};
    private static final String[] GLYPH_D = {"11.", "1.1", "1.1", "1.1", "11."};
    private static final String[] GLYPH_N = {"1.1", "111", "111", "111", "1.1"};
    private static final String[] GLYPH_E = {"111", "1..", "11.", "1..", "111"};
    private static final String[] GLYPH_X = {"1.1", "1.1", ".1.", "1.1", "1.1"};
    private static final String[] GLYPH_T = {"111", ".1.", ".1.", ".1.", ".1."};

    /** Draws "HOLD" / "NEXT" in the pixel font, centred on cx, top at y, unit size u. */
    private void drawPixelText(Canvas c, String s, float cx, float y, float u, int colour) {
        String[][] w = new String[s.length()][];
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case 'H': w[i] = GLYPH_H; break;
                case 'O': w[i] = GLYPH_O; break;
                case 'L': w[i] = GLYPH_L; break;
                case 'D': w[i] = GLYPH_D; break;
                case 'N': w[i] = GLYPH_N; break;
                case 'E': w[i] = GLYPH_E; break;
                case 'X': w[i] = GLYPH_X; break;
                default:  w[i] = GLYPH_T; break;
            }
        }
        drawPixelWord(c, w, Math.round(cx - (s.length() * 4 - 1) * u / 2f), Math.round(y), u, colour);
    }

    private int sampleOr(int x, int y, int fallback) {
        int v = nativeSample(x, y);
        return v == 0 ? fallback : v;
    }

    private void fillR(Canvas c, float l, float t, float r, float b, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRect(l, t, r, b, paint);
    }

    /** The game's NEXT-window frame (black, red, yellow, white, blue studs) at game pixels l..r, t..b;
     *  colours are read from the game picture so palettes and level colours carry over. */
    private void drawFrameBox(Canvas c, float l, float t, float r, float bt, float px) {
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

    private void drawPixelWord(Canvas c, String[][] word, float x0, float y0, float px, int colour) {
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

    /** The box over the game's NEXT window (layout "In game"). */
    private void drawInGameBox(Canvas c, boolean showQueue) {
        float px = gameW / 160f;
        float l = gameX + 105f * px, t = gameY + 10f * px, r = gameX + 160f * px, bt = gameY + 71f * px;
        int black = sampleOr(112, 40, 0xFF000000);
        drawFrameBox(c, l, t, r, bt, px);
        float cx = (l + r) / 2f;
        drawPieceAt(c, cx, t + 11f * px, 6f * px, nget(31), false);
        int n = showQueue ? Math.min(2, nget(13)) : 0;
        for (int i = 0; i < n; i++) {
            drawPieceAt(c, l + 4f * px + (r - l - 8f * px) * (i + 0.5f) / 2f, t + 26f * px, 5f * px, nget(14 + i), false);
        }
        if (nget(11) != 0) {
            float labelY = showQueue ? 36f : 27f, pieceY = showQueue ? 51f : 45f, cell = showQueue ? 5f : 7f;
            drawPixelWord(c, new String[][]{GLYPH_H, GLYPH_O, GLYPH_L, GLYPH_D}, cx - 7.5f * px, t + labelY * px, px, black);
            drawPieceAt(c, cx, t + pieceY * px, cell * px, nget(12), nget(28) != 0);
        }
    }

    private void drawHoldQueue(Canvas c) {
        if (nget(19) == 0) return;          // not in a game
        int style = previewStyle();
        // 4:3 screens (e.g. Retroid Pocket Classic) have no room around the
        // game, so Hold and the queue go in the box over the NEXT window.
        boolean compact = isCompact();
        if (style == 5 || (compact && (style != 0 || nget(11) != 0))) {
            drawInGameBox(c, style != 0);
            return;
        }
        if (style != layoutStyle || getWidth() != layoutW || getHeight() != layoutH) {
            layoutHoldQueue(getWidth(), getHeight());
        }
        if (nget(11) != 0) {
            float lh = drawBox(c, holdRect, "HOLD");
            float cell = Math.min(holdRect.width() * 0.8f / 4f, (holdRect.height() - lh) * 0.75f / 2f);
            drawPieceAt(c, holdRect.centerX(), holdRect.top + lh + (holdRect.height() - lh) / 2f,
                    cell, nget(12), nget(28) != 0);
        }
        int n = Math.min(3, nget(13));
        if (style == 0 || n <= 0) return;
        if (style == 1) {
            for (int i = 0; i < n; i++) {
                RectF b = queueRect[i];
                float lh = drawBox(c, b, i == 0 ? "NEXT" : null);
                float cell = Math.min(b.width() * 0.8f / 4f, (b.height() - lh) * 0.75f / 2f);
                drawPieceAt(c, b.centerX(), b.top + lh + (b.height() - lh) / 2f, cell, nget(14 + i), false);
            }
            return;
        }
        RectF b = nextRect;
        float lh = drawBox(c, b, "NEXT");
        if (landscape) {
            float slot = (b.height() - lh) / 3f;
            float cell = Math.min(b.width() * 0.7f / 4f, slot * 0.7f / 2f);
            for (int i = 0; i < n; i++) {
                drawPieceAt(c, b.centerX(), b.top + lh + slot * (i + 0.5f), cell, nget(14 + i), false);
            }
        } else {
            float slot = b.width() / 3f;
            float cell = Math.min(slot * 0.75f / 4f, (b.height() - lh) * 0.7f / 2f);
            for (int i = 0; i < n; i++) {
                drawPieceAt(c, b.left + slot * (i + 0.5f), b.top + lh + (b.height() - lh) / 2f,
                        cell, nget(14 + i), false);
            }
        }
    }

    // ---------------------------- quick buttons (top right, Lemuroid style)

    private static final int Q_MUTE = 0, Q_PALETTE = 1, Q_SCAN = 2, Q_MENU = 3, Q_N = 4;
    private final RectF[] qRect = {new RectF(), new RectF(), new RectF(), new RectF()};
    private long qTouched = SystemClock.uptimeMillis();
    private int qDown = -1;

    private void layoutQuick(int w) {
        float d = density, sz = 46f * d, gap = 8f * d, m = 8f * d;
        float x = w - m;
        for (int i = Q_N - 1; i >= 0; i--) {
            qRect[i].set(x - sz, m, x, m + sz);
            x -= sz + gap;
        }
    }

    private int quickHit(float x, float y) {
        float s = 2f * density;
        for (int i = 0; i < Q_N; i++) {
            RectF r = qRect[i];
            if (x >= r.left - s && x <= r.right + s && y >= r.top - s && y <= r.bottom + s) return i;
        }
        return -1;
    }

    // buttons fade to 30% after 15 seconds; any touch brings them back
    private float quickAlpha() {
        long dt = SystemClock.uptimeMillis() - qTouched;
        if (dt < 15000) return 1f;
        if (dt < 15600) return 1f - 0.7f * (dt - 15000) / 600f;
        return 0.3f;
    }

    private boolean quickFading() {
        long dt = SystemClock.uptimeMillis() - qTouched;
        return dt > 14900 && dt < 15700;
    }

    private boolean quickTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            int i = quickHit(e.getX(), e.getY());
            if (i < 0) return false;
            qDown = i;
            invalidate();
            return true;
        }
        if (qDown < 0) return false;
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            int i = qDown;
            qDown = -1;
            qTouched = SystemClock.uptimeMillis();
            if (a == MotionEvent.ACTION_UP && quickHit(e.getX(), e.getY()) == i) {
                buzz();
                quickAct(i);
            }
            invalidate();
        }
        return true;
    }

    private void quickAct(int i) {
        switch (i) {
            case Q_MUTE:
                nset(6, nget(6) != 0 ? 0 : 1);
                break;
            case Q_PALETTE: {
                int pos = 0;
                for (int k = 0; k < PALETTE_IDS.length; k++) if (PALETTE_IDS[k] == nget(4)) pos = k;
                pos = (pos + 1) % PALETTE_IDS.length;
                int p = PALETTE_IDS[pos];
                nset(4, p);
                showCard("PALETTE", PALETTE_NAMES[p], pos, PALETTE_IDS.length);
                break;
            }
            case Q_SCAN: {
                int s = nget(5) != 0 ? 0 : 1;
                nset(5, s);
                showCard("SCANLINES", s != 0 ? "On" : "Off", s, 2);
                break;
            }
            default:
                releaseAll();
                nreq(REQ_OPEN);
                break;
        }
        invalidate();
    }

    private void drawQuick(Canvas c) {
        float a = quickAlpha(), d = density;
        boolean mute = nget(6) != 0;
        for (int i = 0; i < Q_N; i++) {
            RectF r = qRect[i];
            float cx = r.centerX(), cy = r.centerY(), g = 23f * d / 24f;
            boolean active = (i == Q_MUTE && mute) || qDown == i;
            fillC(0xFF000000, a * (active ? 0.6f : 0.35f));
            c.drawCircle(cx, cy, r.width() / 2f, paint);
            float ox = cx - 12f * g, oy = cy - 12f * g;
            int tint = (i == Q_MUTE && mute) ? YELLOW : 0xCCFFFFFF;
            strokeC(tint, a, 1.8f * d);
            switch (i) {
                case Q_MUTE:
                    path.reset();
                    path.moveTo(ox + 3 * g, oy + 9 * g); path.lineTo(ox + 7 * g, oy + 9 * g);
                    path.lineTo(ox + 12 * g, oy + 4 * g); path.lineTo(ox + 12 * g, oy + 20 * g);
                    path.lineTo(ox + 7 * g, oy + 15 * g); path.lineTo(ox + 3 * g, oy + 15 * g);
                    path.close();
                    c.drawPath(path, paint);
                    if (!mute) {
                        c.drawArc(ox + 8 * g, oy + 7 * g, ox + 18 * g, oy + 17 * g, -50f, 100f, false, paint);
                        c.drawArc(ox + 5 * g, oy + 3 * g, ox + 23 * g, oy + 21 * g, -50f, 100f, false, paint);
                    } else {
                        c.drawLine(ox + 16 * g, oy + 9 * g, ox + 21 * g, oy + 15 * g, paint);
                        c.drawLine(ox + 21 * g, oy + 9 * g, ox + 16 * g, oy + 15 * g, paint);
                    }
                    break;
                case Q_PALETTE: {
                    c.drawCircle(cx, cy, 9f * g, paint);
                    int[] dots = {0xE6F0715A, 0xE643D05E, 0xE66EAAFF, 0xE6FFC107};
                    float[][] at = {{-4, -3}, {0, -5}, {4, -3}, {4, 2}};
                    for (int k = 0; k < 4; k++) {
                        fillC(dots[k], a);
                        c.drawCircle(cx + at[k][0] * g, cy + at[k][1] * g, 1.7f * g, paint);
                    }
                    break;
                }
                case Q_SCAN: {
                    c.drawRoundRect(ox + 2 * g, oy + 5 * g, ox + 22 * g, oy + 19 * g, 2.5f * d, 2.5f * d, paint);
                    strokeC(tint, a, 1.2f * d);
                    for (int k = 0; k < 3; k++) {
                        c.drawLine(ox + 5 * g, oy + (9 + 3 * k) * g, ox + 19 * g, oy + (9 + 3 * k) * g, paint);
                    }
                    break;
                }
                default:
                    for (int k = 0; k < 3; k++) {
                        c.drawLine(ox + 4 * g, oy + (6 + 6 * k) * g, ox + 20 * g, oy + (6 + 6 * k) * g, paint);
                    }
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    // ------------------------- centre feedback card (title, value, bars)

    private String cardTitle = null, cardValue = "";
    private int cardIndex = 0, cardCount = 0;
    private long cardAt = 0;

    private void showCard(String title, String value, int index, int count) {
        cardTitle = title;
        cardValue = value;
        cardIndex = index;
        cardCount = count;
        cardAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Called from native code (gamepad shortcuts) on any thread. */
    public static void card(String title, String value, int index, int count) {
        final TouchControls v = instance;
        if (v == null) return;
        v.post(() -> v.showCard(title, value, index, count));
    }

    private void drawCard(Canvas c) {
        if (cardTitle == null) return;
        long dt = SystemClock.uptimeMillis() - cardAt;
        if (dt > 1200) {
            cardTitle = null;
            return;
        }
        float d = density;
        float a = dt > 900 ? 1f - (dt - 900) / 300f : 1f;
        float ts = 11f * d, vs = 22f * d;
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(ts);
        paint.setLetterSpacing(0.11f);
        float tw = paint.measureText(cardTitle);
        paint.setLetterSpacing(0f);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(vs);
        float vw = paint.measureText(cardValue);
        paint.setTypeface(Typeface.DEFAULT);
        float bars = cardCount * 20f * d + Math.max(0, cardCount - 1) * 6f * d;
        float w = Math.max(Math.max(tw, vw), bars) + 44f * d;
        float h = 12f * d * 2 + ts * 1.3f + vs * 1.3f + 8f * d + 5f * d;
        float x0 = (getWidth() - w) / 2f, y0 = (getHeight() - h) / 2f;
        fillC(0xFF0A0C0E, a * 0.82f);
        c.drawRoundRect(x0, y0, x0 + w, y0 + h, 16f * d, 16f * d, paint);
        paint.setColor(0xFF9AA4AD);
        paint.setAlpha((int) (255 * a));
        paint.setTextSize(ts);
        paint.setLetterSpacing(0.11f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(cardTitle, x0 + w / 2f, y0 + 12f * d + ts, paint);
        paint.setLetterSpacing(0f);
        paint.setTextAlign(Paint.Align.LEFT);
        textC(c, cardValue, x0 + w / 2f, y0 + 12f * d + ts * 1.3f + vs * 0.65f, vs, 0xFFFFFFFF, a,
                Typeface.DEFAULT_BOLD);
        float bx = x0 + (w - bars) / 2f, by = y0 + h - 12f * d - 5f * d;
        for (int i = 0; i < cardCount; i++) {
            fillC(i == cardIndex ? YELLOW : 0x38FFFFFF, a);
            c.drawRoundRect(bx + i * 26f * d, by, bx + i * 26f * d + 20f * d, by + 5f * d, 3f * d, 3f * d, paint);
        }
        postInvalidateOnAnimation();
    }

    // -------------------- PATCHES card: the main menu (same style as DKC STATES)

    private static final String[] KORO_NAMES = {"Off", "Original", "Plus"};
    private static final String[] PATCH_NAMES = {"Hard Drop", "Ghost Piece", "Don't Panic", "Skip Celebrations", "Korobeiniki", "SRS Rotation", "Randomizer", "Hold", "Preview", "Lock Delay"};
    private static final int NT = 10;  // tiles: two columns (an odd last one is full width)
    private final RectF patchesCard = new RectF();
    private final RectF[] tileRect = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private int sel = 0;              // tile 0-9
    private boolean navActive = false; // gamepad in use on the card: show the hint line
    private int touchItem = -1;
    private boolean touchOutside = false;
    private long closingAt = 0;

    private void openedCard() {
        sel = 0;
        navActive = gamepadMode;
        touchItem = -1;
        releaseAll();
    }

    private String patchValue(int i) {
        switch (i) {
            case 0: return nget(0) != 0 ? "On" : "Off";
            case 1: return GHOST_NAMES[Math.max(0, Math.min(2, nget(1)))];
            case 2: return nget(2) != 0 ? "On" : "Off";
            case 3: return SKIP_NAMES[Math.max(0, Math.min(2, nget(3)))];
            case 4: return KORO_NAMES[Math.max(0, Math.min(2, nget(8)))];
            case 5: return nget(9) != 0 ? "On" : "Off";
            case 6: return RAND_NAMES[Math.max(0, Math.min(2, nget(10)))];
            case 7: return nget(11) != 0 ? "On" : "Off";
            case 8: {
                int p = previewStyle();
                if (isCompact() && p != 0) p = 5;
                return p == 0 ? "Off" : (p == 5 ? "In game" : "Layout " + p);
            }
            default: return nget(30) != 0 ? "On" : "Off";
        }
    }

    private void patchesPick(int i) {
        switch (i) {
            case 0: nset(0, nget(0) != 0 ? 0 : 1); break;
            case 1: nset(1, (nget(1) + 1) % 3); break;
            case 2: nset(2, nget(2) != 0 ? 0 : 1); break;
            case 3: nset(3, (nget(3) + 1) % 3); break;
            case 4: nset(8, (nget(8) + 1) % 3); break;
            case 5: nset(9, nget(9) != 0 ? 0 : 1); break;
            case 6: nset(10, (nget(10) + 1) % 3); break;
            case 7: nset(11, nget(11) != 0 ? 0 : 1); break;
            case 8: nset(29, isCompact() ? (previewStyle() == 0 ? 5 : 0) : (previewStyle() + 1) % 6); break;
            case 9: nset(30, nget(30) != 0 ? 0 : 1); break;
            default: return;
        }
        buzz();
        invalidate();
    }

    private void closePatches() {
        closingAt = SystemClock.uptimeMillis();
        nreq(REQ_CLOSE);
        qTouched = SystemClock.uptimeMillis();
        invalidate();
    }

    private void patchIcon(Canvas c, int kind, float cx, float cy, float s, int color) {
        strokeC(color, 1f, Math.max(2f, s * 0.16f));
        switch (kind) {
            case 0: // hard drop: arrow down onto a line
                c.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.2f, paint);
                c.drawLine(cx - s * 0.5f, cy - s * 0.3f, cx, cy + s * 0.2f, paint);
                c.drawLine(cx + s * 0.5f, cy - s * 0.3f, cx, cy + s * 0.2f, paint);
                c.drawLine(cx - s * 0.8f, cy + s * 0.75f, cx + s * 0.8f, cy + s * 0.75f, paint);
                break;
            case 1: { // ghost piece: a dashed T tetromino
                float q = s * 0.55f;
                paint.setStrokeWidth(Math.max(2f, s * 0.11f));
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{q * 0.22f, q * 0.16f}, 0f));
                float x0 = cx - 1.5f * q, y0 = cy - q;
                path.reset();
                path.moveTo(x0, y0);
                path.lineTo(x0 + 3 * q, y0);
                path.lineTo(x0 + 3 * q, y0 + q);
                path.lineTo(x0 + 2 * q, y0 + q);
                path.lineTo(x0 + 2 * q, y0 + 2 * q);
                path.lineTo(x0 + q, y0 + 2 * q);
                path.lineTo(x0 + q, y0 + q);
                path.lineTo(x0, y0 + q);
                path.close();
                c.drawPath(path, paint);
                paint.setPathEffect(null);
                break;
            }
            case 2: // don't panic: a music note, struck through
                c.drawLine(cx + s * 0.1f, cy - s * 0.7f, cx + s * 0.1f, cy + s * 0.35f, paint);
                c.drawOval(cx - s * 0.45f, cy + s * 0.15f, cx + s * 0.15f, cy + s * 0.6f, paint);
                c.drawLine(cx + s * 0.1f, cy - s * 0.7f, cx + s * 0.55f, cy - s * 0.45f, paint);
                c.drawLine(cx - s * 0.8f, cy + s * 0.8f, cx + s * 0.8f, cy - s * 0.8f, paint);
                break;
            case 4: // korobeiniki: two beamed music notes
                c.drawLine(cx - s * 0.45f, cy - s * 0.55f, cx - s * 0.45f, cy + s * 0.45f, paint);
                c.drawLine(cx + s * 0.55f, cy - s * 0.75f, cx + s * 0.55f, cy + s * 0.25f, paint);
                c.drawLine(cx - s * 0.45f, cy - s * 0.55f, cx + s * 0.55f, cy - s * 0.75f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawOval(cx - s * 0.95f, cy + s * 0.25f, cx - s * 0.4f, cy + s * 0.7f, paint);
                c.drawOval(cx + s * 0.05f, cy + s * 0.05f, cx + s * 0.6f, cy + s * 0.5f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case 5: { // SRS: a T piece pointing up (its SRS spawn state), four outlined cells
                float q = s * 0.62f, g = q * 0.08f;
                paint.setStrokeWidth(Math.max(2f, q * 0.14f));
                paint.setStrokeJoin(Paint.Join.MITER);
                float x0 = cx - 1.5f * q, y0 = cy - q;
                int[][] cells = {{1, 0}, {0, 1}, {1, 1}, {2, 1}};
                for (int[] cell : cells) {
                    float l = x0 + cell[0] * q, t = y0 + cell[1] * q;
                    c.drawRect(l + g, t + g, l + q - g, t + q - g, paint);
                }
                break;
            }
            case 6: { // randomizer: a die showing three
                float r = s * 0.85f;
                c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, r * 0.3f, r * 0.3f, paint);
                paint.setStyle(Paint.Style.FILL);
                float o = r * 0.5f, dot = Math.max(2f, r * 0.17f);
                c.drawCircle(cx - o, cy - o, dot, paint);
                c.drawCircle(cx, cy, dot, paint);
                c.drawCircle(cx + o, cy + o, dot, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            }
            case 7: { // hold: a box with a small piece inside
                float r = s * 0.9f;
                c.drawRoundRect(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f, r * 0.2f, r * 0.2f, paint);
                paint.setStyle(Paint.Style.FILL);
                float q = r * 0.34f;
                c.drawRect(cx - 1.5f * q, cy, cx + 1.5f * q, cy + q * 0.9f, paint);
                c.drawRect(cx - 0.5f * q, cy - q, cx + 0.5f * q, cy, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            }
            case 8: { // preview: three small stacked bars
                float r = s * 0.85f;
                for (int k = 0; k < 3; k++) {
                    float y = cy - r * 0.7f + k * r * 0.7f;
                    c.drawRect(cx - r * 0.6f, y - r * 0.18f, cx + r * 0.6f, y + r * 0.18f, paint);
                }
                break;
            }
            case 9: { // lock delay: a clock face
                float r = s * 0.85f;
                c.drawCircle(cx, cy, r, paint);
                c.drawLine(cx, cy, cx, cy - r * 0.6f, paint);
                c.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, paint);
                break;
            }
            case 3: // skip celebrations: skip-to-end
                path.reset();
                path.moveTo(cx - s * 0.75f, cy - s * 0.5f); path.lineTo(cx - s * 0.75f, cy + s * 0.5f);
                path.lineTo(cx - s * 0.05f, cy); path.close();
                path.moveTo(cx - s * 0.05f, cy - s * 0.5f); path.lineTo(cx - s * 0.05f, cy + s * 0.5f);
                path.lineTo(cx + s * 0.6f, cy); path.close();
                c.drawPath(path, paint);
                c.drawLine(cx + s * 0.75f, cy - s * 0.5f, cx + s * 0.75f, cy + s * 0.5f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawPatches(Canvas c) {
        float d = density, W = getWidth(), H = getHeight();
        float pad = 22f * d, gap = 14f * d, titleH = 26f * d, hintH = 30f * d;
        float cw = Math.min(W * 0.94f, 600f * d);
        final int rows = (NT + 1) / 2;
        float tw = (cw - 2f * pad - gap) / 2f, th = tw * 0.5f;
        float ch = pad + titleH + rows * th + (rows - 1) * gap + hintH;
        if (ch > H * 0.94f) {   // short landscape screens: shrink the tiles to fit
            th = (H * 0.94f - pad - titleH - (rows - 1) * gap - hintH) / rows;
            ch = H * 0.94f;
        }
        float x0 = (W - cw) / 2f, y0 = (H - ch) / 2f;
        patchesCard.set(x0, y0, x0 + cw, y0 + ch);

        fillC(0xFF000000, 0.27f);
        c.drawRect(0, 0, W, H, paint);   // light dimming behind the card
        fillC(0xFF0A0C0E, 0.82f);
        c.drawRoundRect(patchesCard, 16f * d, 16f * d, paint);

        Typeface semi = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF9AA4AD);
        paint.setTypeface(semi);
        paint.setTextSize(12f * d);
        paint.setLetterSpacing(0.11f);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("PATCHES", x0 + pad, y0 + pad + 8f * d, paint);
        paint.setLetterSpacing(0f);

        for (int i = 0; i < NT; i++) {
            float bx = x0 + pad + (i % 2) * (tw + gap), by = y0 + pad + titleH + (i / 2) * (th + gap);
            float tileW = (i == NT - 1 && NT % 2 == 1) ? 2f * tw + gap : tw;
            tileRect[i].set(bx, by, bx + tileW, by + th);
            boolean selShown = i == sel;
            fillC(0xFF1E2226, 1f);
            c.drawRoundRect(tileRect[i], 12f * d, 12f * d, paint);
            strokeC(selShown ? YELLOW : 0x26FFFFFF, 1f, selShown ? 3f * d : 1f * d);
            c.drawRoundRect(tileRect[i], 12f * d, 12f * d, paint);
            String val = patchValue(i);
            boolean on = !val.equals("Off");
            int col = selShown ? 0xFFFFFFFF : 0xFFC8CDD2;
            patchIcon(c, i, bx + tileW / 2f, by + th * 0.33f, Math.min(15f * d, th * 0.16f), on ? YELLOW : col);
            textC(c, PATCH_NAMES[i], bx + tileW / 2f, by + th * 0.66f, Math.min(15f * d, th * 0.15f), col, 1f, semi);
            textC(c, val, bx + tileW / 2f, by + th * 0.85f, Math.min(12.5f * d, th * 0.125f),
                    on ? YELLOW : 0xFF9AA4AD, 1f, Typeface.DEFAULT);
        }

        if (navActive) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(12f * d);
            paint.setColor(0xFF9AA4AD);
            paint.setTextAlign(Paint.Align.LEFT);
            c.drawText("A  Toggle        B  Close        ◀ ▶ ▲ ▼  Select",
                    x0 + pad, y0 + ch - 14f * d, paint);
        }
        paint.setTypeface(Typeface.DEFAULT);
    }

    private boolean patchesTouch(MotionEvent e) {
        int a = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (a == MotionEvent.ACTION_DOWN) {
            navActive = false;
            touchOutside = !patchesCard.contains(x, y);
            touchItem = -1;
            for (int i = 0; i < NT; i++) if (tileRect[i].contains(x, y)) touchItem = i;
            if (touchItem >= 0) sel = touchItem;
            invalidate();
        } else if (a == MotionEvent.ACTION_UP) {
            if (touchOutside) closePatches();
            else if (touchItem >= 0 && tileRect[touchItem].contains(x, y)) patchesPick(touchItem);
            touchItem = -1;
            touchOutside = false;
            invalidate();
        } else if (a == MotionEvent.ACTION_CANCEL) {
            touchItem = -1;
            touchOutside = false;
        }
        return true;
    }

    /** True while the PATCHES card is on screen (the activity routes keys here). */
    public boolean patchesVisible() {
        return menuPage() == 0 && SystemClock.uptimeMillis() - closingAt > 400;
    }

    /**
     * Gamepad and Back on the PATCHES card. Returns true if the key was used.
     * Only presses are taken; releases still go to the game so nothing sticks.
     */
    public boolean patchesKey(KeyEvent e) {
        if (!patchesVisible()) return false;
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean first = e.getRepeatCount() == 0;
        int row = sel / 2, col = sel % 2;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min((NT - 1) / 2, row + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: col = 0; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: col = 1; break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) patchesPick(sel);
                navActive = true;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_MODE:
                if (first) closePatches();
                return true;
            default:
                // any other gamepad press is kept from the paused game
                return KeyEvent.isGamepadButton(code);
        }
        sel = Math.min(NT - 1, row * 2 + col);
        navActive = true;
        invalidate();
        return true;
    }

    // ----------------------------------------------------------------- input

    private void hit(float x, float y, boolean[] out) {
        if (menuOpen) return;
        float dx = x - dpadX, dy = y - dpadY;
        float dist = (float) Math.hypot(dx, dy);
        if (dist <= dpadR * 1.3f) {
            if (dist < dpadR * 0.18f) return;
            double deg = Math.toDegrees(Math.atan2(-dy, dx));
            if (deg < 0) deg += 360;
            int sector = (int) Math.floor((deg + 22.5) / 45.0) % 8;
            switch (sector) {
                case 0: out[RIGHT] = true; break;
                // With Hard Drop on, Up drops the piece, so up-diagonals count
                // as plain left/right to avoid accidental drops. With it off,
                // the D-pad is normal 8-way like the original game.
                case 1: out[RIGHT] = true; if (!hardDropOn) out[UP] = true; break;
                case 2: out[UP] = true; break;
                case 3: out[LEFT] = true; if (!hardDropOn) out[UP] = true; break;
                case 4: out[LEFT] = true; break;
                case 5: out[LEFT] = true; out[DOWN] = true; break;
                case 6: out[DOWN] = true; break;
                case 7: out[DOWN] = true; out[RIGHT] = true; break;
            }
            return;
        }
        float br = btnR * 1.35f;
        float da = (float) Math.hypot(x - aX, y - aY);
        float db = (float) Math.hypot(x - bX, y - bY);
        if (da <= br || db <= br) {
            if (da <= db) out[A] = true; else out[B] = true;
            return;
        }
        float slop = 10 * density;
        if (holdButtonShown()) {
            float hs = 6 * density;
            if (x >= holdBtnRect.left - hs && x <= holdBtnRect.right + hs
                    && y >= holdBtnRect.top - hs && y <= holdBtnRect.bottom + hs) {
                holdBtnHit = true;
                return;
            }
        }
        if (x >= selectRect.left - slop && x <= selectRect.right + slop
                && y >= selectRect.top - slop && y <= selectRect.bottom + slop) {
            out[SELECT] = true;
            return;
        }
        if (x >= startRect.left - slop && x <= startRect.right + slop
                && y >= startRect.top - slop && y <= startRect.bottom + slop) {
            out[START] = true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int page = menuPage();

        // PATCHES card: it takes every touch (tap outside closes it).
        if (page == 0) {
            if (action == MotionEvent.ACTION_DOWN) releaseAll();
            return patchesTouch(e);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            qTouched = SystemClock.uptimeMillis();
            if (gamepadMode) setGamepadMode(false);
        }
        if (quickTouch(e)) return true;
        if (controllerPresent) return true;     // with a controller only the quick buttons are touch-operated

        boolean[] now = new boolean[COUNT];
        holdBtnHit = false;
        if (action != MotionEvent.ACTION_CANCEL) {
            int skip = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    ? e.getActionIndex() : -1;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == skip) continue;
                hit(e.getX(i), e.getY(i), now);
            }
        }
        apply(now);
        if (holdBtnHit != holdBtnDown) {
            holdBtnDown = holdBtnHit;
            if (holdBtnDown) {
                nreq(REQ_HOLD);
                buzz();
            }
            invalidate();
        }
        return true;
    }

    private boolean holdButtonShown() {
        return nget(11) != 0;
    }

    /** L1 / R1 on a controller hold the piece while Hold is on and a game is
     *  being played. Returns true if the key was used. */
    public boolean holdKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_L1 && code != KeyEvent.KEYCODE_BUTTON_R1) return false;
        if (nget(11) == 0 || nget(19) == 0 || menuPage() >= 0) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) nreq(REQ_HOLD);
        return true;
    }

    private void apply(boolean[] now) {
        boolean changed = false;
        for (int i = 0; i < COUNT; i++) {
            if (now[i] == pressed[i]) continue;
            changed = true;
            pressed[i] = now[i];
            if (now[i]) {
                SDLActivity.onNativeKeyDown(KEYCODES[i]);
                buzz();
            } else {
                SDLActivity.onNativeKeyUp(KEYCODES[i]);
            }
        }
        if (changed) invalidate();
    }

    public void releaseAll() {
        apply(new boolean[COUNT]);
    }
}
