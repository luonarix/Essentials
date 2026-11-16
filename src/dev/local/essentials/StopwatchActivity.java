package dev.local.essentials;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class StopwatchActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long base;
    private long accumulated;
    private boolean running;
    private int lapNo;

    private TextView display;
    private TextView primary;
    private LinearLayout laps;

    private final Runnable ticker = new Runnable() {
        public void run() {
            display.setText(fmt(elapsed()));
            if (running) handler.postDelayed(this, 31);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 8));

        TextView back = Ui.text(this, "\u2039", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.press(back, 0.88f);
        header.addView(Ui.text(this, "Bấm giờ", 22, Ui.TEXT, true));
        root.addView(header);

        display = Ui.text(this, fmt(0), 58, Ui.TEXT, true);
        display.setGravity(Gravity.CENTER);
        display.setTypeface(android.graphics.Typeface.MONOSPACE);
        display.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams dp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.topMargin = Ui.dp(this, 34);
        root.addView(display, dp);

        TextView hint = Ui.text(this, "phút : giây . phần trăm giây", 12, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        LinearLayout buttons = Ui.row(this);
        LinearLayout.LayoutParams bp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 54));
        bp.topMargin = Ui.dp(this, 36);
        bp.leftMargin = Ui.dp(this, 18);
        bp.rightMargin = Ui.dp(this, 18);

        primary = Ui.text(this, "Bắt đầu", 16, 0xFF0B1020, true);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(Ui.bg(Ui.ACCENT, this, 16));
        primary.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggle();
            }
        });
        buttons.addView(primary, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        Ui.press(primary);

        TextView reset = Ui.text(this, "Đặt lại", 16, Ui.TEXT, false);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reset();
            }
        });
        LinearLayout.LayoutParams rp = Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = Ui.dp(this, 10);
        buttons.addView(reset, rp);
        Ui.press(reset);

        root.addView(buttons, bp);

        TextView lap = Ui.text(this, "Ghi lap", 15, Ui.TEXT, false);
        lap.setGravity(Gravity.CENTER);
        lap.setBackground(Ui.framed(Ui.SURFACE, this, 14));
        lap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addLap();
            }
        });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = Ui.dp(this, 18);
        lp.rightMargin = Ui.dp(this, 18);
        root.addView(lap, lp);
        Ui.press(lap);

        ScrollView sv = new ScrollView(this);
        laps = Ui.col(this);
        laps.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        sv.addView(laps, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, Ui.fill(1f));

        setContentView(root);

        // entrance: header, then the time read-out pops in, then the controls
        Ui.rise(header, 20, 200);
        display.setScaleX(0.94f);
        display.setScaleY(0.94f);
        display.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .start();
        Ui.rise(display, 40, 300);
        Ui.rise(hint, 100, 240);
        Ui.rise(buttons, 150, 260);
        Ui.rise(lap, 200, 260);
    }

    private long elapsed() {
        return accumulated + (running ? SystemClock.elapsedRealtime() - base : 0L);
    }

    private void toggle() {
        if (running) {
            accumulated = elapsed();
            running = false;
            handler.removeCallbacks(ticker);
            display.setText(fmt(accumulated));
            primary.setText("Tiếp tục");
        } else {
            base = SystemClock.elapsedRealtime();
            running = true;
            primary.setText("Tạm dừng");
            handler.post(ticker);
        }
    }

    private void reset() {
        running = false;
        accumulated = 0L;
        base = 0L;
        handler.removeCallbacks(ticker);
        display.setText(fmt(0));
        primary.setText("Bắt đầu");
        laps.removeAllViews();
        lapNo = 0;
    }

    private void addLap() {
        long now = elapsed();
        if (now == 0L) return;
        lapNo++;
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

        TextView name = Ui.text(this, "Lap " + lapNo, 14, Ui.MUTED, false);
        name.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        row.addView(Ui.text(this, fmt(now), 15, Ui.TEXT, true));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF232A36);

        LinearLayout holder = Ui.col(this);
        holder.addView(row, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(divider, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1,
                Ui.dp(this, 0.6f))));
        holder.setAlpha(0f);
        holder.setTranslationY(-Ui.dp(this, 10));
        laps.addView(holder, 0);
        holder.animate().alpha(1f).translationY(0f).setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .start();
    }

    private String fmt(long ms) {
        long cs = (ms / 10) % 100;
        long s = (ms / 1000) % 60;
        long m = ms / 60000;
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
