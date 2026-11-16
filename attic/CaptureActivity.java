package dev.local.essentials;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible handshake activity: receives the choice made in the sidebar capture
 * tab, asks for RECORD_AUDIO when needed, then shows the system MediaProjection
 * consent dialog and hands the token to CaptureService.
 *
 * It never draws anything (Theme.Ghost), so the tab stays the only UI.
 */
public class CaptureActivity extends Activity {

    private static final int REQ_PROJECTION = 501;
    private static final int REQ_AUDIO = 502;

    private String action;
    private int height = 1080;
    private int fps = 30;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        action = in.getStringExtra(CaptureService.EXTRA_ACTION);
        height = in.getIntExtra(CaptureService.EXTRA_HEIGHT, 1080);
        fps = in.getIntExtra(CaptureService.EXTRA_FPS, 30);
        if (action == null) {
            finish();
            return;
        }
        proceed();
    }

    private void proceed() {
        if (CaptureService.ACTION_RECORD.equals(action)
                && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO);
            return;
        }
        launchConsent();
    }

    private void launchConsent() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("Máy không hỗ trợ chụp màn hình");
            finish();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            toast("Không mở được hộp thoại quyền");
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code == REQ_AUDIO) {
            // Continue either way: the recorder falls back to a silent video.
            launchConsent();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJECTION) return;
        if (res != RESULT_OK || data == null) {
            toast("Đã huỷ");
            finish();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.setAction(action);
        svc.putExtra(CaptureService.EXTRA_CODE, res);
        svc.putExtra(CaptureService.EXTRA_DATA, data);
        svc.putExtra(CaptureService.EXTRA_HEIGHT, height);
        svc.putExtra(CaptureService.EXTRA_FPS, fps);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception e) {
            toast("Không khởi động được dịch vụ chụp");
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
