package dev.local.essentials;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Floating picker that appends a "app:&lt;pkg&gt;" shortcut to the sidebar. */
public class AddShortcutActivity extends Activity {

    private LinearLayout list;
    private List<Apps.App> apps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 20));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 12));

        card.addView(Ui.text(this, "Thêm ứng dụng", 15, Ui.TEXT, true));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(Ui.bg(0xFF0E1116, this, 12));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0xFF6A7382);
        search.setHint("Tìm...");
        search.setTextSize(14);
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 10);
        card.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                render(s.toString(), false);
            }
        });

        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380));
        vp.topMargin = Ui.dp(this, 6);
        card.addView(sv, vp);

        setContentView(card);
        getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                ViewGroup.LayoutParams.WRAP_CONTENT);

        apps = Apps.load(this);
        render("", true);
    }

    private void render(String query) {
        render(query, false);
    }

    private void render(String query, boolean animate) {
        if (list == null) return;
        list.removeAllViews();
        String q = fold(query);
        int shown = 0;

        for (final Apps.App app : apps) {
            if (!q.isEmpty() && !fold(app.label).contains(q)) continue;
            shown++;

            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

            ImageView icon = new ImageView(this);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));

            TextView name = Ui.text(this, app.label, 14, Ui.TEXT, false);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ((LinearLayout.LayoutParams) name.getLayoutParams()).leftMargin = Ui.dp(this, 12);

            final String id = "app:" + app.pkg;
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!Side.has(AddShortcutActivity.this, id)) {
                        java.util.List<String> ids = Side.items(AddShortcutActivity.this);
                        ids.add(id);
                        Side.setItems(AddShortcutActivity.this, ids);
                    }
                    SidebarService.refreshItems();
                    finish();
                }
            });
            row.setClickable(true);
            Ui.press(row, 0.98f);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (shown == 0) {
            TextView empty = Ui.text(this, "Không tìm thấy", 13, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            list.addView(empty);
        }
        if (animate) Ui.stagger(list, 40, 20);
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).trim();
    }
}
