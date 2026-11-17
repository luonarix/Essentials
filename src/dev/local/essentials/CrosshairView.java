package dev.local.essentials;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draws the selected crosshair style in a small transparent overlay. */
public class CrosshairView extends View {

    private final float density;
    private int style = Crosshair.STYLE_CROSS;
    private int sizeDp = 18;

    private final Paint color = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CrosshairView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        color.setStyle(Paint.Style.STROKE);
        color.setStrokeCap(Paint.Cap.ROUND);
        color.setStrokeJoin(Paint.Join.ROUND);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setColor(0x99000000);
    }

    public void setStyle(int value) {
        style = value;
        invalidate();
    }

    public void setColor(int value) {
        color.setColor(value);
        invalidate();
    }

    public void setSizeDp(int value) {
        sizeDp = value;
        invalidate();
    }

    /** Width/height the overlay window needs for the current size. */
    public int windowSizePx() {
        return Math.round(dp(sizeDp) + dp(30));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float size = dp(sizeDp);
        float stroke = Math.max(dp(1.2f), size * 0.12f);
        color.setStrokeWidth(stroke);
        outline.setStrokeWidth(stroke + dp(2.2f));

        if (style == Crosshair.STYLE_DOT) {
            Paint.Style oldColor = color.getStyle();
            Paint.Style oldOutline = outline.getStyle();
            color.setStyle(Paint.Style.FILL);
            outline.setStyle(Paint.Style.FILL);
            float radius = Math.max(dp(1.6f), size * 0.24f);
            canvas.drawCircle(cx, cy, radius + dp(1.6f), outline);
            canvas.drawCircle(cx, cy, radius, color);
            color.setStyle(oldColor);
            outline.setStyle(oldOutline);
            return;
        }

        if (style == Crosshair.STYLE_RING) {
            float radius = Math.max(dp(3f), size / 2f);
            canvas.drawCircle(cx, cy, radius, outline);
            canvas.drawCircle(cx, cy, radius, color);
            float dot = Math.max(dp(1.2f), size * 0.14f);
            Paint.Style oldColor = color.getStyle();
            Paint.Style oldOutline = outline.getStyle();
            color.setStyle(Paint.Style.FILL);
            outline.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, dot + dp(1.4f), outline);
            canvas.drawCircle(cx, cy, dot, color);
            color.setStyle(oldColor);
            outline.setStyle(oldOutline);
            return;
        }

        // Cross: four arms with a small gap in the middle so the target stays visible.
        float gap = Math.max(dp(1.5f), size * 0.11f);
        float arm = Math.max(dp(4f), size / 2f);
        drawArm(canvas, cx - arm, cy, cx - gap, cy);
        drawArm(canvas, cx + gap, cy, cx + arm, cy);
        drawArm(canvas, cx, cy - arm, cx, cy - gap);
        drawArm(canvas, cx, cy + gap, cx, cy + arm);

        float dot = Math.max(dp(0.9f), size * 0.07f);
        Paint.Style oldColor = color.getStyle();
        Paint.Style oldOutline = outline.getStyle();
        color.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, dot + dp(1.2f), outline);
        canvas.drawCircle(cx, cy, dot, color);
        color.setStyle(oldColor);
        outline.setStyle(oldOutline);
    }

    private void drawArm(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, outline);
        canvas.drawLine(x1, y1, x2, y2, color);
    }

    private float dp(float v) {
        return v * density;
    }
}
