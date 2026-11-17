package dev.local.essentials;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;

/** Local storage for clipboard history and pinned snippets. */
final class Snippets {

    private static final String PREF = "snippets";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PINNED = "pinned";
    private static final int HISTORY_MAX = 12;
    private static final int PINNED_MAX = 30;

    private Snippets() {}

    static List<String> history(Context c) {
        return load(c, KEY_HISTORY);
    }

    static List<String> pinned(Context c) {
        return load(c, KEY_PINNED);
    }

    /** Adds a clip to the front of the history, ignoring duplicates. */
    static void addHistory(Context c, String text) {
        if (text == null) return;
        String value = text.trim();
        if (value.isEmpty()) return;
        List<String> items = load(c, KEY_HISTORY);
        items.remove(value);
        items.add(0, value);
        while (items.size() > HISTORY_MAX) items.remove(items.size() - 1);
        save(c, KEY_HISTORY, items);
    }

    static void removeHistory(Context c, String text) {
        List<String> items = load(c, KEY_HISTORY);
        items.remove(text);
        save(c, KEY_HISTORY, items);
    }

    static void addPinned(Context c, String text) {
        if (text == null) return;
        String value = text.trim();
        if (value.isEmpty()) return;
        List<String> items = load(c, KEY_PINNED);
        items.remove(value);
        items.add(0, value);
        while (items.size() > PINNED_MAX) items.remove(items.size() - 1);
        save(c, KEY_PINNED, items);
    }

    static void removePinned(Context c, String text) {
        List<String> items = load(c, KEY_PINNED);
        items.remove(text);
        save(c, KEY_PINNED, items);
    }

    static void clearHistory(Context c) {
        save(c, KEY_HISTORY, new ArrayList<String>());
    }

    private static List<String> load(Context c, String key) {
        List<String> out = new ArrayList<>();
        String raw = prefs(c).getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            try {
                out.add(new String(Base64.decode(line, Base64.NO_WRAP), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void save(Context c, String key, List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(Base64.encodeToString(item.getBytes(), Base64.NO_WRAP));
        }
        prefs(c).edit().putString(key, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
