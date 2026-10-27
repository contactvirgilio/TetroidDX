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
 * Game Boy touch controls drawn over the game. Presses are sent to SDL as
 * key events, which the runtime's default keyboard bindings map to the
 * Game Boy buttons (arrows, Z = A, X = B, Backspace = Select, Enter = Start).
 * The Menu button sends Escape, which opens or closes the runtime menu.
 */
public class TouchControls extends View {

    static native boolean nativeMenuOpen();
    static native boolean nativeHardDropOn();

    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3,
            A = 4, B = 5, SELECT = 6, START = 7, MENU = 8, COUNT = 9;

    private static final int[] KEYCODES = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
    };

    private static final int OUTLINE = 0xFF8A8A8A;
    private static final int FILL = 0x808A8A8A;
    private static final int LABEL = 0xFFBDBDBD;

    private final boolean[] pressed = new boolean[COUNT];
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    private boolean menuOpen = false;
    private boolean hardDropOn = false;
    private boolean gamepadMode = false;

    // Layout, recomputed in onSizeChanged.
    private float dpadX, dpadY, dpadR;
    private float aX, aY, bX, bY, btnR;
    private final RectF selectRect = new RectF(), startRect = new RectF(), menuRect = new RectF();
    private final Path dpadPath = new Path();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean open = false;
            try {
                open = nativeMenuOpen();
                hardDropOn = nativeHardDropOn();
            } catch (UnsatisfiedLinkError ignored) {
            }
            if (open != menuOpen) {
                menuOpen = open;
                if (open) releaseGameButtons();
                invalidate();
            }
            handler.postDelayed(this, 150);
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

    public void setGamepadMode(boolean on) {
        if (gamepadMode == on) return;
        gamepadMode = on;
        if (on) releaseAll();
        invalidate();
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

        float menuW = 72 * dp, menuH = 32 * dp;

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
            float my = (gy >= menuH + 8 * dp) ? (gy - menuH) / 2f : 8 * dp;
            menuRect.set(w / 2f - menuW / 2f, my, w / 2f + menuW / 2f, my + menuH);
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
            menuRect.set(dpadX - menuW / 2f, 16 * dp, dpadX + menuW / 2f, 16 * dp + menuH);
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
    }

    // --------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        drawPill(c);
        if (gamepadMode) return;

        float corner = 10 * density;
        text.setTextSize(14 * density);
        int menuAlpha = menuOpen ? 110 : 255;
        stroke.setAlpha(menuAlpha);
        text.setAlpha(menuAlpha);
        if (pressed[MENU]) c.drawRoundRect(menuRect, corner, corner, fill);
        c.drawRoundRect(menuRect, corner, corner, stroke);
        drawLabel(c, "Menu", menuRect.centerX(), menuRect.centerY());
        stroke.setAlpha(255);
        text.setAlpha(255);

        if (menuOpen) return;

        // D-pad: light only the pressed arm(s).
        float arm = dpadR * 0.33f;
        if (pressed[UP]) c.drawRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY - arm, fill);
        if (pressed[DOWN]) c.drawRect(dpadX - arm, dpadY + arm, dpadX + arm, dpadY + dpadR, fill);
        if (pressed[LEFT]) c.drawRect(dpadX - dpadR, dpadY - arm, dpadX - arm, dpadY + arm, fill);
        if (pressed[RIGHT]) c.drawRect(dpadX + arm, dpadY - arm, dpadX + dpadR, dpadY + arm, fill);
        c.drawPath(dpadPath, stroke);

        text.setTextSize(btnR * 0.75f);
        if (pressed[A]) c.drawCircle(aX, aY, btnR, fill);
        c.drawCircle(aX, aY, btnR, stroke);
        drawLabel(c, "A", aX, aY);
        if (pressed[B]) c.drawCircle(bX, bY, btnR, fill);
        c.drawCircle(bX, bY, btnR, stroke);
        drawLabel(c, "B", bX, bY);

        float pr = selectRect.height() / 2f;
        text.setTextSize(Math.max(10 * density, selectRect.height() * 0.42f));
        if (pressed[SELECT]) c.drawRoundRect(selectRect, pr, pr, fill);
        c.drawRoundRect(selectRect, pr, pr, stroke);
        drawLabel(c, "SELECT", selectRect.centerX(), selectRect.centerY());
        if (pressed[START]) c.drawRoundRect(startRect, pr, pr, fill);
        c.drawRoundRect(startRect, pr, pr, stroke);
        drawLabel(c, "START", startRect.centerX(), startRect.centerY());
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

    private void drawLabel(Canvas c, String s, float x, float y) {
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
    }

    // ----------------------------------------------------------------- input

    private boolean onMenu(float x, float y) {
        float slop = 8 * density;
        return x >= menuRect.left - slop && x <= menuRect.right + slop
                && y >= menuRect.top - slop && y <= menuRect.bottom + slop;
    }

    private void hit(float x, float y, boolean[] out) {
        if (onMenu(x, y)) {
            out[MENU] = true;
            return;
        }
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

        if (action == MotionEvent.ACTION_DOWN) {
            if (gamepadMode) {
                setGamepadMode(false);
            }
            // While the runtime menu is open, let touches through to it.
            if (menuOpen && !onMenu(e.getX(), e.getY())) {
                return false;
            }
        }

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
            if (i == MENU) {
                if (now[i]) {
                    SDLActivity.onNativeKeyDown(KEYCODES[MENU]);
                    SDLActivity.onNativeKeyUp(KEYCODES[MENU]);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                continue;
            }
            if (now[i]) {
                SDLActivity.onNativeKeyDown(KEYCODES[i]);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else {
                SDLActivity.onNativeKeyUp(KEYCODES[i]);
            }
        }
        if (changed) invalidate();
    }

    private void releaseGameButtons() {
        boolean[] now = new boolean[COUNT];
        now[MENU] = pressed[MENU];
        apply(now);
    }

    public void releaseAll() {
        apply(new boolean[COUNT]);
    }
}
