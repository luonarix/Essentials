package dev.local.essentials;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * MediaProjection foreground service: full screen shot, region shot, recorder.
 * Everything here runs in a service so the capture survives the picker UI
 * being closed and the sidebar being hidden.
 */
public class CaptureService extends Service {

    static final String ACTION_SHOT = "shot";
    static final String ACTION_PICK = "pick";
    static final String ACTION_RECORD = "record";
    static final String ACTION_STOP = "stop";

    static final String EXTRA_ACTION = "action";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_HEIGHT = "height";
    static final String EXTRA_FPS = "fps";

    private static final String CHANNEL = "capture";
    private static final int NOTIF_ID = 42;
    private static final int TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    static boolean recording;
    static CaptureService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private Recorder recorder;
    private View regionRoot;
    private View stopPill;
    private boolean sidebarHidden;

    private final MediaProjection.Callback projCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (recording) stopRecording();
            else endSession();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }

        startForegroundSafe(false);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm == null ? null : mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            toast("Không lấy được quyền chụp màn hình");
            endSession();
            return START_NOT_STICKY;
        }
        try {
            projection.registerCallback(projCallback, main);
        } catch (Exception ignored) {
        }

        String action = intent.getAction();
        if (ACTION_SHOT.equals(action)) {
            hideSidebar();
            main.postDelayed(new Runnable() {
                public void run() {
                    captureScreen(null);
                }
            }, 650);
        } else if (ACTION_PICK.equals(action)) {
            hideSidebar();
            main.postDelayed(new Runnable() {
                public void run() {
                    showRegionPicker();
                }
            }, 400);
        } else if (ACTION_RECORD.equals(action)) {
            startRecording(intent.getIntExtra(EXTRA_HEIGHT, 1080),
                    intent.getIntExtra(EXTRA_FPS, 30));
        } else {
            endSession();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handlerCleanup();
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        releaseProjection();
        removeRegion();
        removeStopPill();
        restoreSidebar();
        recording = false;
        if (current == this) current = null;
        super.onDestroy();
    }

    private void handlerCleanup() {
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------ notification

    private void startForegroundSafe(boolean rec) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Chụp & ghi",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(rec ? "Đang ghi màn hình" : "Essentials")
                .setContentText(rec ? "Chạm Dừng để kết thúc" : "Đang chụp màn hình…")
                .setSmallIcon(R.drawable.ic_camera)
                .setOngoing(true);

        if (rec) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_note),
                    "Dừng", stop).build());
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, b.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    // ------------------------------------------------------------ screenshots

    private Point screenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point p = new Point();
        if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        if (p.x <= 0 || p.y <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            p.x = dm.widthPixels;
            p.y = dm.heightPixels;
        }
        return p;
    }

    private void captureScreen(final Rect crop) {
        final Point size = screenSize();
        final int w = size.x;
        final int h = size.y;
        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);

        final VirtualDisplay display;
        try {
            display = projection.createVirtualDisplay("ess-shot", w, h,
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, main);
        } catch (Exception e) {
            reader.close();
            toast("Không chụp được màn hình");
            endSession();
            return;
        }

        final boolean[] done = new boolean[1];
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader r) {
                if (done[0]) return;
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                } catch (Exception ignored) {
                }
                if (img == null) return;
                done[0] = true;
                Bitmap bmp = null;
                try {
                    bmp = imageToBitmap(img, w, h);
                } catch (Exception ignored) {
                }
                try {
                    img.close();
                } catch (Exception ignored) {
                }
                r.setOnImageAvailableListener(null, null);
                try {
                    r.close();
                } catch (Exception ignored) {
                }
                try {
                    display.release();
                } catch (Exception ignored) {
                }

                if (bmp == null) {
                    toast("Không đọc được ảnh");
                    endSession();
                    return;
                }
                if (crop != null) {
                    Rect c = new Rect(crop);
                    c.left = Math.max(0, Math.min(c.left, bmp.getWidth() - 2));
                    c.top = Math.max(0, Math.min(c.top, bmp.getHeight() - 2));
                    c.right = Math.max(c.left + 2, Math.min(c.right, bmp.getWidth()));
                    c.bottom = Math.max(c.top + 2, Math.min(c.bottom, bmp.getHeight()));
                    try {
                        Bitmap cut = Bitmap.createBitmap(bmp, c.left, c.top,
                                c.right - c.left, c.bottom - c.top);
                        bmp.recycle();
                        bmp = cut;
                    } catch (Exception ignored) {
                    }
                }
                String path = Shots.saveImage(CaptureService.this, bmp);
                bmp.recycle();
                toast(path != null ? "Đã lưu: " + path : "Không lưu được ảnh");
                endSession();
            }
        }, main);

        main.postDelayed(new Runnable() {
            public void run() {
                if (done[0]) return;
                done[0] = true;
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
                try {
                    display.release();
                } catch (Exception ignored) {
                }
                toast("Hết thời gian chụp");
                endSession();
            }
        }, 4000);
    }

    private Bitmap imageToBitmap(Image img, int w, int h) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        int padded = w + rowPadding / pixelStride;
        Bitmap full = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        if (padded == w) return full;
        Bitmap cut = Bitmap.createBitmap(full, 0, 0, w, h);
        full.recycle();
        return cut;
    }

    // ------------------------------------------------------------ region picker

    private void showRegionPicker() {
        final FrameLayout root = new FrameLayout(this);
        final PickView pick = new PickView(this);
        root.addView(pick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = Ui.text(this, "Kéo để chọn vùng cần chụp", 13, Ui.TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.framed(0xCC171C24, this, 14));
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(42);
        root.addView(hint, hp);

        final LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.framed(0xE6171C24, this, 16));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setVisibility(View.GONE);

        TextView cancel = Ui.text(this, "Huỷ", 13, Ui.MUTED, false);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeRegion();
                toast("Đã huỷ");
                endSession();
            }
        });
        bar.addView(cancel);

        TextView ok = Ui.text(this, "Lưu vùng chọn", 13, 0xFF0B1020, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(Ui.bg(Ui.ACCENT, this, 12));
        ok.setPadding(dp(18), dp(10), dp(18), dp(10));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Rect r = pick.selection();
                if (r == null || r.width() < dp(8) || r.height() < dp(8)) {
                    toast("Vùng chọn quá nhỏ");
                    return;
                }
                removeRegion();
                main.postDelayed(new Runnable() {
                    public void run() {
                        captureScreen(r);
                    }
                }, 320);
            }
        });
        bar.addView(ok);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bp.bottomMargin = dp(48);
        root.addView(bar, bp);

        pick.onSelection = new Runnable() {
            public void run() {
                hint.setVisibility(View.GONE);
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(0f);
                bar.animate().alpha(1f).setDuration(160).start();
            }
        };

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm().addView(root, lp);
            regionRoot = root;
        } catch (Exception e) {
            toast("Không mở được vùng chọn");
            endSession();
        }
    }

    private void removeRegion() {
        if (regionRoot == null) return;
        try {
            wm().removeView(regionRoot);
        } catch (Exception ignored) {
        }
        regionRoot = null;
    }

    /** Dim overlay with a draggable rectangle. */
    private class PickView extends View {
        private final Paint dim = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sel = new RectF();
        private float startX, startY;
        private boolean dragging;
        Runnable onSelection;

        PickView(Context c) {
            super(c);
            dim.setColor(0x99000000);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(2));
            border.setColor(Ui.ACCENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (sel.width() < 2 || sel.height() < 2) {
                canvas.drawRect(0, 0, w, h, dim);
                return;
            }
            canvas.drawRect(0, 0, w, sel.top, dim);
            canvas.drawRect(0, sel.bottom, w, h, dim);
            canvas.drawRect(0, sel.top, sel.left, sel.bottom, dim);
            canvas.drawRect(sel.right, sel.top, w, sel.bottom, dim);
            canvas.drawRect(sel, border);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getX();
                    startY = e.getY();
                    sel.set(startX, startY, startX, startY);
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    sel.set(Math.min(startX, e.getX()), Math.min(startY, e.getY()),
                            Math.max(startX, e.getX()), Math.max(startY, e.getY()));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    dragging = false;
                    invalidate();
                    if (onSelection != null && sel.width() > dp(8) && sel.height() > dp(8)) {
                        onSelection.run();
                    }
                    return true;
                default:
                    return false;
            }
        }

        Rect selection() {
            if (sel.width() < 2 || sel.height() < 2) return null;
            return new Rect(Math.round(sel.left), Math.round(sel.top),
                    Math.round(sel.right), Math.round(sel.bottom));
        }
    }

    // ------------------------------------------------------------ recording

    private void startRecording(int height, int fps) {
        int maxH = maxEncoderHeight();
        if (height > maxH) {
            toast("Máy chỉ ghi được tối đa " + maxH + "p, đã hạ xuống");
            height = maxH;
        }
        Point size = screenSize();
        float aspect = (float) size.x / (float) size.y;
        int width = Math.round(height * aspect);
        if (width % 2 != 0) width++;
        if (height % 2 != 0) height++;

        hideSidebar();
        File out = new File(getCacheDir(), "ess_rec_" + System.currentTimeMillis() + ".mp4");

        recorder = new Recorder(this, projection, width, height, fps, out,
                new Recorder.Listener() {
                    public void onSaved(final File file) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String path =
                                        Shots.saveVideo(CaptureService.this, file);
                                file.delete();
                                main.post(new Runnable() {
                                    public void run() {
                                        toast(path != null
                                                ? "Đã lưu video: " + path
                                                : "Không lưu được video");
                                        finishRecording();
                                    }
                                });
                            }
                        }, "rec-save").start();
                    }

                    public void onError(final String message) {
                        main.post(new Runnable() {
                            public void run() {
                                toast(message);
                                finishRecording();
                            }
                        });
                    }
                });

        main.postDelayed(new Runnable() {
            public void run() {
                if (recorder == null) return;
                recording = true;
                recorder.start();
                startForegroundSafe(true);
                maybeShowStopPill();
            }
        }, 800);
    }

    private void stopRecording() {
        final Recorder r = recorder;
        recorder = null;
        if (r == null) {
            finishRecording();
            return;
        }
        // muxer stop + file copy are slow; keep the main thread free
        new Thread(new Runnable() {
            public void run() {
                try {
                    r.stop();
                } catch (Exception e) {
                    main.post(new Runnable() {
                        public void run() {
                            toast("Không kết thúc được ghi hình");
                            finishRecording();
                        }
                    });
                }
            }
        }, "rec-stop").start();
    }

    private void finishRecording() {
        recording = false;
        removeStopPill();
        restoreSidebar();
        stopSelf();
    }

    /** Highest height the device's AVC encoder accepts (capped at 1440). */
    private int maxEncoderHeight() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(type)) continue;
                    android.util.Range<Integer> r = info.getCapabilitiesForType(type)
                            .getVideoCapabilities().getSupportedHeights();
                    if (r != null && r.getUpper() != null && r.getUpper() > 0) {
                        return Math.min(1440, r.getUpper());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 1080;
    }

    /**
     * If notifications are blocked the "Dừng" action is invisible, so show a
     * tiny on-screen button instead. It is captured by the recording, which is
     * why it only appears when it is the only way to stop.
     */
    private void maybeShowStopPill() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.areNotificationsEnabled()) return;
        if (stopPill != null) return;

        TextView v = Ui.text(this, "\u25a0  Dừng ghi", 12, 0xFFFFE3E3, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.bg(0xE61C1013, 0xFFE05A5A, this, 18, 1));
        v.setPadding(dp(14), dp(9), dp(14), dp(9));
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                stopRecording();
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                TYPE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(56);
        try {
            wm().addView(v, lp);
            stopPill = v;
        } catch (Exception ignored) {
        }
    }

    private void removeStopPill() {
        if (stopPill == null) return;
        try {
            wm().removeView(stopPill);
        } catch (Exception ignored) {
        }
        stopPill = null;
    }

    // ------------------------------------------------------------ session

    private void endSession() {
        restoreSidebar();
        releaseProjection();
        stopSelf();
    }

    private void releaseProjection() {
        if (projection == null) return;
        try {
            projection.unregisterCallback(projCallback);
        } catch (Exception ignored) {
        }
        try {
            projection.stop();
        } catch (Exception ignored) {
        }
        projection = null;
    }

    private void hideSidebar() {
        if (sidebarHidden) return;
        sidebarHidden = true;
        SidebarService.hideForCapture();
    }

    private void restoreSidebar() {
        if (!sidebarHidden) return;
        sidebarHidden = false;
        SidebarService.restoreAfterCapture();
    }

    // ------------------------------------------------------------ misc

    private WindowManager wm() {
        return (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
