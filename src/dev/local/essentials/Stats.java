package dev.local.essentials;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Cheap device stats for the floating HUD. Everything is read from public
 * /proc and /sys files; when a kernel hides a value we return -1 and the HUD
 * prints "--" instead of guessing.
 */
final class Stats {

    private static long lastTotal = -1;
    private static long lastIdle = -1;

    private static final String[] FPS_FILES = {
            "/sys/class/graphics/fb0/measured_fps",
            "/sys/class/graphics/fb0/fps"
    };

    private Stats() {}

    /** Total CPU load in percent, or -1 when nothing readable is available. */
    static synchronized int cpu() {
        int direct = cpuFromProc();
        if (direct >= 0) return direct;
        int freq = cpuFromFreq();
        if (freq >= 0) return freq;
        return cpuFromPolicy();
    }

    /**
     * Fallback for ROMs (vivo/Funtouch often do) that hide /proc/stat: average
     * current scaling frequency vs the hardware maximum across all online cores.
     */
    private static int cpuFromFreq() {
        try {
            File[] dirs = new File("/sys/devices/system/cpu").listFiles();
            if (dirs == null) return -1;
            int sum = 0;
            int n = 0;
            for (File d : dirs) {
                if (!isCpuDir(d.getName())) continue;
                File cf = new File(d, "cpufreq");
                long cur = parseLong(new File(cf, "scaling_cur_freq"));
                long max = parseLong(new File(cf, "cpuinfo_max_freq"));
                if (cur <= 0 || max <= 0) continue;
                sum += (int) Math.min(100, cur * 100 / max);
                n++;
            }
            return n == 0 ? -1 : sum / n;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Second fallback: the aggregated cpufreq policies, in case the per-core
     * cpufreq folders are hidden but /sys/devices/system/cpu/cpufreq remains.
     */
    private static int cpuFromPolicy() {
        int sum = 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            File d = new File("/sys/devices/system/cpu/cpufreq/policy" + i);
            long cur = parseLong(new File(d, "scaling_cur_freq"));
            long max = parseLong(new File(d, "cpuinfo_max_freq"));
            if (cur <= 0 || max <= 0) continue;
            sum += (int) Math.min(100, cur * 100 / max);
            n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    private static boolean isCpuDir(String name) {
        if (name == null || !name.startsWith("cpu") || name.length() <= 3) return false;
        for (int i = 3; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Reads /proc/stat; -1 when the ROM blocks the file. */
    private static int cpuFromProc() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/stat"));
            String line = r.readLine();
            if (line == null) return -1;
            String[] p = line.trim().split("\\s+");
            if (p.length < 6) return -1;
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            // Some ROMs hand out a stub file whose counters stay at zero.
            if (total <= 0) return -1;
            if (lastTotal < 0 || total <= lastTotal) {
                lastTotal = total;
                lastIdle = idle;
                return 0;
            }
            long dt = total - lastTotal;
            long di = idle - lastIdle;
            lastTotal = total;
            lastIdle = idle;
            int usage = (int) ((dt - di) * 100 / dt);
            return Math.max(0, Math.min(100, usage));
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Refresh rate as the FPS fallback; real per-app FPS needs root. */
    static int fps(Context c) {
        for (String path : FPS_FILES) {
            String raw = readFirstLine(path);
            if (raw == null) continue;
            try {
                float v = Float.parseFloat(raw.trim().split("\\s+")[0]);
                if (v > 0 && v < 500) return Math.round(v);
            } catch (Exception ignored) {
            }
        }
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            float hz = wm == null ? 60f : wm.getDefaultDisplay().getRefreshRate();
            return Math.round(hz);
        } catch (Exception e) {
            return 60;
        }
    }

    /** Battery temperature in whole degrees Celsius, or -1 when unavailable. */
    static int batteryTempC(Context c) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = null;
        try {
            battery = c.registerReceiver(null, filter);
        } catch (Exception ignored) {
        }
        if (battery == null) return -1;
        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        if (tenths <= 0) return -1;
        return Math.round(tenths / 10f);
    }

    /** Used RAM with an adaptive unit: "3.2 GB" or "640 MB". */
    static String ram(Context c) {
        try {
            ActivityManager am = (ActivityManager)
                    c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "--";
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long used = Math.max(0, mi.totalMem - mi.availMem);
            if (used >= 1073741824L) {
                return String.format(java.util.Locale.US, "%.1f GB", used / 1073741824d);
            }
            long mb = Math.max(1, Math.round(used / 1048576d));
            return mb + " MB";
        } catch (Exception e) {
            return "--";
        }
    }

    private static long parseLong(File f) {
        String raw = readFirstLine(f.getAbsolutePath());
        if (raw == null) return -1;
        try {
            return Long.parseLong(raw.trim().split("\\s+")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static long maxOf(File f) {
        String raw = readFirstLine(f.getAbsolutePath());
        if (raw == null) return -1;
        long best = -1;
        for (String part : raw.trim().split("\\s+")) {
            try {
                long v = Long.parseLong(part);
                if (v > best) best = v;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static String readFirstLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
    }
}
