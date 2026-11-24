package dev.local.essentials;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
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
import android.widget.Toast;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * One switchboard: enable or hide each tool, pin the useful ones to the top,
 * and back up or restore every setting as a single JSON file.
 */
public class FeaturesActivity extends Activity {

    private static final int REQ_BACKUP = 81;
    private static final int REQ_RESTORE = 82;

    private LinearLayout list;
    private TextView summary;
    private EditText search;
    private String query = "";

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
        header.addView(Ui.text(this, "Tính năng & dữ liệu", 22, Ui.TEXT, true));
        root.addView(header);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        body.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 28));
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, Ui.fill(1f));

        body.addView(dataCard());

        LinearLayout perms = Ui.row(this);
        perms.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        perms.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        perms.addView(Ui.text(this, "Quyền hệ thống", 14, Ui.TEXT, true),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        perms.addView(Ui.text(this, "Kiểm tra \u203a", 12, Ui.ACCENT, true));
        perms.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(FeaturesActivity.this,
                        PermissionsActivity.class));
            }
        });
        perms.setClickable(true);
        Ui.press(perms, 0.98f);
        LinearLayout.LayoutParams pp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = Ui.dp(this, 10);
        body.addView(perms, pp);

        summary = Ui.text(this, "", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams sp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 20);
        body.addView(summary, sp);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(Ui.framed(Ui.SURFACE, this, 14));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0xFF6A7382);
        search.setHint("Tìm công cụ...");
        search.setTextSize(14);
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams fp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(this, 8);
        body.addView(search, fp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString();
                render();
            }
        });

        list = Ui.col(this);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        body.addView(list, lp);

        setContentView(root);
        Ui.rise(header, 20, 200);
        render();
    }

    private View dataCard() {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 18));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        card.addView(Ui.text(this, "Sao lưu & khôi phục", 15, Ui.TEXT, true));
        TextView note = Ui.text(this,
                "Ghi chú, mẫu giờ hẹn, bố cục thanh bên và mọi cài đặt vào một tệp JSON. "
                        + "Đổi máy hoặc cài lại thì khôi phục lại là xong.",
                11, Ui.MUTED, false);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        note.setLayoutParams(Ui.lp());
        Ui.margin(this, note, 0, 4, 0, 0);
        card.addView(note);

        LinearLayout row = Ui.row(this);
        TextView backup = Ui.text(this, "Sao lưu", 13, 0xFF0B1020, true);
        backup.setGravity(Gravity.CENTER);
        backup.setBackground(Ui.bg(Ui.ACCENT, this, 12));
        backup.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        backup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startBackup();
            }
        });
        row.addView(backup);
        Ui.press(backup, 0.94f);

        TextView restore = Ui.text(this, "Khôi phục", 13, Ui.TEXT, false);
        restore.setGravity(Gravity.CENTER);
        restore.setBackground(Ui.framed(0x33FFFFFF, this, 12));
        restore.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        restore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startRestore();
            }
        });
        LinearLayout.LayoutParams rp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = Ui.dp(this, 10);
        row.addView(restore, rp);
        Ui.press(restore, 0.94f);

        LinearLayout.LayoutParams rowp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowp.topMargin = Ui.dp(this, 12);
        card.addView(row, rowp);
        return card;
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String q = fold(query);
        List<Modules.Item> all = Modules.all();
        int shown = 0;
        for (int i = 0; i < all.size(); i++) {
            Modules.Item item = all.get(i);
            if (!q.isEmpty() && !fold(item.label).contains(q)
                    && !fold(item.id).contains(q)) continue;
            shown++;
            list.addView(moduleRow(item));
        }
        int enabled = Modules.enabledCount(this);
        summary.setText(enabled + "/" + all.size() + " công cụ đang bật"
                + (q.isEmpty() ? " · chạm để bật/tắt, chạm ★ để ghim lên đầu"
                               : " · " + shown + " kết quả"));
    }

    private View moduleRow(final Modules.Item item) {
        final boolean on = Modules.enabled(this, item.id);
        final boolean pin = Modules.pinned(this, item.id);

        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.bg(Ui.ACCENT_SOFT, this, 11));
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(item.icon);
        icon.setColorFilter(on ? Ui.ACCENT : Ui.MUTED);
        box.addView(icon, Ui.lp(Ui.dp(this, 19), Ui.dp(this, 19)));
        row.addView(box, Ui.lp(Ui.dp(this, 38), Ui.dp(this, 38)));

        LinearLayout text = Ui.col(this);
        LinearLayout.LayoutParams tp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 10);
        text.addView(Ui.text(this, item.label, 14, on ? Ui.TEXT : Ui.MUTED, false));
        TextView state = Ui.text(this,
                on ? (pin ? "Đang bật · đã ghim" : "Đang bật") : "Đang ẩn",
                10, on ? (pin ? 0xFF6FE3A1 : Ui.MUTED) : 0xFFFFB05A, false);
        state.setLayoutParams(Ui.lp());
        Ui.margin(this, state, 0, 2, 0, 0);
        text.addView(state);
        row.addView(text, tp);

        TextView star = Ui.text(this, pin ? "\u2605" : "\u2606", 22,
                pin ? 0xFFFFD166 : Ui.MUTED, false);
        star.setGravity(Gravity.CENTER);
        star.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        star.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Modules.setPinned(FeaturesActivity.this, item.id, !pin);
                render();
                SidebarService.refreshItems();
            }
        });
        row.addView(star);
        Ui.press(star, 0.9f);

        final TextView toggle = Ui.text(this, on ? "Bật" : "Tắt", 11,
                on ? 0xFF0B1020 : Ui.TEXT, true);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackground(Ui.bg(on ? 0xFF6FE3A1 : 0x33FFFFFF, this, 12));
        toggle.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        toggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Modules.setEnabled(FeaturesActivity.this, item.id, !on);
                render();
                SidebarService.refreshItems();
            }
        });
        row.addView(toggle);
        Ui.press(toggle, 0.94f);

        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(p);
        return row;
    }

    // ------------------------------------------------------------ backup

    private void startBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "essentials-backup.json");
            startActivityForResult(i, REQ_BACKUP);
        } catch (Exception e) {
            toast("Máy không có trình chọn tệp");
        }
    }

    private void startRestore() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            toast("Máy không có trình chọn tệp");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_BACKUP) {
            try {
                String json = Backup.exportJson(this);
                Backup.write(this, uri, json);
                toast("Đã sao lưu xong");
            } catch (Exception e) {
                toast("Sao lưu thất bại: " + e.getMessage());
            }
            return;
        }

        if (req == REQ_RESTORE) {
            final String json;
            try {
                json = Backup.read(this, uri);
            } catch (Exception e) {
                toast("Không đọc được tệp");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Khôi phục dữ liệu?")
                    .setMessage("Mọi cài đặt và ghi chú hiện tại sẽ được thay bằng "
                            + "nội dung trong tệp sao lưu.")
                    .setPositiveButton("Khôi phục", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            doRestore(json);
                        }
                    })
                    .setNegativeButton("Huỷ", null)
                    .show();
        }
    }

    private void doRestore(String json) {
        try {
            int n = Backup.restoreJson(this, json);
            render();
            restartSidebar();
            toast("Đã khôi phục " + n + " mục cài đặt");
        } catch (Exception e) {
            toast("Tệp không hợp lệ");
        }
    }

    private void restartSidebar() {
        boolean wasOn = SidebarService.running();
        if (wasOn) stopService(new Intent(this, SidebarService.class));
        if (!wasOn) return;
        final boolean enabled = Side.on(this);
        if (!enabled) return;
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try {
                    Intent i = new Intent(FeaturesActivity.this, SidebarService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (Exception ignored) {
                }
            }
        }, 350);
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
