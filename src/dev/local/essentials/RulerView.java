package dev.local.essentials;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen measuring grid. Draws a 10dp / 50dp grid (or 50px / 250px) with
 * edge labels and a crosshair that follows the finger, reporting the exact
 * position in both dp and px.
 */
public class RulerView extends View {

    private final float density;
    private boolean pixels;

    private final Paint minor = new Paint();
    private final Paint major = new Paint();
    private final Paint labels = new Paint();
    private final Paint cross = new Paint();
    private final Paint chip = new Paint();
    private final Paint chipText = new Paint();

    private float markX = -1;
    private float markY = -1;

    public RulerView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;

        minor.setColor(0x226E9BFF);
        minor.setStrokeWidth(1f);

        major.setColor(0x556E9BFF);
        major.setStrokeWidth(Math.max(1f, density * 0.7f));

        labels.setColor(0xCC9FB4E8);
        labels.setTextSize(9f * c.getResources().getDisplayMetrics().scaledDensity);
        labels.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        labels.setAntiAlias(true);

        cross.setColor(0xFF6E9BFF);
        cross.setStrokeWidth(Math.max(1f, density));
        cross.setAntiAlias(true);

        chip.setColor(0xE610141B);
        chip.setAntiAlias(true);

        chipText.setColor(0xFFFFFFFF);
        chipText.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
        chipText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        chipText.setAntiAlias(true);
    }

    public void setPixels(boolean on) {
        pixels = on;
        invalidate();
    }

    public boolean isPixels() {
        return pixels;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        float stepMinor = pixels ? 50f : 10f * density;
        float stepMajor = pixels ? 250f : 50f * density;

        for (float x = 0; x <= w; x += stepMinor) {
            boolean isMajor = Math.abs(x % stepMajor) < 0.5f
                    || Math.abs(x % stepMajor - stepMajor) < 0.5f;
            c.drawLine(x, 0, x, h, isMajor ? major : minor);
        }
        for (float y = 0; y <= h; y += stepMinor) {
            boolean isMajor = Math.abs(y % stepMajor) < 0.5f
                    || Math.abs(y % stepMajor - stepMajor) < 0.5f;
            c.drawLine(0, y, w, y, isMajor ? major : minor);
        }

        // Edge labels every major step, in the active unit.
        for (float x = stepMajor; x < w - stepMajor * 0.4f; x += stepMajor) {
            c.drawText(value(x), x + 3f, 12f, labels);
        }
        for (float y = stepMajor; y < h - stepMajor * 0.4f; y += stepMajor) {
            c.drawText(value(y), 3f, y - 4f, labels);
        }

        if (markX >= 0 && markY >= 0) {
            c.drawLine(markX, 0, markX, h, cross);
            c.drawLine(0, markY, w, markY, cross);
            c.drawCircle(markX, markY, dp(7), cross);

            String label = value(markX) + " · " + value(markY)
                    + "   (" + Math.round(markX) + "px · " + Math.round(markY) + "px)";
            float tw = chipText.measureText(label);
            float left = Math.min(Math.max(dp(8), markX + dp(14)),
                    w - tw - dp(24));
            float top = Math.min(Math.max(dp(8), markY - dp(38)), h - dp(40));
            c.drawRoundRect(left, top, left + tw + dp(20), top + dp(28),
                    dp(9), dp(9), chip);
            c.drawText(label, left + dp(10), top + dp(19), chipText);
        }
    }

    private String value(float px) {
        if (pixels) return Math.round(px) + "px";
        return String.format(java.util.Locale.US, "%.0fdp", px / density);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                markX = e.getX();
                markY = e.getY();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private float dp(float v) {
        return v * density;
    }
}
