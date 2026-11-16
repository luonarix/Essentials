package dev.local.essentials;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotesActivity extends Activity {

    private LinearLayout list;
    private TextView counter;
    private final List<Notes.Note> items = new ArrayList<>();

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

        LinearLayout titles = Ui.col(this);
        titles.setPadding(Ui.dp(this, 4), 0, 0, 0);
        titles.addView(Ui.text(this, "Ghi chú", 22, Ui.TEXT, true));
        counter = Ui.text(this, "", 12, Ui.MUTED, false);
        counter.setLayoutParams(Ui.lp());
        Ui.margin(this, counter, 0, 3, 0, 0);
        titles.addView(counter);
        header.addView(titles, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = Ui.text(this, "+", 30, Ui.ACCENT, false);
        add.setGravity(Gravity.CENTER);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                edit(-1);
            }
        });
        header.addView(add, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)));
        Ui.press(add, 0.88f);

        root.addView(header);

        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        list.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 28));
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, Ui.fill(1f));

        setContentView(root);
        Ui.rise(header, 20, 220);
        reload(true);
    }

    private void reload(boolean animate) {
        items.clear();
        items.addAll(Notes.load(this));
        list.removeAllViews();

        counter.setText(items.isEmpty() ? "Chưa có ghi chú nào"
                : items.size() + " ghi chú");

        if (items.isEmpty()) {
            TextView empty = Ui.text(this,
                    "Bấm dấu + để thêm ghi chú đầu tiên.\nChạm để sửa, giữ để xoá.",
                    14, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(Ui.dp(this, 5), 1f);
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(this, 80);
            list.addView(empty, p);
            if (animate) Ui.rise(empty, 60, 240);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final Notes.Note note = items.get(i);

            LinearLayout row = Ui.col(this);
            row.setBackground(Ui.framed(Ui.SURFACE, this, 16));
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

            TextView body = Ui.text(this, note.text, 15, Ui.TEXT, false);
            body.setLineSpacing(Ui.dp(this, 4), 1f);
            row.addView(body);

            TextView time = Ui.text(this, when(note.time), 11, Ui.MUTED, false);
            time.setLayoutParams(Ui.lp());
            Ui.margin(this, time, 0, 8, 0, 0);
            row.addView(time);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    edit(index);
                }
            });
            Ui.press(row, 0.98f);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    confirmDelete(index);
                    return true;
                }
            });

            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = Ui.dp(this, 10);
            list.addView(row, p);
        }
        if (animate) Ui.stagger(list, 60, 24);
    }

    /** index = -1 means "create new". */
    private void edit(final int index) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("Viết gì đó...");
        if (index >= 0) {
            input.setText(items.get(index).text);
            input.setSelection(input.getText().length());
        }

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(index < 0 ? "Ghi chú mới" : "Sửa ghi chú")
                .setView(wrap)
                .setPositiveButton("Lưu", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String text = input.getText().toString().trim();
                        if (text.isEmpty()) return;
                        if (index < 0) {
                            items.add(0, new Notes.Note(text, System.currentTimeMillis()));
                        } else {
                            items.get(index).text = text;
                            items.get(index).time = System.currentTimeMillis();
                        }
                        Notes.save(NotesActivity.this, items);
                        reload(false);
                    }
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }

    private void confirmDelete(final int index) {
        String preview = items.get(index).text;
        if (preview.length() > 40) preview = preview.substring(0, 40) + "...";
        new AlertDialog.Builder(this)
                .setTitle("Xoá ghi chú?")
                .setMessage(preview)
                .setPositiveButton("Xoá", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        items.remove(index);
                        Notes.save(NotesActivity.this, items);
                        reload(false);
                    }
                })
                .setNegativeButton("Giữ", null)
                .show();
    }

    private String when(long ts) {
        if (ts <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (today) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        return new SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
