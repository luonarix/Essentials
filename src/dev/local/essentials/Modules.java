package dev.local.essentials;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Central switchboard for the tools in the sidebar: which ones are enabled,
 * which ones are pinned to the top, and the order the dock should show them.
 * Everything is plain SharedPreferences so a backup only has to copy the
 * "features" file.
 */
final class Modules {

    static final String PREF = "features";
    static final String KEY_DISABLED = "disabled";
    static final String KEY_PINNED = "pinned";

    static final class Item {
        final String id;
        final String label;
        final int icon;

        Item(String id, String label, int icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
        }
    }

    private Modules() {}

    /** Every built-in tool, in sidebar order. The menu button is not a tool. */
    static List<Item> all() {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < Side.BUILTIN.length; i++) {
            String id = Side.BUILTIN[i];
            if ("#menu".equals(id)) continue;
            out.add(new Item(id, Side.LABELS[i], iconFor(id)));
        }
        return out;
    }

    static int iconFor(String id) {
        if ("#torch".equals(id)) return R.drawable.ic_flash;
        if ("#quicknote".equals(id)) return R.drawable.ic_edit;
        if ("#notes".equals(id)) return R.drawable.ic_note;
        if ("#timer".equals(id)) return R.drawable.ic_timer;
        if ("#convert".equals(id)) return R.drawable.ic_convert;
        if ("#shot".equals(id)) return R.drawable.ic_camera;
        if ("#volume".equals(id)) return R.drawable.ic_volume;
        if ("#hud".equals(id)) return R.drawable.ic_stats;
        if ("#power".equals(id)) return R.drawable.ic_power;
        if ("#notifications".equals(id)) return R.drawable.ic_bell;
        if ("#quicksettings".equals(id)) return R.drawable.ic_quick;
        if ("#ringer".equals(id)) return R.drawable.ic_ringer;
        if ("#caffeine".equals(id)) return R.drawable.ic_caffeine;
        if ("#color".equals(id)) return R.drawable.ic_dropper;
        if ("#lock".equals(id)) return R.drawable.ic_lock;
        if ("#back".equals(id)) return R.drawable.ic_back;
        if ("#home".equals(id)) return R.drawable.ic_home;
        if ("#recents".equals(id)) return R.drawable.ic_recents;
        if ("#ruler".equals(id)) return R.drawable.ic_ruler;
        if ("#tally".equals(id)) return R.drawable.ic_tally;
        if ("#clip".equals(id)) return R.drawable.ic_clip;
        if ("#black".equals(id)) return R.drawable.ic_black;
        if ("#qr".equals(id)) return R.drawable.ic_qr;
        if ("#crosshair".equals(id)) return R.drawable.ic_crosshair;
        if ("#timers".equals(id)) return R.drawable.ic_multitimer;
        if ("#brightness".equals(id)) return R.drawable.ic_brightness;
        if ("#rotate".equals(id)) return R.drawable.ic_rotate;
        if ("#dnd".equals(id)) return R.drawable.ic_dnd;
        if ("#autoclick".equals(id)) return R.drawable.ic_autoclick;
        if ("#secure".equals(id)) return R.drawable.ic_secure;
        if ("#battery".equals(id)) return R.drawable.ic_battery_alert;
        if ("#refimage".equals(id)) return R.drawable.ic_refimage;
        if ("#side".equals(id)) return R.drawable.ic_side;
        if ("#peeker".equals(id)) return R.drawable.ic_peeker;
        if ("#data".equals(id)) return R.drawable.ic_data;
        if ("#hotspot".equals(id)) return R.drawable.ic_hotspot;
        if ("#sticky".equals(id)) return R.drawable.ic_sticky;
        if ("#toasttimer".equals(id)) return R.drawable.ic_toasttimer;
        if ("#farm".equals(id)) return R.drawable.ic_farm;
        if ("#dmgcalc".equals(id)) return R.drawable.ic_dmgcalc;
        if ("#bpm".equals(id)) return R.drawable.ic_bpm;
        if ("#menu".equals(id)) return R.drawable.ic_settings;
        return R.drawable.ic_settings;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static List<String> list(Context c, String key) {
        String raw = prefs(c).getString(key, "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static void write(Context c, String key, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs(c).edit().putString(key, sb.toString()).apply();
    }

    /** The always-present menu button cannot be disabled. */
    static boolean enabled(Context c, String id) {
        if ("#menu".equals(id) || id != null && id.startsWith("app:")) return true;
        return !list(c, KEY_DISABLED).contains(id);
    }

    static void setEnabled(Context c, String id, boolean on) {
        List<String> off = list(c, KEY_DISABLED);
        boolean has = off.contains(id);
        if (on && has) {
            off.remove(id);
            write(c, KEY_DISABLED, off);
        } else if (!on && !has) {
            off.add(id);
            write(c, KEY_DISABLED, off);
        }
    }

    static boolean pinned(Context c, String id) {
        return list(c, KEY_PINNED).contains(id);
    }

    static void setPinned(Context c, String id, boolean on) {
        List<String> pin = list(c, KEY_PINNED);
        boolean has = pin.contains(id);
        if (on && !has) {
            pin.add(id);
            write(c, KEY_PINNED, pin);
        } else if (!on && has) {
            pin.remove(id);
            write(c, KEY_PINNED, pin);
        }
    }

    static int enabledCount(Context c) {
        int n = 0;
        for (Item it : all()) if (enabled(c, it.id)) n++;
        return n;
    }

    /**
     * Items the dock should draw: pinned tools first (in pin order), then the
     * rest in the order saved by the sidebar editor. Disabled tools are gone.
     */
    static List<String> menuItems(Context c) {
        List<String> pinned = list(c, KEY_PINNED);
        List<String> out = new ArrayList<>();
        for (String id : pinned) {
            if (enabled(c, id) && Side.has(c, id) && !out.contains(id)) out.add(id);
        }
        for (String id : Side.items(c)) {
            if (!enabled(c, id) || out.contains(id)) continue;
            out.add(id);
        }
        // Pinned entries whose id was removed from the sidebar are still shown,
        // otherwise pinning would silently do nothing.
        for (String id : pinned) {
            if (enabled(c, id) && !out.contains(id)) out.add(id);
        }
        return out;
    }
}
