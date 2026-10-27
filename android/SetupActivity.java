package org.tetrisdx.recomp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Asks for the player's own Tetris DX ROM once, checks it, then starts the game. */
public class SetupActivity extends Activity {

    /** SHA-256 of the ROM the code was generated from. Filled in by the build. */
    static final String EXPECTED_SHA256 = "@ROM_SHA256@";

    private static final int PICK_ROM = 1;
    private static final String PREFS = "setup";
    private static final String KEY_VERIFIED = "verified_sha256";

    static File romFile(Activity a) {
        return new File(a.getFilesDir(), "rom.gbc");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (romFile(this).isFile() && EXPECTED_SHA256.equals(prefs.getString(KEY_VERIFIED, ""))) {
            startGame();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Tetroid DX");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Pick your Tetris DX ROM (.gbc).\nIt is checked once and copied into the app.");
        info.setTextColor(0xFFBDBDBD);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(16), 0, dp(24));
        root.addView(info);

        Button pick = new Button(this);
        pick.setText("Choose ROM");
        pick.setAllCaps(false);
        pick.setTextColor(Color.WHITE);
        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(dp(2), 0xFF9E9E9E);
        bg.setCornerRadius(dp(24));
        pick.setBackground(bg);
        pick.setPadding(dp(32), dp(12), dp(32), dp(12));
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK_ROM);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(pick, lp);

        setContentView(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        File tmp = new File(getFilesDir(), "rom.tmp");
        String sha;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("cannot open");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 16L * 1024 * 1024) throw new Exception("file too large");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            sha = hex(md.digest());
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(this, "Could not read that file.", Toast.LENGTH_LONG).show();
            return;
        }

        if (!EXPECTED_SHA256.equals(sha)) {
            tmp.delete();
            Toast.makeText(this,
                    "That ROM doesn't match the version this build was made from.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        File rom = romFile(this);
        rom.delete();
        if (!tmp.renameTo(rom)) {
            tmp.delete();
            Toast.makeText(this, "Could not save the ROM.", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VERIFIED, sha).apply();
        Toast.makeText(this, "ROM verified.", Toast.LENGTH_SHORT).show();
        startGame();
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
