package dev.local.essentials;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Notes storage: a JSON array inside SharedPreferences. No database needed. */
final class Notes {

    static final String PREF = "notes_store";
    static final String KEY = "items";

    static final class Note {
        String text;
        long time;

        Note(String text, long time) {
            this.text = text;
            this.time = time;
        }
    }

    private Notes() {}

    static List<Note> load(Context c) {
        List<Note> out = new ArrayList<>();
        String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("t", "");
                if (t.isEmpty()) continue;
                out.add(new Note(t, o.optLong("ts", 0L)));
            }
        } catch (Exception ignored) {
            // corrupt payload: behave like an empty list rather than crashing
        }
        return out;
    }

    static void save(Context c, List<Note> list) {
        JSONArray arr = new JSONArray();
        for (Note n : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", n.text);
                o.put("ts", n.time);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    /** Adds to the top of the list. */
    static void insert(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        List<Note> list = load(c);
        list.add(0, new Note(text.trim(), System.currentTimeMillis()));
        save(c, list);
    }

    /** Removes one note; identity is object or (text + timestamp). */
    static void delete(Context c, Note target) {
        if (target == null) return;
        List<Note> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            Note n = list.get(i);
            if (n == target || (n.time == target.time && n.text.equals(target.text))) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }
}
