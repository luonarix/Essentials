package dev.local.essentials;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Whole-app backup as one readable JSON file. Every SharedPreferences file
 * (notes, sidebar layout, tool settings, feature switches, timers) is copied,
 * so moving to a new phone keeps everything.
 */
final class Backup {

    static final int FORMAT = 1;

    private Backup() {}

    static String exportJson(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", c.getPackageName());
        root.put("format", FORMAT);
        root.put("saved", System.currentTimeMillis());

        JSONObject prefsOut = new JSONObject();
        File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String file = f.getName();
                if (!file.endsWith(".xml")) continue;
                String name = file.substring(0, file.length() - 4);
                try {
                    prefsOut.put(name, readPrefs(f));
                } catch (Exception ignored) {
                    // Skip one unreadable prefs file instead of losing the rest.
                }
            }
        }
        root.put("prefs", prefsOut);
        return root.toString(2);
    }

    private static JSONObject readPrefs(File file) throws Exception {
        JSONObject out = new JSONObject();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new FileInputStream(file), "UTF-8");
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            if ("map".equals(tag)) continue;
            String name = p.getAttributeValue(null, "name");
            if (name == null || name.isEmpty()) continue;

            JSONObject wrap = new JSONObject();
            wrap.put("t", tag);
            if ("set".equals(tag)) {
                JSONArray arr = new JSONArray();
                int depth = p.getDepth();
                while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.END_TAG && p.getDepth() == depth) break;
                    if (event == XmlPullParser.START_TAG
                            && "string".equals(p.getName())) {
                        arr.put(p.nextText());
                    }
                }
                wrap.put("v", arr);
            } else if ("string".equals(tag)) {
                wrap.put("v", p.nextText());
            } else {
                String v = p.getAttributeValue(null, "value");
                wrap.put("v", v == null ? "" : v);
            }
            out.put(name, wrap);
        }
        return out;
    }

    static int restoreJson(Context c, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject prefsIn = root.optJSONObject("prefs");
        if (prefsIn == null) throw new Exception("Tệp không phải bản sao lưu Essentials");

        int total = 0;
        JSONArray names = prefsIn.names();
        if (names == null) return 0;
        for (int i = 0; i < names.length(); i++) {
            String prefName = names.getString(i);
            JSONObject values = prefsIn.optJSONObject(prefName);
            if (values == null) continue;
            SharedPreferences.Editor ed = c.getSharedPreferences(
                    prefName, Context.MODE_PRIVATE).edit();
            ed.clear();
            JSONArray keys = values.names();
            if (keys == null) {
                ed.commit();
                continue;
            }
            for (int k = 0; k < keys.length(); k++) {
                String key = keys.getString(k);
                JSONObject wrap = values.optJSONObject(key);
                if (wrap == null) continue;
                String type = wrap.optString("t", "string");
                Object v = wrap.opt("v");
                try {
                    if ("boolean".equals(type)) {
                        ed.putBoolean(key, "true".equals(String.valueOf(v)));
                    } else if ("int".equals(type)) {
                        ed.putInt(key, Integer.parseInt(String.valueOf(v)));
                    } else if ("long".equals(type)) {
                        ed.putLong(key, Long.parseLong(String.valueOf(v)));
                    } else if ("float".equals(type)) {
                        ed.putFloat(key, Float.parseFloat(String.valueOf(v)));
                    } else if ("set".equals(type) && v instanceof JSONArray) {
                        Set<String> set = new HashSet<>();
                        JSONArray arr = (JSONArray) v;
                        for (int j = 0; j < arr.length(); j++) {
                            set.add(arr.optString(j, ""));
                        }
                        ed.putStringSet(key, set);
                    } else {
                        ed.putString(key, v == null ? "" : String.valueOf(v));
                    }
                    total++;
                } catch (Exception ignored) {
                    // One malformed value must not abort the whole restore.
                }
            }
            ed.commit();
        }
        return total;
    }

    static void write(Context c, Uri uri, String text) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(uri, "w");
        if (os == null) throw new Exception("Không ghi được tệp");
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            os.close();
        }
    }

    static String read(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("Không đọc được tệp");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
