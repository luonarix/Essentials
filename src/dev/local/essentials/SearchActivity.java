package dev.local.essentials;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** One search box for every tool, with accents ignored. */
public class SearchActivity extends Activity {

    private EditText input;
    private LinearLayout results;
    private TextView count;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 6));

        TextView back = Ui.text(this, "\u2039", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.press(back, 0.88f);
        header.addView(Ui.text(this, "Tìm công cụ", 22, Ui.TEXT, true));
        root.addView(header);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setBackground(Ui.framed(Ui.SURFACE, this, 14));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xFF6A7382);
        input.setHint("Gõ tên công cụ, ví dụ: đèn, ghi chú, hẹn giờ...");
        input.setTextSize(15);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams ip = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.leftMargin = Ui.dp(this, 18);
        ip.rightMargin = Ui.dp(this, 18);
        ip.topMargin = Ui.dp(this, 8);
        root.addView(input, ip);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                render(s.toString());
            }
        });

        count = Ui.text(this, "", 11, Ui.MUTED, false);
        count.setLayoutParams(Ui.lp());
        Ui.margin(this, count, 20, 10, 20, 0);
        root.addView(count);

        ScrollView sv = new ScrollView(this);
        results = Ui.col(this);
        results.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 28));
        sv.addView(results, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, Ui.fill(1f));

        setContentView(root);
        Ui.rise(header, 20, 200);
        Ui.rise(input, 50, 200);
        render("");
        input.requestFocus();
        input.postDelayed(new Runnable() {
            public void run() {
                try {
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception ignored) {
                }
            }
        }, 220);
    }

    private void render(String query) {
        if (results == null) return;
        results.removeAllViews();
        String q = fold(query);
        List<Modules.Item> all = Modules.all();
        int shown = 0;
        for (final Modules.Item item : all) {
            if (!Modules.enabled(this, item.id)) continue;
            if (!q.isEmpty() && !fold(item.label).contains(q)
                    && !fold(item.id).contains(q)) continue;
            shown++;
            results.addView(row(item));
        }
        count.setText(shown == 0 ? "Không tìm thấy công cụ phù hợp"
                : shown + " công cụ");
        Ui.stagger(results, 20, 14);
    }

    private View row(final Modules.Item item) {
        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.bg(Ui.ACCENT_SOFT, this, 12));
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(item.icon);
        icon.setColorFilter(Ui.ACCENT);
        box.addView(icon, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
        r.addView(box, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));

        TextView label = Ui.text(this, item.label, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        r.addView(label, lp);

        r.addView(Ui.text(this, "\u203A", 20, Ui.MUTED, false));

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openTool(item.id);
            }
        });
        r.setClickable(true);
        Ui.press(r, 0.98f);

        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(p);
        return r;
    }

    private void openTool(String id) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cấp quyền \"Hiển thị trên ứng dụng khác\" rồi thử lại",
                    Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        Side.setOn(this, true);
        SidebarService.requestTool(this, id);
        finish();
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (input != null) {
            input.setText("");
            render("");
        }
    }
}
