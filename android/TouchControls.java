package org.tetrisdx.recomp;

import android.content.Context;
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
 *   Menu shortcut, Select + D-pad Down, Guide or Android Back. The game is
 *   paused while it is open;
 * - the teal confirmation pill.
 */
public class TouchControls extends View {

    static native boolean nativeMenuOpen();
    static native boolean nativeHardDropOn();
    /** ids: 0 Hard Drop, 1 Ghost, 2 Don't Panic, 3 Skip Celebrations, 4 Palette,
     *  5 Scanlines, 6 Mute, 7 menu page (-1 closed, 0 PATCHES card, 1 Controls, 2 Credits),
     *  8 Korobeiniki. */
    static native int nativeGet(int id);
    static native void nativeSet(int id, int value);
    /** bits: 1 open the PATCHES card, 2 close, 4 Controls page, 8 Credits page. */
    static native void nativeRequest(int bits);

    private static final int REQ_OPEN = 1, REQ_CLOSE = 2;

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
    private static final String[] PALETTE_NAMES = {"Original", "GBC LCD", "Pastel", "Swamp", "Sunset"};

    private final boolean[] pressed = new boolean[COUNT];
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    // Teal confirmation pill (Cannoli style), shown by native code via pill().
    private static TouchControls instance;
    private final Paint pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private String pillMessage;
    private long pillStart;
    private static final long PILL_MS = 2200, PILL_FADE_MS = 300;

    private boolean menuOpen = false;     // any menu page (card or ImGui list)
    private boolean hardDropOn = false;
    private boolean gamepadMode = false;
    private int lastPage = -1;

    // Layout, recomputed in onSizeChanged.
    private float dpadX, dpadY, dpadR;
    private float aX, aY, bX, bY, btnR;
    private final RectF selectRect = new RectF(), startRect = new RectF();
    private final Path dpadPath = new Path();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            int page = menuPage();
            try {
                hardDropOn = nativeHardDropOn();
            } catch (Throwable ignored) {
            }
            if (page != lastPage) {
                if (page == 0 && lastPage != 0) openedCard();
                if (page >= 0 && lastPage < 0) releaseAll();
                lastPage = page;
                menuOpen = page >= 0;
                invalidate();
            }
            handler.postDelayed(this, 120);
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
        Typeface face;
        try {
            face = Typeface.createFromAsset(context.getAssets(), "BPreplayBold.otf");
        } catch (RuntimeException e) {
            face = Typeface.create("sans-serif-medium", Typeface.BOLD);
        }
        text.setTypeface(face);
        pillFill.setColor(0xFF00897B);
        pillText.setColor(0xFFFFFFFF);
        pillText.setTextAlign(Paint.Align.CENTER);
        pillText.setTypeface(face);
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
        }

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
            drawPill(c);
            return;
        }
        if (page >= 0) {          // an ImGui list page (Controls, Credits): nothing on top
            drawPill(c);
            return;
        }
        if (!gamepadMode) drawPad(c);
        drawQuick(c);
        drawCard(c);
        drawPill(c);
        // keep the fade and the cards moving
        if (quickFading() || cardTitle != null || pillMessage != null) postInvalidateOnAnimation();
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
    }

    private void drawLabel(Canvas c, String s, float x, float y) {
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
    }

    /** Called from native code (JNI) on any thread. */
    public static void pill(String message) {
        final TouchControls v = instance;
        if (v == null || message == null) return;
        v.post(() -> {
            v.pillMessage = message;
            v.pillStart = SystemClock.uptimeMillis();
            v.invalidate();
        });
    }

    private void drawPill(Canvas c) {
        if (pillMessage == null) return;
        long t = SystemClock.uptimeMillis() - pillStart;
        if (t >= PILL_MS) {
            pillMessage = null;
            return;
        }
        float alpha = t > PILL_MS - PILL_FADE_MS ? (PILL_MS - t) / (float) PILL_FADE_MS : 1f;
        float v = Math.min(getWidth(), getHeight()) / 7f;
        pillText.setTextSize(0.30f * v);
        float tw = pillText.measureText(pillMessage);
        float h = 0.42f * v, pad = 0.22f * v;
        float cx = getWidth() / 2f, cy = getHeight() - 0.62f * v;
        pillRect.set(cx - tw / 2f - pad, cy - h / 2f, cx + tw / 2f + pad, cy + h / 2f);
        int a = Math.round(alpha * 255);
        pillFill.setAlpha(a);
        pillText.setAlpha(a);
        c.drawRoundRect(pillRect, h / 2f, h / 2f, pillFill);
        Paint.FontMetrics fm = pillText.getFontMetrics();
        c.drawText(pillMessage, cx, cy - (fm.ascent + fm.descent) / 2f, pillText);
        postInvalidateOnAnimation();
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
                int p = (nget(4) + 1) % PALETTE_NAMES.length;
                nset(4, p);
                showCard("PALETTE", PALETTE_NAMES[p], p, PALETTE_NAMES.length);
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

    private static final String[] PATCH_NAMES = {"Hard Drop", "Ghost Piece", "Don't Panic", "Skip Celebrations", "Korobeiniki"};
    private static final int NT = 5;  // tiles: two columns, the last one full width
    private final RectF patchesCard = new RectF();
    private final RectF[] tileRect = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private int sel = 0;              // tile 0-4
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
            case 3: return nget(3) != 0 ? "On" : "Off";
            default: return nget(8) != 0 ? "On" : "Off";
        }
    }

    private void patchesPick(int i) {
        switch (i) {
            case 0: nset(0, nget(0) != 0 ? 0 : 1); break;
            case 1: nset(1, (nget(1) + 1) % 3); break;
            case 2: nset(2, nget(2) != 0 ? 0 : 1); break;
            case 3: nset(3, nget(3) != 0 ? 0 : 1); break;
            case 4: nset(8, nget(8) != 0 ? 0 : 1); break;
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
            default: // skip celebrations: skip-to-end
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
        float tw = (cw - 2f * pad - gap) / 2f, th = tw * 0.56f;
        float ch = pad + titleH + 3f * th + 2f * gap + hintH;
        if (ch > H * 0.94f) {   // short landscape screens: shrink the tiles to fit
            th = (H * 0.94f - pad - titleH - 2f * gap - hintH) / 3f;
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
        // ImGui list page (Controls, Credits): every touch goes to the game's menu.
        if (page > 0) {
            if (action == MotionEvent.ACTION_DOWN) releaseAll();
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            qTouched = SystemClock.uptimeMillis();
            if (gamepadMode) setGamepadMode(false);
        }
        if (quickTouch(e)) return true;

        boolean[] now = new boolean[COUNT];
        if (action != MotionEvent.ACTION_CANCEL) {
            int skip = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    ? e.getActionIndex() : -1;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == skip) continue;
                hit(e.getX(i), e.getY(i), now);
            }
        }
        apply(now);
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
