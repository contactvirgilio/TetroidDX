package org.tetrisdx.recomp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * First-run screen, the same as the DKC1/DKC2 ports: finds or asks for the
 * player's own Tetris DX ROM, checks it, keeps a copy inside the app, then
 * starts the game.
 */
public class SetupActivity extends Activity {

    /** SHA-256 of the ROM the code was generated from. Filled in by the build. */
    static final String EXPECTED_SHA256 = "@ROM_SHA256@";

    /** Folder in the device's main storage for the save, settings and a ROM to pick up. */
    static final String FOLDER = "TetroidDX";

    private static final int PICK_ROM = 1;
    private static final String PREFS = "setup";
    private static final String KEY_VERIFIED = "verified_sha256";

    private TextView status;

    static File romFile(Context c) {
        return new File(c.getFilesDir(), "rom.gbc");
    }

    static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    static File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER);
    }

    /**
     * Copies the ROM into the app if its SHA-256 matches. Returns null on
     * success, otherwise a message for the player.
     */
    static String install(Context c, InputStream in) {
        File tmp = new File(c.getFilesDir(), "rom.tmp");
        String sha;
        try (OutputStream out = new FileOutputStream(tmp)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 16L * 1024 * 1024) {
                    tmp.delete();
                    return "That file is too large to be the Tetris DX ROM.";
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            sha = hex(md.digest());
        } catch (Exception e) {
            tmp.delete();
            return "Could not read that file.";
        }
        if (!EXPECTED_SHA256.equals(sha)) {
            tmp.delete();
            return "This is not the Tetris DX (World) (SGB Enhanced) ROM this build "
                    + "was made from (checksum mismatch). Other versions will not work.";
        }
        File rom = romFile(c);
        rom.delete();
        if (!tmp.renameTo(rom)) {
            tmp.delete();
            return "Could not save the ROM inside the app.";
        }
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VERIFIED, sha).apply();
        return null;
    }

    /** With All files access, picks up a matching .gbc from the TetroidDX folder. */
    private boolean findInFolder() {
        if (!hasAllFilesAccess()) return false;
        File dir = publicDir();
        if (!dir.isDirectory()) dir.mkdirs();
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (!f.isFile() || !(name.endsWith(".gbc") || name.endsWith(".gb"))) continue;
            try (InputStream in = new FileInputStream(f)) {
                if (install(this, in) == null) return true;
            } catch (Exception e) {
                // try the next file
            }
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if ((romFile(this).isFile() && EXPECTED_SHA256.equals(prefs.getString(KEY_VERIFIED, "")))
                || findInFolder()) {
            startGame();
            return;
        }

        // Black screen, BPreplay title and button, body text in the system
        // sans-serif used by the in-game cards, outlined "Choose ROM" pill,
        // errors shown in red on the screen (never Android toasts).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);
        Typeface face;
        try {
            face = Typeface.createFromAsset(getAssets(), "BPreplayBold.otf");
        } catch (Throwable t) {
            face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }

        TextView title = new TextView(this);
        title.setText("Tetroid DX");
        title.setTextColor(Color.WHITE);
        title.setTypeface(face);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("\nThis app does not include the game. Choose your own copy of "
                + "Tetris DX (World) (SGB Enhanced), the .gbc file. It is checked and kept "
                + "inside the app.\n\n"
                + "Tip: with All files access turned on, a ROM placed in the " + FOLDER
                + " folder of your device's main storage is found automatically.\n");
        body.setTextColor(0xFFBDBDBD);
        body.setTypeface(Typeface.DEFAULT);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setGravity(Gravity.CENTER);
        root.addView(body);

        Button pick = new Button(this);
        pick.setText("Choose ROM");
        pick.setAllCaps(false);
        pick.setTypeface(face);
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

        status = new TextView(this);
        status.setTypeface(Typeface.DEFAULT);
        status.setTextColor(0xFFFF8A80);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        TextView credits = new TextView(this);
        credits.setText("Credits");
        credits.setTypeface(Typeface.DEFAULT);
        credits.setTextColor(0xFF9E9E9E);
        credits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        credits.setPaintFlags(credits.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        credits.setGravity(Gravity.CENTER);
        credits.setPadding(dp(16), dp(20), dp(16), dp(8));
        credits.setOnClickListener(v -> showCredits());
        root.addView(credits);

        setContentView(root);
    }

    /** Credits and licence notices, shown from the ROM screen. */
    static final String CREDITS =
            "Tetroid DX is an unofficial fan port. It contains no game data; you supply "
            + "your own ROM. Tetris DX is a trademark of its owners.\n\n"
            + "PATCHES\n"
            + "Hard Drop and Ghost Piece: tetrisdx-harddrop by lightbulb-sun (MIT), made "
            + "after a request by SpongeDan; ghost piece idea by szechp.\n"
            + "Don't Panic: DevEd.\n"
            + "Skip Celebrations: llakssz.\n"
            + "Korobeiniki (Original): after Tetris DX - Music Fix by Marc Robledo. "
            + "Korobeiniki (Plus): Last Stand, an original first panic song written for "
            + "Tetroid DX.\n"
            + "Hold, Preview and Lock Delay: idea and testing by CreeperCraftYT; written "
            + "for Tetroid DX.\n"
            + "SRS Rotation and Randomizer (7-Bag, 35-Bag): written for Tetroid DX from "
            + "tetris.wiki and the Hard Drop wiki. Random numbers: xorshift by John "
            + "Metcalf.\n"
            + "Guideline and Sega palettes: CreeperCraftYT. Amber Night: Tetroid DX.\n"
            + "Testing and ideas: CreeperCraftYT.\n\n"
            + "SOFTWARE\n"
            + "GB Recompiled by arcanite24 (MIT). SDL2 (zlib License). Dear ImGui by Omar "
            + "Cornut (MIT). Patches assembled with RGBDS.\n"
            + "BPreplay font, Copyright (c) 2004 George Triantafyllakos and Magenta, "
            + "SIL Open Font License 1.1.\n\n"
            + "Android app, menu and touch controls built with Claude (Anthropic) "
            + "together with the project owner.\n\n"
            + "MIT LICENSE (tetrisdx-harddrop: Copyright (c) 2023 lightbulb-sun; "
            + "GB Recompiled: Copyright (c) 2026 arcanite24; Dear ImGui: Copyright (c) "
            + "Omar Cornut)\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy "
            + "of this software and associated documentation files (the \"Software\"), to "
            + "deal in the Software without restriction, including without limitation the "
            + "rights to use, copy, modify, merge, publish, distribute, sublicense, and/or "
            + "sell copies of the Software, and to permit persons to whom the Software is "
            + "furnished to do so, subject to the following conditions:\n"
            + "The above copyright notice and this permission notice shall be included in "
            + "all copies or substantial portions of the Software.\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR "
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, "
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE "
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER "
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, "
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE "
            + "SOFTWARE.";

    private void showCredits() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        TextView t = new TextView(this);
        t.setText(CREDITS);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextColor(0xFFBDBDBD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(t);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Credits")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String err;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            err = (in == null) ? "Could not open that file." : install(this, in);
        } catch (Exception e) {
            err = "Could not read that file.";
        }
        if (err != null) {
            status.setText(err);
            return;
        }
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
