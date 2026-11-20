package dev.local.essentials;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sidebar state: which actions are shown, and whether the bar is on. */
final class Side {

    static final String PREF = "sidebar";
    static final String KEY_ITEMS = "items";
    static final String KEY_ON = "on";
    static final String KEY_POS = "pos";

    /** id -> label, in the order they appear in the editor */
    static final String[] BUILTIN = {
            "#torch", "#quicknote", "#notes", "#timer", "#convert", "#shot", "#volume",
            "#hud", "#power", "#notifications", "#quicksettings", "#ringer", "#caffeine",
            "#color", "#lock", "#back", "#home", "#recents",
            "#ruler", "#tally", "#clip", "#black", "#qr",
            "#crosshair", "#timers", "#brightness", "#rotate", "#dnd",
            "#autoclick", "#secure", "#battery", "#refimage", "#side",
            "#peeker", "#data", "#hotspot", "#sticky", "#toasttimer",
            "#farm", "#menu"
    };

    static final String[] LABELS = {
            "Đèn pin", "Ghi chú nhanh", "Danh sách ghi chú", "Bấm giờ", "Quy đổi",
            "Chụp màn hình", "Âm lượng", "HUD hiệu năng",
            "Nguồn / Khởi động lại", "Kéo rèm thông báo", "Cài đặt nhanh",
            "Chuông / Rung / Im", "Giữ sáng màn hình", "Soi màu & lấy mã Hex",
            "Khoá cảm ứng", "Quay lại", "Về màn hình chính", "Đa nhiệm",
            "Thước đo & lưới", "Bộ đếm & xúc xắc", "Clipboard & đoạn mẫu",
            "Màn hình đen", "Mã QR offline", "Tâm ngắm ảo", "Đa hẹn giờ",
            "Khoá độ sáng", "Khoá xoay màn hình", "Không làm phiền",
            "Tự động chạm", "Mật khẩu & mã hoá", "Cảnh báo pin",
            "Ảnh tham chiếu nổi", "Đổi bên thanh bên", "Đọc thông báo nhanh",
            "Dữ liệu di động", "Điểm phát sóng", "Bảng nháp ghim",
            "Nhắc nhanh", "Bộ đếm phiên cày", "Menu đầy đủ"
    };

    /** Tools added in v7.0, appended once to lists saved by older versions. */
    static final String[] V7_ITEMS = {
            "#power", "#notifications", "#quicksettings", "#ringer", "#caffeine",
            "#color", "#lock", "#back", "#home", "#recents"
    };

    /** Tools added in v8.0, appended once to lists saved by older versions. */
    static final String[] V8_ITEMS = {
            "#ruler", "#tally", "#clip", "#black", "#qr"
    };

    /** Tools added in v9.0, appended once to lists saved by older versions. */
    static final String[] V9_ITEMS = {
            "#crosshair", "#timers", "#brightness", "#rotate", "#dnd"
    };

    /** Tools added in v9.2, appended once to lists saved by older versions. */
    static final String[] V92_ITEMS = {
            "#autoclick", "#secure", "#battery", "#refimage"
    };

    /** Actions added in v1.0.1, appended once to lists saved by older versions. */
    static final String[] V101_ITEMS = {
            "#side"
    };

    /** Tools added in v1.1.0, appended once to lists saved by older versions. */
    static final String[] V110_ITEMS = {
            "#peeker", "#data", "#hotspot", "#sticky", "#toasttimer"
    };

    /** Tools added in v1.2.0, appended once to lists saved by older versions. */
    static final String[] V120_ITEMS = {
            "#farm"
    };

    private Side() {}

    static List<String> items(Context c) {
        String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null);
        if (raw == null) {
            return new ArrayList<>(Arrays.asList("#torch", "#quicknote", "#notes",
                    "#timer", "#convert", "#shot", "#volume", "#hud",
                    "#power", "#notifications", "#quicksettings", "#ringer", "#caffeine",
                    "#color", "#lock", "#back", "#home", "#recents",
                    "#ruler", "#tally", "#clip", "#black", "#qr",
                    "#crosshair", "#timers", "#brightness", "#rotate", "#dnd",
                    "#autoclick", "#secure", "#battery", "#refimage", "#side",
                    "#peeker", "#data", "#hotspot", "#sticky", "#toasttimer",
                    "#farm"));
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static void setItems(Context c, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, sb.toString()).apply();
    }

    static boolean has(Context c, String id) {
        return items(c).contains(id);
    }

    static void toggle(Context c, String id) {
        List<String> ids = items(c);
        if (ids.contains(id)) ids.remove(id);
        else ids.add(id);
        setItems(c, ids);
    }

