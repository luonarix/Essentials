package dev.local.essentials;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny styling helpers so the activities stay readable. */
final class Ui {

    static final int BG = 0xFF0E1116;
    static final int SURFACE = 0xFF171C24;
    static final int SURFACE_HI = 0xFF1E2530;
    static final int TEXT = 0xFFE9EEF7;
    static final int MUTED = 0xFF8B94A7;
    static final int ACCENT = 0xFF6E9BFF;
    static final int ACCENT_SOFT = 0xFF1D2A44;
    /** 1dp accent frame drawn around every card. */
    static final int FRAME = 0x596E9BFF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable bg(int color, Context c, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    /** Filled card with the standard 1dp accent frame. */
    static GradientDrawable framed(int color, Context c, float radius) {
        return bg(color, FRAME, c, radius, 1);
    }

    static GradientDrawable bg(int color, int strokeColor, Context c, float radius, float stroke) {
        GradientDrawable d = bg(color, c, radius);
        d.setStroke(Math.max(1, dp(c, stroke)), strokeColor);
        return d;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** wrap_content / wrap_content */
    static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams lpw(float weight, int h) {
        return new LinearLayout.LayoutParams(0, h, weight);
    }

    /** Child of a vertical LinearLayout that fills the remaining height. */
    static LinearLayout.LayoutParams fill(float weight) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
    }

    static void margin(Context c, View v, float l, float t, float r, float b) {
        if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
            v.setLayoutParams(p);
        }
    }

    // ------------------------------------------------------------ motion

    /** Distance every "rise" animation travels, in dp. */
    static final float RISE_DP = 14f;

    /** Single element: fade in while sliding up a little. */
    static void rise(View v, int delay) {
        rise(v, delay, 260);
    }

    static void rise(View v, int delay, int duration) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), RISE_DP));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
    }

    /**
     * Adds every direct child in a gentle cascade. Long lists are compressed so
     * the whole entrance never takes much more than a third of a second.
     */
    static void stagger(ViewGroup parent, int startDelay, int step) {
        if (parent == null) return;
        int n = parent.getChildCount();
        if (n == 0) return;
        if (n > 12) step = Math.max(8, 300 / n);
        step = Math.max(0, step);
        for (int i = 0; i < n; i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            rise(c, startDelay + i * step, 250);
        }
    }

    /**
     * Touch feedback for a button: a white radial ripple from the exact finger
     * position plus a soft press-in scale. Returns false from onTouch, so taps,
     * long-presses and scrolling all keep working.
     */
    static void press(View v) {
        press(v, 0.96f);
    }

    static void press(final View v, final float scale) {
        touch(v, scale);
    }

    /** Ripple only, for controls that should not change size (e.g. Spinners). */
    static void ripple(View v) {
        touch(v, 0f);
    }

    private static void touch(final View v, final float scale) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        TouchFade.play(view, e.getX(), e.getY());
                        if (scale > 0f) {
                            view.animate().cancel();
                            view.animate().scaleX(scale).scaleY(scale)
                                    .setDuration(90).start();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (scale > 0f) {
                            view.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(150)
                                    .setInterpolator(new DecelerateInterpolator(2f))
                                    .start();
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** Quick pop used when a value or toggle changes. */
    static void pop(View v) {
        if (v == null) return;
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90)
                .withEndAction(new Runnable() {
                    public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }).start();
    }
}
