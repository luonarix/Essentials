package dev.local.essentials;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Persisted countdown state plus exact-alarm delivery of the finish alert. */
final class TimerStore {

    static final String PREF = "timers";
    static final String CHANNEL = "timer_done";
    static final String ACTION = "dev.local.essentials.TIMER_DONE";
    static final int SLOTS = 4;

    /** Guards against the service and the alarm posting the same alert twice. */
    private static final long[] lastNotify = new long[SLOTS];

    private TimerStore() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static boolean running(Context c, int slot) {
        return p(c).getBoolean("run_" + slot, false);
    }

    static long endWall(Context c, int slot) {
        return p(c).getLong("end_" + slot, 0L);
    }

    static long total(Context c, int slot) {
        return p(c).getLong("total_" + slot, 0L);
    }

    static long remain(Context c, int slot) {
        return p(c).getLong("remain_" + slot, 0L);
    }

    static void save(Context c, int slot, boolean running,
                     long wallEnd, long remain, long total) {
        p(c).edit()
                .putBoolean("run_" + slot, running)
                .putLong("end_" + slot, wallEnd)
                .putLong("remain_" + slot, Math.max(0L, remain))
                .putLong("total_" + slot, Math.max(0L, total))
                .apply();
    }

    static void clear(Context c, int slot) {
        p(c).edit()
                .putBoolean("run_" + slot, false)
                .putLong("end_" + slot, 0L)
                .putLong("remain_" + slot, 0L)
                .apply();
        cancel(c, slot);
    }

    static int activeCount(Context c) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) if (running(c, i)) n++;
        return n;
    }

    /** Starts (or restarts) one slot without needing the sidebar service. */
    static void startMinutes(Context c, int slot, int minutes) {
        long total = Math.max(1, minutes) * 60000L;
        long end = System.currentTimeMillis() + total;
        save(c, slot, true, end, total, total);
        schedule(c, slot, end);
    }

    static PendingIntent alarmIntent(Context c, int slot) {
        Intent i = new Intent(c, TimerReceiver.class);
        i.setAction(ACTION);
        i.putExtra("slot", slot);
        return PendingIntent.getBroadcast(c, 400 + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, int slot, long wallEnd) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c, slot);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, wallEnd, 60000L, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallEnd, pi);
            }
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, wallEnd, pi);
            } catch (Exception ignored) {
            }
        }
    }

    static void cancel(Context c, int slot) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(alarmIntent(c, slot));
        } catch (Exception ignored) {
        }
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Hẹn giờ", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Báo khi đồng hồ đếm ngược chạy xong");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /** Posts the finish alert once even if both the service and alarm race. */
    static void notifyDone(Context c, int slot) {
        long now = System.currentTimeMillis();
        synchronized (lastNotify) {
            if (now - lastNotify[slot] < 4000L) return;
            lastNotify[slot] = now;
        }
        ensureChannel(c);
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            PendingIntent pi = PendingIntent.getActivity(c, 500 + slot,
                    new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            b.setContentTitle("Hết giờ · Hẹn " + (slot + 1))
                    .setContentText("Đồng hồ đếm ngược đã chạy xong")
                    .setSmallIcon(R.drawable.ic_multitimer)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL);
            nm.notify(120 + slot, b.build());
        } catch (Exception ignored) {
        }
    }

    /** Re-arms timers after a reboot, or reports ones that ended while off. */
    static void restoreAfterBoot(Context c) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < SLOTS; i++) {
            if (!running(c, i)) continue;
            long end = endWall(c, i);
            if (end <= now) {
                clear(c, i);
                notifyDone(c, i);
            } else {
                schedule(c, i, end);
            }
        }
    }
}
