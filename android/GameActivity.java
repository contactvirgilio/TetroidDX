package org.tetrisdx.recomp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class GameActivity extends SDLActivity {

    private TouchControls touch;
    private long lastPaletteKey = 0;

    /** True if a physical game controller (including a handheld's built-in one) is connected. */
    private static boolean controllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    private void refreshController() {
        if (touch != null) touch.setControllerPresent(controllerConnected());
    }

    private void paletteNext() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPaletteKey < 250) return;     // a trigger can arrive as key and axis
        lastPaletteKey = now;
        if (touch != null) touch.cyclePalette();
    }
    /** Where the game keeps its save and settings (null = inside the app). */
    private String saveDir = null;
    private boolean askForAllFilesAccess = false;

    /** Copies a file or folder, never overwriting anything already there. */
    private static void copyTree(File src, File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) return;
                String[] names = src.list();
                if (names != null) {
                    for (String n : names) copyTree(new File(src, n), new File(dst, n));
                }
                return;
            }
            if (dst.exists()) return;
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Same as the DKC ports: with All files access, the save and settings live
     * in a TetroidDX folder in the device's main storage. The folder is created
     * here, and anything saved inside the app before is copied in once (the
     * ROM itself stays private). Without access, it is asked for once
     * (Android 11+) and the game keeps saving inside the app meanwhile.
     */
    private void chooseSaveFolder() {
        SharedPreferences prefs = getSharedPreferences("game", MODE_PRIVATE);
        if (SetupActivity.hasAllFilesAccess()) {
            File pub = SetupActivity.publicDir();
            if (pub.isDirectory() || pub.mkdirs()) {
                if (!prefs.getBoolean("migrated", false)) {
                    File priv = getFilesDir();
                    String[] names = priv.list();
                    if (names != null) {
                        for (String n : names) {
                            if (n.equals("rom.gbc") || n.equals("rom.tmp")) continue;
                            copyTree(new File(priv, n), new File(pub, n));
                        }
                    }
                    prefs.edit().putBoolean("migrated", true).apply();
                }
                saveDir = pub.getAbsolutePath();
            }
        } else if (Build.VERSION.SDK_INT >= 30 && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply();
            askForAllFilesAccess = true;
        }
    }

    /** Passed to the game's main(): where to keep the save and settings. */
    @Override
    protected String[] getArguments() {
        if (saveDir != null && new File(saveDir).isDirectory()) {
            return new String[] {"--save-dir", saveDir};
        }
        return new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        chooseSaveFolder();
        super.onCreate(savedInstanceState);
        if (askForAllFilesAccess) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
            }
        }
        if (!SetupActivity.romFile(this).isFile()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        if (mLayout != null) {
            touch = new TouchControls(this);
            mLayout.addView(touch, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshController();
            InputManager im = (InputManager) getSystemService(INPUT_SERVICE);
            if (im != null) {
                im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                    @Override public void onInputDeviceAdded(int id) { refreshController(); }
                    @Override public void onInputDeviceRemoved(int id) { refreshController(); }
                    @Override public void onInputDeviceChanged(int id) { refreshController(); }
                }, null);
            }
        }
    }

    /**
     * SDL locks the orientation for a fullscreen window. Always follow the
     * device instead, so it works on handheld devices (either way up) and on the
     * Retroid Pocket Classic's portrait screen.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
    }

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private int padX = 0, padY = 0;   // last direction from the hat / left stick: -1, 0, 1

    /** Some handhelds send the D-pad as a hat axis, and the left stick is an axis: while
     *  the PATCHES card is open both move the selection like D-pad keys. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (touch != null && (ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && ev.getAction() == MotionEvent.ACTION_MOVE && touch.patchesVisible()) {
            float x = ev.getAxisValue(MotionEvent.AXIS_HAT_X), y = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (x == 0f) x = ev.getAxisValue(MotionEvent.AXIS_X);
            if (y == 0f) y = ev.getAxisValue(MotionEvent.AXIS_Y);
            int nx = x > 0.6f ? 1 : (x < -0.6f ? -1 : 0), ny = y > 0.6f ? 1 : (y < -0.6f ? -1 : 0);
            if (nx != padX && nx != 0) touch.patchesKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                    nx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT));
            if (ny != padY && ny != 0) touch.patchesKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                    ny > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP));
            padX = nx;
            padY = ny;
            return true;
        }
        padX = 0;
        padY = 0;
        if (touch != null && (ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && ev.getAction() == MotionEvent.ACTION_MOVE
                && Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.6f) {
            paletteNext();
        }
        if (touch != null && isGamepad(ev.getSource())) {
            touch.setGamepadMode(true);
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /** A physical gamepad press hides the touch buttons; touching the screen brings them back. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (touch != null && isGamepad(event.getSource())
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            touch.setGamepadMode(true);
        }
        // The PATCHES card takes the D-pad, A, B, Guide and Back while it is open.
        if (touch != null && touch.patchesKey(event)) return true;
        // L2 changes the palette; R3 or Mode opens the menu (the touch Menu button is hidden).
        if (touch != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && isGamepad(event.getSource())) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_BUTTON_L2) { paletteNext(); return true; }
            if (k == KeyEvent.KEYCODE_BUTTON_THUMBR || k == KeyEvent.KEYCODE_BUTTON_MODE) {
                touch.openMenu();
                return true;
            }
        }
        if (touch != null && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) return true;
        // L1 / R1 hold the piece while Hold is on.
        if (touch != null && touch.holdKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        if (touch != null) touch.releaseAll();
        super.onPause();
    }
}
