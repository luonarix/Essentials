package dev.local.essentials;

/*
 * GPU reading removed from the shipping app in v5.5 (versionCode 23): the vivo
 * V21 5G / Funtouch kernel blocks every /sys and /proc GPU counter, so the HUD
 * only ever printed "--%". Kept here unchanged for reference in case a future
 * device or a rooted ROM exposes one of these files.
 *
 * It used to be part of Stats: cpu()/fps()/ram() live on there; these helpers
 * rely on Stats.parseLong / Stats.maxOf / Stats.readFirstLine, which are private,
 * so copy them over (or make them package-private) before re-using this file.
 */

final class StatsGpu {

    private StatsGpu() {}

    private static final String[] GPU_FILES = {
            "/sys/module/ged/parameters/gpu_loading",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/mali.0/utilisation",
            "/proc/mali/utilization"
    };

    /** GPU load in percent when the kernel exposes it, else -1. */
    static int gpu() {
        int direct = gpuFromFiles();
        if (direct >= 0) return direct;
        return gpuFromDevfreq();
    }

    private static int gpuFromFiles() {
        for (String path : GPU_FILES) {
            String raw = readFirstLine(path);
            if (raw == null) continue;
            raw = raw.trim();
            try {
                if (path.endsWith("gpubusy")) {
                    String[] p = raw.split("\\s+");
                    if (p.length >= 2) {
                        long busy = Long.parseLong(p[0]);
                        long all = Long.parseLong(p[1]);
                        if (all <= 0) continue;
                        return (int) Math.max(0, Math.min(100, busy * 100 / all));
                    }
                } else {
                    int v = (int) Float.parseFloat(raw);
                    if (v >= 0 && v <= 100) return v;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /** GPU devfreq fallback: current vs max clock of the "gpu/mali/kgsl" node. */
    private static int gpuFromDevfreq() {
        try {
            File[] dirs = new File("/sys/class/devfreq").listFiles();
            if (dirs == null) return -1;
            for (File d : dirs) {
                String n = d.getName().toLowerCase(java.util.Locale.US);
                if (!n.contains("gpu") && !n.contains("mali") && !n.contains("kgsl")) {
                    continue;
                }
                long cur = parseLong(new File(d, "cur_freq"));
                if (cur <= 0) continue;
                long max = parseLong(new File(d, "max_freq"));
                if (max <= 0) max = maxOf(new File(d, "available_frequencies"));
                if (max <= 0) continue;
                return (int) Math.min(100, cur * 100 / max);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
