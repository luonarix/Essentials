package dev.local.essentials;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent settings for the floating crosshair overlay. */
final class Crosshair {

    static final String PREF = "crosshair";
    static final int STYLE_DOT = 0;
    static final int STYLE_CROSS = 1;
    static final int STYLE_RING = 2;

    /** Colours offered in the settings tab. */
    static final int[] COLORS = {
            0xFF6E9BFF, 0xFFFF4D5E, 0xFF4ED27A, 0xFF27D3E0,
            0xFFFFD34D, 0xFFFFFFFF, 0xFFFF6AD5
    };

    private Crosshair() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
    }

    static int style(Context c) {
        return prefs(c).getInt("style", STYLE_CROSS);
    }

    static void setStyle(Context c, int style) {
        prefs(c).edit().putInt("style", style).apply();
    }

    static int color(Context c) {
        return prefs(c).getInt("color", COLORS[0]);
    }

    static void setColor(Context c, int color) {
        prefs(c).edit().putInt("color", color).apply();
    }

    static int sizeDp(Context c) {
        return prefs(c).getInt("size", 18);
    }

    static void setSizeDp(Context c, int dp) {
        prefs(c).edit().putInt("size", dp).apply();
    }

    static int alphaPercent(Context c) {
        return prefs(c).getInt("alpha", 85);
    }

    static void setAlphaPercent(Context c, int percent) {
        prefs(c).edit().putInt("alpha", percent).apply();
    }

    static int offsetX(Context c) {
        return prefs(c).getInt("ox", 0);
    }

    static int offsetY(Context c) {
        return prefs(c).getInt("oy", 0);
    }

    static void setOffset(Context c, int x, int y) {
        prefs(c).edit().putInt("ox", x).putInt("oy", y).apply();
    }
}
