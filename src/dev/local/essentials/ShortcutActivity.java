package dev.local.essentials;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Invisible trampoline used by home-screen shortcuts and Quick Settings tiles.
 * It performs one small action and closes immediately, so nothing flashes.
 */
public class ShortcutActivity extends Activity {

    private static final int REQ_CAMERA = 61;

    private String pending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent() == null ? null : getIntent().getStringExtra("action");
        if (action == null && getIntent() != null && getIntent().getAction() != null) {
            String a = getIntent().getAction();
            int dot = a.lastIndexOf('.');
            action = dot >= 0 ? a.substring(dot + 1) : a;
        }
        run(action);
    }

    private void run(String action) {
        if ("torch".equals(action)) {
            if (Torch.id(this) == null) {
                toast("Máy không có đèn flash");
                finish();
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                pending = action;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return;
            }
            if (!Torch.toggle(this)) toast("Không bật được đèn pin");
            else toast(Torch.isOn() ? "Đèn pin: BẬT" : "Đèn pin: TẮT");
            finish();
            return;
        }

        if ("caffeine".equals(action)) {
            boolean next = !Side.caffeineOn(this);
            Side.setCaffeineOn(this, next);
            if (next) ensureSidebar();
            if (SidebarService.running()) SidebarService.refreshCaffeine();
            toast(next ? "Giữ sáng màn hình: BẬT" : "Giữ sáng màn hình: TẮT");
            finish();
            return;
        }

        if ("timer".equals(action)) {
            TimerStore.startMinutes(this, 0, 5);
            if (SidebarService.running()) SidebarService.refreshTimers();
            ensureSidebar();
            toast("Đã hẹn giờ 5 phút");
            finish();
            return;
        }

        if ("search".equals(action)) {
            startActivity(new Intent(this, SearchActivity.class));
            finish();
            return;
        }

        finish();
    }

    private void ensureSidebar() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Cần quyền hiển thị trên ứng dụng khác");
            return;
        }
        Side.setOn(this, true);
        try {
            Intent i = new Intent(this, SidebarService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                run(pending == null ? "torch" : pending);
            } else {
                toast("Cần quyền Camera để bật đèn pin");
                finish();
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
