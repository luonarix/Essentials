package dev.local.essentials;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotesActivity extends Activity {

    private static final int REQ_EXPORT = 91;

    private LinearLayout list;
    private LinearLayout tagBar;
    private TextView counter;
    private EditText search;

    private final List<Notes.Note> all = new ArrayList<>();
    private final List<Notes.Note> items = new ArrayList<>();
    private String query = "";
    private String tagFilter = "";

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

        TextView export = Ui.text(this, "Xuất", 12, Ui.ACCENT, true);
        export.setGravity(Gravity.CENTER);
        export.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                exportNotes();
            }
        });
        header.addView(export);
        Ui.press(export, 0.92f);

        TextView add = Ui.text(this, "+", 30, Ui.ACCENT, false);
        add.setGravity(Gravity.CENTER);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                edit(null);
            }
        });
        header.addView(add, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.press(add, 0.88f);

        root.addView(header);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(Ui.framed(Ui.SURFACE, this, 13));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0xFF6A7382);
        search.setHint("Tìm trong ghi chú...");
        search.setTextSize(14);
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams sp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(this, 18);
        sp.rightMargin = Ui.dp(this, 18);
        sp.topMargin = Ui.dp(this, 2);
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString();
                filter();
            }
        });

        android.widget.HorizontalScrollView tags = new android.widget.HorizontalScrollView(this);
        tags.setHorizontalScrollBarEnabled(false);
        tags.setFillViewport(false);
        tagBar = Ui.row(this);
        tagBar.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 2));
        tags.addView(tagBar, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tags);

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
        all.clear();
        all.addAll(Notes.load(this));
        filter();
    }

    private void filter() {
        if (list == null) return;
        items.clear();
        String q = fold(query);
        for (Notes.Note n : all) {
            if (!tagFilter.isEmpty() && !tagFilter.equals(n.tag)) continue;
            if (!q.isEmpty() && !fold(n.text + " " + n.tag).contains(q)) continue;
            items.add(n);
        }
        render();
        renderTags();
    }

    private void render() {
        list.removeAllViews();

        if (all.isEmpty()) {
            counter.setText("Chưa có ghi chú nào");
            addEmpty("Bấm dấu + để thêm ghi chú đầu tiên.\n"
                    + "Chạm để sửa, giữ để xoá.");
            return;
        }
        if (items.isEmpty()) {
            counter.setText("Không khớp bộ lọc · " + all.size() + " ghi chú");
            addEmpty("Không có ghi chú nào khớp.\nThử từ khoá khác hoặc bỏ lọc nhãn.");
            return;
        }

        boolean filtered = !query.trim().isEmpty() || !tagFilter.isEmpty();
        counter.setText(filtered ? items.size() + "/" + all.size() + " ghi chú"
                : items.size() + " ghi chú");

        for (int i = 0; i < items.size(); i++) {
            final Notes.Note note = items.get(i);

            LinearLayout row = Ui.col(this);
            row.setBackground(Ui.framed(Ui.SURFACE, this, 16));
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

            if (note.tag != null && !note.tag.isEmpty()) {
                TextView chip = Ui.text(this, "#" + note.tag, 10, Ui.ACCENT, true);
                chip.setBackground(Ui.bg(Ui.ACCENT_SOFT, this, 8));
                chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
                LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.bottomMargin = Ui.dp(this, 7);
                row.addView(chip, cp);
            }

            TextView body = Ui.text(this, note.text, 15, Ui.TEXT, false);
            body.setLineSpacing(Ui.dp(this, 4), 1f);
            row.addView(body);

            TextView time = Ui.text(this, when(note.time), 11, Ui.MUTED, false);
            time.setLayoutParams(Ui.lp());
            Ui.margin(this, time, 0, 8, 0, 0);
            row.addView(time);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    edit(note);
                }
            });
            Ui.press(row, 0.98f);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    confirmDelete(note);
                    return true;
                }
            });

            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = Ui.dp(this, 10);
            list.addView(row, p);
        }
        Ui.stagger(list, 40, 20);
    }

    private void addEmpty(String message) {
        TextView empty = Ui.text(this, message, 14, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 56);
        list.addView(empty, p);
    }

    private void renderTags() {
        if (tagBar == null) return;
        tagBar.removeAllViews();
        List<String> tags = new ArrayList<>();
        for (Notes.Note n : all) {
            if (n.tag != null && !n.tag.isEmpty() && !tags.contains(n.tag)) tags.add(n.tag);
        }
        if (tags.isEmpty()) {
            tagFilter = "";
            tagBar.setVisibility(View.GONE);
            return;
        }
        tagBar.setVisibility(View.VISIBLE);
        addTagChip("Tất cả", "");
        for (String tag : tags) addTagChip("#" + tag, tag);
    }

    private void addTagChip(String label, final String value) {
        boolean on = tagFilter.equals(value);
        TextView chip = Ui.text(this, label, 12, on ? 0xFF0B1020 : Ui.TEXT, on);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.bg(on ? Ui.ACCENT : 0x22FFFFFF, this, 12));
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tagFilter = value;
                filter();
            }
        });
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(this, 7);
        tagBar.addView(chip, p);
        Ui.press(chip, 0.94f);
    }

    /** note == null means "create new". */
    private void edit(final Notes.Note note) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("Viết gì đó...");
        if (note != null) {
            input.setText(note.text);
            input.setSelection(input.getText().length());
        }

        final EditText tag = new EditText(this);
        tag.setSingleLine(true);
        tag.setInputType(InputType.TYPE_CLASS_TEXT);
        tag.setTextColor(Ui.TEXT);
        tag.setHintTextColor(Ui.MUTED);
        tag.setHint("Nhãn (không bắt buộc), ví dụ: việc");
        tag.setTextSize(13);
        if (note != null) tag.setText(note.tag);

        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), 0);
        wrap.addView(input, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 8);
        wrap.addView(tag, tp);

        new AlertDialog.Builder(this)
                .setTitle(note == null ? "Ghi chú mới" : "Sửa ghi chú")
                .setView(wrap)
                .setPositiveButton("Lưu", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String text = input.getText().toString().trim();
                        if (text.isEmpty()) return;
                        String t = tag.getText().toString().trim();
                        if (t.startsWith("#")) t = t.substring(1);
                        t = t.replace(",", " ").trim();
                        if (note == null) {
                            all.add(0, new Notes.Note(text, t, System.currentTimeMillis()));
                        } else {
                            note.text = text;
                            note.tag = t;
                            note.time = System.currentTimeMillis();
                        }
                        Notes.save(NotesActivity.this, all);
                        reload(false);
                    }
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }

    private void confirmDelete(final Notes.Note note) {
        String preview = note.text;
        if (preview.length() > 40) preview = preview.substring(0, 40) + "...";
        new AlertDialog.Builder(this)
                .setTitle("Xoá ghi chú?")
                .setMessage(preview)
                .setPositiveButton("Xoá", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        all.remove(note);
                        Notes.save(NotesActivity.this, all);
                        reload(false);
                    }
                })
                .setNegativeButton("Giữ", null)
                .show();
    }

    // ------------------------------------------------------------ export

    private void exportNotes() {
        if (all.isEmpty()) {
            toast("Chưa có ghi chú để xuất");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "ghi-chu-essentials.md");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("Máy không có trình chọn tệp");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Backup.write(this, uri, markdown());
            toast("Đã xuất ghi chú");
        } catch (Exception e) {
            toast("Xuất thất bại");
        }
    }

    private String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Ghi chú Essentials\n\n");
        sb.append("_")
                .append(all.size())
                .append(" ghi chú · xuất ")
                .append(new SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault())
                        .format(new Date()))
                .append("_\n");
        String lastTag = null;
        for (Notes.Note n : all) {
            String tag = n.tag == null ? "" : n.tag;
            if (!tag.equals(lastTag)) {
                sb.append("\n## ").append(tag.isEmpty() ? "Không nhãn" : "#" + tag)
                        .append("\n\n");
                lastTag = tag;
            }
            sb.append("- **")
                    .append(new SimpleDateFormat("d/M/yyyy HH:mm", Locale.getDefault())
                            .format(new Date(n.time)))
                    .append("**\n\n");
            String[] lines = n.text.split("\n", -1);
            for (String line : lines) {
                sb.append("  ").append(line).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
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
