package dev.local.essentials;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.Locale;

/**
 * Accessibility-only screenshot engine. Android lets an enabled accessibility
 * service grab the screen without the MediaProjection consent dialog, which is
 * what the phone's own screenshot tool effectively does.
 *
 * Used for still images only; the app has no screen recorder.
 */
public class ShotService extends AccessibilityService {

    interface ShotCallback {
        void onShot(Bitmap bmp);

        void onFail(String message);
    }

    static volatile ShotService instance;

    static boolean ready() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Screenshots do not need events; this feeds the notification peeker.
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (pkg.equals(getPackageName())) return;

        CharSequence clsCs = event.getClassName();
        String cls = clsCs == null ? "" : clsCs.toString().toLowerCase(Locale.ROOT);
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !cls.contains("toast")) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence cs : texts) {
                if (cs == null) continue;
                String t = cs.toString().trim();
                if (t.isEmpty() || sb.indexOf(t) >= 0) continue;
                if (sb.length() > 0) sb.append(" \u00b7 ");
                sb.append(t);
            }
        }
        if (sb.length() == 0 && event.getContentDescription() != null) {
            sb.append(event.getContentDescription().toString().trim());
        }
        if (sb.length() == 0) return;

        String app = pkg.isEmpty() ? "Hệ thống" : Apps.labelOf(this, pkg);
        Peeker.add(System.currentTimeMillis(), app, sb.toString());
    }

    @Override
    public void onInterrupt() {
    }

    /** Grabs the default display; the bitmap callback runs on the main thread. */
    static void take(final ShotCallback cb) {
        final ShotService s = instance;
        if (s == null) {
            cb.onFail("Chưa bật Trợ năng cho Essentials");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            cb.onFail("Máy không hỗ trợ chụp qua Trợ năng");
            return;
        }
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap out = null;
                            HardwareBuffer buf = null;
                            try {
                                buf = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        buf, result.getColorSpace());
                                if (hw != null) {
                                    out = hw.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } catch (Exception e) {
                                out = null;
                            }
                            try {
                                if (buf != null) buf.close();
                            } catch (Exception ignored) {
                            }
                            if (out == null) cb.onFail("Không đọc được ảnh");
                            else cb.onShot(out);
                        }

                        public void onFailure(int errorCode) {
                            cb.onFail(message(errorCode));
                        }
                    });
        } catch (Exception e) {
            cb.onFail("Không chụp được màn hình");
        }
    }

    /** Taps one point with a synthetic gesture; used by the auto clicker. */
    static boolean tap(float x, float y) {
        final ShotService s = instance;
        if (s == null) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 40);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke).build();
            return s.dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs a global accessibility action (back, home, power dialog, ...). */
    static boolean global(int action) {
        ShotService s = instance;
        if (s == null) return false;
        try {
            return s.performGlobalAction(action);
        } catch (Exception e) {
            return false;
        }
    }

    private static String message(int code) {
        if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            return "Chụp quá nhanh, thử lại sau 1 giây";
        }
        if (code == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) {
            return "Màn hình không chụp được";
        }
        return "Không chụp được màn hình";
    }
}
