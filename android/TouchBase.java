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

/** Shared state and drawing helpers for the overlay (see TouchControls). */
abstract class TouchBase extends View {

    TouchBase(Context context) {
        super(context);
    }

    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3,
            A = 4, B = 5, SELECT = 6, START = 7, COUNT = 8;

    final boolean[] pressed = new boolean[COUNT];
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final Handler handler = new Handler(Looper.getMainLooper());
    float density;

    static int menuPage() {
        try {
            return TouchControls.nativeGet(7);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int nget(int id) {
        try {
            return TouchControls.nativeGet(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void nset(int id, int v) {
        try {
            TouchControls.nativeSet(id, v);
        } catch (Throwable ignored) {
        }
    }

    static void nreq(int bits) {
        try {
            TouchControls.nativeRequest(bits);
        } catch (Throwable ignored) {
        }
    }

    // Shapes in their spawn orientation, 4 x 2 cells ("#" = block), by piece
    // number (0 I, 1 T, 2 Z, 3 S, 4 J, 5 L, 6 O). DX shows T, J and L flat side
    // up; with SRS on they spawn flat side down.
    static final String[] SHAPES_DX = {
            "....####", "###..#..", "##...##.", ".##.##..", "###...#.", "###.#...", ".##..##."};
    static final String[] SHAPES_SRS = {
            "....####", ".#..###.", "##...##.", ".##.##..", "#...###.", "..#.###.", ".##..##."};
    float gameX, gameY, gameW, gameH;
    final RectF holdRect = new RectF();
    final RectF[] queueRect = {new RectF(), new RectF(), new RectF()};
    final RectF nextRect = new RectF();   // one box holding all three
    boolean landscape = false;
    int layoutW, layoutH, layoutStyle = -1;
    int lastHqSig = 0;
    final Bitmap[] pieceBmp = new Bitmap[7];
    final int[] pieceBuf = new int[32 * 16];
    long pieceBmpTime = 0;
    int pieceBmpSig = 0;
    final android.graphics.Rect bmpSrc = new android.graphics.Rect();
    final RectF bmpDst = new RectF();
    final Paint bmpPaint = new Paint();   // no filtering: sharp pixels

    /** Preview setting (native id 29): 0 off, 1-5 layout. In landscape, layout 1
     *  stacks three boxes down the right margin and 2-4 use one box there.
     *  Layout 5 draws inside the game screen, over the game's NEXT window, so it
     *  also works where there is no room around the game (RPC). */
    /** 4:3 screens (e.g. Retroid Pocket Classic): only Preview Off / In game make sense. */
    boolean isCompact() {
        float aspect = getHeight() > 0 ? (float) getWidth() / getHeight() : 0f;
        return aspect > 1.0f && aspect < 1.5f;   // 4:3 = 1.33, Retroid Pocket Classic 1240x1080 = 1.15
    }

    int previewStyle() {
        return Math.max(0, Math.min(5, nget(29)));
    }

    int sampleOr(int x, int y, int fallback) {
        int v = TouchControls.nativeSample(x, y);
        return v == 0 ? fallback : v;
    }

    void fillR(Canvas c, float l, float t, float r, float b, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRect(l, t, r, b, paint);
    }
}
