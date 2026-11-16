package dev.local.essentials;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Installed app list, shared by the sidebar picker. */
final class Apps {

    static final class App {
        final String label;
        final String pkg;
        final Drawable icon;

        App(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private Apps() {}

    static List<App> load(Context c) {
        List<App> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(i, 0);
        } catch (Exception e) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception e) {
                icon = null;
            }
            out.add(new App(String.valueOf(ri.loadLabel(pm)), pkg, icon));
        }
        final Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        collator.setStrength(Collator.PRIMARY);
        out.sort(new java.util.Comparator<App>() {
            public int compare(App a, App b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    static Drawable iconOf(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    static String labelOf(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    static void launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
