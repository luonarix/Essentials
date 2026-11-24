package dev.local.essentials;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Publishes the most-used tools as home-screen shortcuts. */
final class Shortcuts {

    private Shortcuts() {}

    static void sync(Context c) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = (ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return;
            List<ShortcutInfo> list = new ArrayList<>();
            list.add(item(c, "torch", "Đèn pin", R.drawable.ic_flash));
            list.add(item(c, "caffeine", "Giữ sáng màn hình", R.drawable.ic_caffeine));
            list.add(item(c, "timer", "Hẹn giờ 5 phút", R.drawable.ic_multitimer));
            list.add(item(c, "search", "Tìm công cụ", R.drawable.ic_quick));
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {
        }
    }

    private static ShortcutInfo item(Context c, String action, String label, int icon) {
        Intent i = new Intent(c, ShortcutActivity.class);
        i.setAction("dev.local.essentials.SHORTCUT_" + action);
        i.putExtra("action", action);
        return new ShortcutInfo.Builder(c, "essentials_" + action)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(c, icon))
                .setIntent(i)
                .build();
    }
}