    /**
     * One-time migration of saved item lists: "#capture" (v5.0) becomes "#shot",
     * the removed "#record" is dropped, and "#shot" is appended if missing.
     */
    static void ensureCaptureTools(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getInt("capture_tools", 0) >= 3) return;
        List<String> ids = items(c);
        int legacy = ids.indexOf("#capture");
        if (legacy >= 0) {
            ids.remove(legacy);
            ids.add(legacy, "#shot");
        }
        ids.remove("#record");
        if (!ids.contains("#shot")) ids.add("#shot");
        setItems(c, ids);
        sp.edit().putInt("capture_tools", 3).apply();
    }

    /** Adds the volume button once for lists saved before v5.2. */
    static void ensureVolume(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("volume_added", false)) return;
        List<String> ids = items(c);
        if (!ids.contains("#volume")) ids.add("#volume");
        setItems(c, ids);
        sp.edit().putBoolean("volume_added", true).apply();
    }

    /** Adds the performance HUD button once for lists saved before v5.3. */
    static void ensureHud(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("hud_added", false)) return;
        List<String> ids = items(c);
        if (!ids.contains("#hud")) ids.add("#hud");
        setItems(c, ids);
        sp.edit().putBoolean("hud_added", true).apply();
    }

    /** Adds the v7.0 tools once, keeping "#menu" at the end of the list. */
    static void ensureToolkit(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v7", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V7_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v7", true).apply();
    }

    /** Adds the v8.0 tools once, keeping "#menu" at the end of the list. */
    static void ensureToolkit8(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v8", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V8_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v8", true).apply();
    }

    /** Adds the v9.0 tools once, keeping "#menu" at the end of the list. */
    static void ensureToolkit9(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v9", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V9_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v9", true).apply();
    }

    /** Adds the v9.2 tools once, keeping "#menu" at the end of the list. */
    static void ensureToolkit92(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v92", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V92_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v92", true).apply();
    }

    /** Adds the v1.0.1 side switch once, keeping "#menu" last when present. */
    static void ensureToolkit101(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v101", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V101_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v101", true).apply();
    }

    /** Adds the v1.1.0 tools once, keeping "#menu" last when present. */
    static void ensureToolkit110(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v110", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V110_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v110", true).apply();
    }

    /** Adds the v1.2.0 tools once, keeping "#menu" last when present. */
    static void ensureToolkit120(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("tools_v120", false)) return;
        List<String> ids = items(c);
        int menu = ids.indexOf("#menu");
        for (String id : V120_ITEMS) {
            if (ids.contains(id)) continue;
            if (menu >= 0) {
                ids.add(menu, id);
                menu++;
            } else {
                ids.add(id);
            }
        }
        setItems(c, ids);
        sp.edit().putBoolean("tools_v120", true).apply();
    }

    /** True when the handle and dock sit on the left screen edge. */
    static boolean sidebarLeft(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("left", false);
    }

    static void setSidebarLeft(Context c, boolean left) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("left", left).apply();
    }

    /** Default countdown lengths for the four multi-timer slots, in minutes. */
    private static final int[] TIMER_DEFAULTS = {5, 10, 15, 30};

    static int timerMinutes(Context c, int index) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt("timer_min_" + index,
                        TIMER_DEFAULTS[index % TIMER_DEFAULTS.length]);
    }

    static void setTimerMinutes(Context c, int index, int minutes) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("timer_min_" + index, Math.max(1, minutes)).apply();
    }

    static int tally(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("tally", 0);
    }

    static void setTally(Context c, int value) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("tally", value).apply();
    }

    static boolean caffeineOn(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("caffeine", false);
    }

    static void setCaffeineOn(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("caffeine", on).apply();
    }

    static boolean hudOn(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("hud", false);
    }

    static void setHudOn(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("hud", on).apply();
    }

    static boolean hudCompact(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("hud_compact", false);
    }

    static void setHudCompact(Context c, boolean compact) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("hud_compact", compact).apply();
    }

    static int hudX(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("hud_x", -1);
    }

    static int hudY(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("hud_y", -1);
    }

    static void setHudPos(Context c, int x, int y) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("hud_x", x).putInt("hud_y", y).apply();
    }

    static boolean on(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false);
    }

    static void setOn(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    static int pos(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_POS, -1);
    }

    static void setPos(Context c, int y) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_POS, y).apply();
    }

    static String labelOf(String id) {
        for (int i = 0; i < BUILTIN.length; i++) {
            if (BUILTIN[i].equals(id)) return LABELS[i];
        }
        return id;
    }
}
