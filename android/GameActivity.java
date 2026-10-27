package org.tetrisdx.recomp;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

public class GameActivity extends SDLActivity {

    private TouchControls touch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SetupActivity.romFile(this).isFile()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        if (mLayout != null) {
            touch = new TouchControls(this);
            mLayout.addView(touch, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * SDL locks the orientation for a fullscreen window. Always follow the
     * device instead, so it works on phones (either way up) and on the
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

    /** A physical gamepad press hides the touch buttons; touching the screen brings them back. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (touch != null && isGamepad(event.getSource())
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            touch.setGamepadMode(true);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (touch != null && isGamepad(event.getSource())) {
            touch.setGamepadMode(true);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        if (touch != null) touch.releaseAll();
        super.onPause();
    }
}
