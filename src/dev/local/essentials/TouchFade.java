package dev.local.essentials;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Pure-SDK touch ripple: a translucent white circle that grows from the exact
 * finger position and fades to zero. No AndroidX / Material dependency.
 *
 * <p>The effect lives in a {@link Drawable} added to the host view's
 * {@link View#getOverlay() overlay}, so it paints above the button content and
 * the original OnClickListener / long-press / scroll handling are untouched.
 * When the button background is a rounded GradientDrawable the host is clipped
 * to its outline, which keeps the circle inside the accent frame.</p>
 */
final class TouchFade extends Drawable implements ValueAnimator.AnimatorUpdateListener {

    /** Peak white alpha: 0x55 = 85/255, i.e. #55FFFFFF. */
    private static final int PEAK_ALPHA = 0x55;
    private static final int DURATION_MS = 310;

    private final View host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator anim;
    private float cx;
    private float cy;
    private float targetRadius;
    private float radius;
    private float progress;

    private TouchFade(View host) {
        this.host = host;
    }

    /**
     * Starts a ripple on {@code v} at the given view-local coordinates. Safe to
     * call on every ACTION_DOWN; the helper is created once and reused.
     */
    static void play(View v, float x, float y) {
        if (v == null) return;
        Object tag = v.getTag(R.id.tag_touch_fade);
        TouchFade fade;
        if (tag instanceof TouchFade) {
            fade = (TouchFade) tag;
        } else {
            fade = new TouchFade(v);
            v.setTag(R.id.tag_touch_fade, fade);
            v.getOverlay().add(fade);
            clipToHostOutline(v);
        }
        fade.fire(x, y);
    }

    /** Clip to the rounded frame of the button when its background has one. */
    private static void clipToHostOutline(View v) {
        Drawable bg = v.getBackground();
        if (bg == null) return;
        Outline outline = new Outline();
        bg.getOutline(outline);
        if (outline.isEmpty()) return;
        v.setClipToOutline(true);
    }

    private void fire(float x, float y) {
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;

        cx = Math.max(0f, Math.min(w, x));
        cy = Math.max(0f, Math.min(h, y));

        // Radius that reaches the farthest corner, so the circle covers the
        // whole button no matter where the finger landed.
        float dx = Math.max(cx, w - cx);
        float dy = Math.max(cy, h - cy);
        targetRadius = (float) Math.sqrt(dx * dx + dy * dy);

        if (anim != null) anim.cancel();
        progress = 0f;
        radius = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator(2.2f));
        anim.addUpdateListener(this);
        anim.start();
    }

    public void onAnimationUpdate(ValueAnimator a) {
        progress = (Float) a.getAnimatedValue();
        radius = targetRadius * progress;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        if (radius <= 0f) return;
        int alpha = (int) (PEAK_ALPHA * (1f - progress));
        if (alpha <= 0) return;
        paint.setColor(Color.argb(alpha, 255, 255, 255));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
