package dev.local.essentials;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One place that shows every system permission the app can use. */
public class PermissionsActivity extends Activity {

    private static final int REQ_CAMERA = 71;
    private static final int REQ_NOTIF = 72;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 6));
        TextView back = Ui.text(this, "\u2039", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.press(back, 0.88f);
        header.addView(Ui.text(this, "Quyền hệ thống", 22, Ui.TEXT, true));
        root.addView(header);

        TextView note = Ui.text(this,
                "Mỗi quyền chỉ được xin khi bạn dùng công cụ cần nó. "
                        + "Màn hình này để kiểm tra và cấp trước khi cần.",
                12, Ui.MUTED, false);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams np = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        np.leftMargin = Ui.dp(this, 20);
        np.rightMargin = Ui.dp(this, 20);
        np.topMargin = Ui.dp(this, 6);
        np.bottomMargin = Ui.dp(this, 10);
        root.addView(note, np);

        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        list.setPadding(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 28));
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, Ui.fill(1f));

        setContentView(root);
        Ui.rise(header, 20, 200);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();

        add(R.drawable.ic_side, "Hiển thị trên ứng dụng khác",
                "Bắt buộc để thanh bên, HUD và các bảng nổi hoạt động",
                Settings.canDrawOverlays(this), new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                });

        add(R.drawable.ic_camera, "Trợ năng",
                "Dùng cho chụp màn hình và các phím điều hướng",
                accessibilityOn(), new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });

        add(R.drawable.ic_flash, "Camera / đèn pin",
                "Chỉ dùng để bật đèn flash",
                checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA},
                                REQ_CAMERA);
                    }
                });

        add(R.drawable.ic_bell, "Thông báo",
                "Báo khi hẹn giờ xong và cho thanh bên chạy nền",
                notificationsOn(), new View.OnClickListener() {
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestPermissions(
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                    REQ_NOTIF);
                        } else {
                            openNotificationSettings();
                        }
                    }
                });

        add(R.drawable.ic_brightness, "Ghi cài đặt hệ thống",
                "Dùng cho khoá độ sáng màn hình",
                Settings.System.canWrite(this), new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                });

        add(R.drawable.ic_dnd, "Không làm phiền",
                "Dùng cho nút bật/tắt chế độ im lặng",
                dndOn(), new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(new Intent(
                                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    }
                });

        if (Build.VERSION.SDK_INT >= 31) {
            add(R.drawable.ic_multitimer, "Báo thức chính xác",
                    "Giúp hẹn giờ kêu đúng giây kể cả khi máy ngủ",
                    exactAlarmOn(), new View.OnClickListener() {
                        public void onClick(View v) {
                            openSettings(new Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    });
        }

        add(R.drawable.ic_battery_alert, "Bỏ tối ưu pin",
                "Giữ hẹn giờ và cảnh báo pin chạy ổn định",
                ignoringBattery(), new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    }
                });

        Ui.stagger(list, 30, 22);
    }

    private void add(int iconRes, String title, String desc, boolean granted,
                     View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.bg(Ui.ACCENT_SOFT, this, 12));
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.ACCENT);
        box.addView(icon, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
        row.addView(box, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout text = Ui.col(this);
        LinearLayout.LayoutParams tp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 12);
        text.addView(Ui.text(this, title, 14, Ui.TEXT, true));
        TextView d = Ui.text(this, desc, 11, Ui.MUTED, false);
        d.setLayoutParams(Ui.lp());
        Ui.margin(this, d, 0, 3, 0, 0);
        d.setLineSpacing(Ui.dp(this, 2), 1f);
        text.addView(d);
        row.addView(text, tp);

        TextView pill = Ui.text(this, granted ? "Đã cấp" : "Chưa cấp", 11,
                granted ? 0xFF0B1020 : Ui.TEXT, true);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(Ui.bg(granted ? 0xFF6FE3A1 : 0x33FFFFFF, this, 12));
        pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(pill);

        row.setOnClickListener(click);
        row.setClickable(true);
        Ui.press(row, 0.98f);

        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 9);
        list.addView(row, p);
    }

    private boolean accessibilityOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean notificationsOn() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean dndOn() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean exactAlarmOn() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            return am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ignoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void openSettings(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }
}
