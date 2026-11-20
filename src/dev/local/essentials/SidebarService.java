package dev.local.essentials;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Edge sidebar drawn over other apps (TYPE_APPLICATION_OVERLAY).
 * Minimal motion: 180ms slide+fade, handle chevron rotates, border brightens.
 * Glass panels: sidebar (right), quick note (right), notes list/detail (left),
 * capture options (left).
 */
public class SidebarService extends Service {

    static final String CHANNEL = "sidebar";
    private static final int NOTIF_ID = 41;
    private static final int TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final long AUTO_HIDE_MS = 12000L;
    private static final long TUCK_DELAY_MS = 3000L;
    private static final int ANIM_MS = 220;
    /** Tabs fade/slide in and out with the same duration, mirrored. */
    private static final int TAB_ANIM_MS = 220;
    private static final int TOGGLE_GREEN = 0xFF6FE3A1;
    private static final int TOGGLE_GREEN_BOX = 0x336FE3A1;
    private static final int TOGGLE_CYAN = 0xFF27D3E0;
    private static final int TOGGLE_CYAN_BOX = 0x3327D3E0;
    private static final int TOGGLE_AMBER = 0xFFFFB05A;
    private static final int TOGGLE_AMBER_BOX = 0x33FFB05A;
    private static final int EDGE_INSET_DP = 10;
    private static final int PANEL_INSET_DP = 42;

    // handle resting / tucked geometry
    private static final int HANDLE_W_DP = 40;        // window is wider than the chip
    private static final int HANDLE_CHIP_DP = 26;     // chip when resting
    private static final int HANDLE_H_NORMAL = 68;
    private static final int HANDLE_H_TUCKED = 118;
    private static final int HANDLE_X_NORMAL = 10;
    private static final int HANDLE_X_TUCKED = 0;
    private static final int HANDLE_BAR_DP = 18;      // tucked: visible sliver, in dp
    private static final int HANDLE_TUCK_OVERHANG_DP = 7; // half the rounded end sits past the edge
    private static final int HANDLE_CHEVRON_DP = 16;
    private static final int HANDLE_CHEVRON_MARGIN_DP = 5;
    private static final float HANDLE_A_NORMAL = 0.85f;
    private static final float HANDLE_A_TUCKED = 0.5f;


    /** Glass panels: 0.85 alpha, so the app behind still shows through. */
    private static final int GLASS = 0xD9171C24;
    private static final int GLASS_HANDLE = 0xFF1D2A44;
    private static final int ITEM_BG = 0x1FFFFFFF;
    private static final int ITEM_BG_SOFT = 0x14FFFFFF;

    private static final int STROKE_CLOSED = 0x80213A66;
    private static final int STROKE_OPEN = 0xFF6E9BFF;

    // panel kinds
    private static final int KIND_NONE = 0;
    private static final int KIND_MENU = 1;
    private static final int KIND_EDITOR = 2;
    private static final int KIND_QUICKNOTE = 3;
    private static final int KIND_NOTES = 4;
    private static final int KIND_NOTE_DETAIL = 5;
    private static final int KIND_SHOT = 6;
    private static final int KIND_VOLUME = 7;
    private static final int KIND_DOCK = 8;

    static SidebarService current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = new Runnable() {
        public void run() {
            closePanel();
        }
    };
    private final Runnable tuckIdle = new Runnable() {
        public void run() {
            tuckHandle();
        }
    };

    private WindowManager wm;
    private View handle;
    private WindowManager.LayoutParams handleParams;
    private TextView chevron;
    private GradientDrawable handleBg;
    private View handleChip;
    private int chipWidthPx;
    private int strokeColor = STROKE_CLOSED;
    private int strokePx;
    private boolean tucked;
    private boolean untuckingAtDown;
    private ValueAnimator handleAnim;

    private View panel;          // full-screen host window
    private View panelCard;      // the visible glass card inside it
    private WindowManager.LayoutParams panelParams;
    private boolean panelOpen;
    private boolean panelOnRight = true;
    private int panelKind = KIND_NONE;

    // Dock: the menu is permanent, tool tabs stack beside it.
    private FrameLayout dockRoot;
    private LinearLayout dockMenu;
    private LinearLayout dockTabs;
    private ScrollView dockScroll;
    private final Map<String, View> tabViews = new LinkedHashMap<>();
    private final Map<String, View> menuButtons = new LinkedHashMap<>();
    private String buildingTab;
    private boolean dockFocusable;

    private static final String TAB_QUICKNOTE = "quicknote";
    private static final String TAB_NOTES = "notes";
    private static final String TAB_NOTEDETAIL = "notedetail";
    private static final String TAB_VOLUME = "volume";
    private static final String TAB_SHOT = "shot";
    private static final String TAB_EDITOR = "editor";
    private static final String TAB_TALLY = "tally";
    private static final String TAB_CLIP = "clip";
    private static final String TAB_QR = "qr";
    private static final String TAB_CROSSHAIR = "crosshair";
    private static final String TAB_TIMERS = "timers";
    private static final String TAB_AUTOCLICK = "autoclick";
    private static final String TAB_SECURE = "secure";
    private static final String TAB_BATTERY = "battery";
    private static final String TAB_REFIMAGE = "refimage";
    private static final String TAB_PEEKER = "peeker";
    private static final String TAB_STICKY = "sticky";
    private static final String TAB_REMINDER = "reminder";
    private static final String TAB_FARM = "farm";
    private static final String TAB_DMG = "dmgcalc";
    private static final String TAB_BPM = "bpm";

    /** Tab key -> the menu button that toggles it. */
    private static String tabButtonId(String key) {
        if (TAB_QUICKNOTE.equals(key)) return "#quicknote";
        if (TAB_NOTES.equals(key)) return "#notes";
        if (TAB_VOLUME.equals(key)) return "#volume";
        if (TAB_SHOT.equals(key)) return "#shot";
        if (TAB_TALLY.equals(key)) return "#tally";
        if (TAB_CLIP.equals(key)) return "#clip";
        if (TAB_QR.equals(key)) return "#qr";
        if (TAB_CROSSHAIR.equals(key)) return "#crosshair";
        if (TAB_TIMERS.equals(key)) return "#timers";
        if (TAB_AUTOCLICK.equals(key)) return "#autoclick";
        if (TAB_SECURE.equals(key)) return "#secure";
        if (TAB_BATTERY.equals(key)) return "#battery";
        if (TAB_REFIMAGE.equals(key)) return "#refimage";
        if (TAB_PEEKER.equals(key)) return "#peeker";
        if (TAB_STICKY.equals(key)) return "#sticky";
        if (TAB_REMINDER.equals(key)) return "#toasttimer";
        if (TAB_FARM.equals(key)) return "#farm";
        if (TAB_DMG.equals(key)) return "#dmgcalc";
        if (TAB_BPM.equals(key)) return "#bpm";
        return null;
    }

    // stateful buttons
    private ImageView torchIcon;
    private View torchBox;
    private int iconTint = Ui.ACCENT;
    private int boxTint = ITEM_BG_SOFT;

    private final Map<String, ImageView> toggleIcons = new LinkedHashMap<>();
    private final Map<String, View> toggleBoxes = new LinkedHashMap<>();
    private final Map<String, Integer> toggleIconTint = new LinkedHashMap<>();
    private final Map<String, Integer> toggleBoxTint = new LinkedHashMap<>();

    private ImageView caffeineIcon;
    private View caffeineBox;
    private int caffeineIconTint = Ui.ACCENT;
    private int caffeineBoxTint = ITEM_BG_SOFT;
    private boolean keepAwake;

    // extra overlays
    private FrameLayout colorRoot;
    private Bitmap colorBitmap;
    private FrameLayout lockRoot;
    private FrameLayout rulerRoot;
    private RulerView rulerView;
    private FrameLayout blackRoot;

    // clipboard tab state
    private LinearLayout clipPinnedList;
    private LinearLayout clipHistoryList;
    private TextView clipStatus;

    // ------------------------------------------------------------ lifecycle

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        Side.ensureCaptureTools(this);
        Side.ensureVolume(this);
        Side.ensureHud(this);
        Side.ensureToolkit(this);
        Side.ensureToolkit8(this);
        Side.ensureToolkit9(this);
        Side.ensureToolkit92(this);
        Side.ensureToolkit101(this);
        Side.ensureToolkit110(this);
        Side.ensureToolkit120(this);
        Side.ensureToolkit120b(this);
        keepAwake = Side.caffeineOn(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lastConfigW = getResources().getDisplayMetrics().widthPixels;
        lastConfigH = getResources().getDisplayMetrics().heightPixels;
        startForegroundSafe();
        if (!addHandle()) {
            toast("Không tạo được thanh bên");
            stopSelf();
        } else {
            applyKeepAwake(keepAwake);
            scheduleTuck();
            if (Side.hudOn(this)) addHud(false);
            if (Crosshair.enabled(this)) addCrosshair(false);
            registerBatteryMonitor();
            if (refPrefs().getBoolean("on", false)) showReference(false);
            if (stickyPrefs().getBoolean("on", false)) showSticky(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && "capture".equals(intent.getAction())) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    openShot();
                }
            }, 300);
        }
        return START_STICKY;
    }

    /**
     * Screen rotation changes the display bounds; the handle and its tucked
     * geometry are rebuilt from scratch so the bar keeps the same thickness in
     * portrait and landscape. Open panels and overlays are dismissed because
     * their old coordinates no longer match the display.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        if (w == lastConfigW && h == lastConfigH) return;
        lastConfigW = w;
        lastConfigH = h;
        resetSidebar();
    }

    private void resetSidebar() {
        handler.removeCallbacksAndMessages(null);
        if (handleAnim != null) {
            handleAnim.cancel();
            handleAnim = null;
        }
        removeShotPicker();
        removeAutoPicker(autoPickRoot);
        removeAutoDot();
        removeReferenceWindow();
        removeStickyWindow();
        removeTouchLock(false);
        closeColorPicker(false);
        stopRuler(false);
        stopBlackScreen(false);
        detachCrosshair();
        detachHud();
        hideNow();
        if (handle != null) {
            try {
                wm.removeView(handle);
            } catch (Exception ignored) {
            }
            handle = null;
        }

        tucked = false;
        untuckingAtDown = false;
        shotBusy = false;
        Side.setPos(this, -1);

        if (!addHandle()) {
            toast("Không tạo lại được thanh bên");
            return;
        }
        applyKeepAwake(keepAwake);
        scheduleTuck();
        if (Side.hudOn(this)) addHud(false);
        if (Crosshair.enabled(this)) addCrosshair(false);
        restartTimerTick();
        restartReminderTick();
        if (autoRunning) {
            showAutoDot();
            handler.removeCallbacks(autoClickTick);
            handler.postDelayed(autoClickTick, 160);
        }
        if (refPrefs().getBoolean("on", false)) showReference(false);
        if (stickyPrefs().getBoolean("on", false)) showSticky(false);
    }

    @Override
    public void onDestroy() {
        stopAuto(false);
        saveStickyText();
        removeStickyWindow();
        handler.removeCallbacks(reminderTick);
        Peeker.listener = null;
        unregisterBatteryMonitor();
        removeAutoPicker(autoPickRoot);
        removeReferenceWindow();
        removeTouchLock(false);
        closeColorPicker(false);
        stopRuler(false);
        stopBlackScreen(false);
        detachCrosshair();
        releaseTone();
        detachHud();
        hideNow();
        handler.removeCallbacksAndMessages(null);
        if (handle != null) {
            try {
                wm.removeView(handle);
            } catch (Exception ignored) {
            }
            handle = null;
        }
        if (current == this) current = null;
        super.onDestroy();
    }

    static boolean running() {
        return current != null;
    }

    /** Called after the action list changed elsewhere. */
    static void refreshItems() {
        if (current != null && current.panelOpen && current.panelKind == KIND_DOCK) {
            current.rebuildDockMenu();
        }
    }

    /** Opens the screenshot tab, starting the sidebar first if needed. */
    static void requestShot(Context c) {
        if (current != null) {
            current.openShot();
            return;
        }
        Intent i = new Intent(c, SidebarService.class);
        i.setAction("capture");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Toast.makeText(c, "Không mở được thanh bên", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ capture hooks

    private void hideHandleForCapture() {
        handler.removeCallbacksAndMessages(null);
        hideNow();
        handler.removeCallbacksAndMessages(null);
        if (handleAnim != null) {
            handleAnim.cancel();
            handleAnim = null;
        }
        if (handle != null) {
            try {
                wm.removeView(handle);
            } catch (Exception ignored) {
            }
            handle = null;
        }
        // wipe() above also cleared the timer heartbeat; countdowns must keep
        // running while a capture, ruler or black-screen overlay is open.
        restartTimerTick();
        restartReminderTick();
    }

    /** Puts the handle back and re-opens the dock after a full-screen capture. */
    private void restoreAfterCapture() {
        restoreHandleAfterCapture();
        if (panel == null) openMenu();
    }

    private void restoreHandleAfterCapture() {
        if (handle != null) return;
        tucked = false;
        untuckingAtDown = false;
        if (!addHandle()) {
            toast("Không tạo lại được thanh bên");
            return;
        }
        if (hudView != null) hudView.setVisibility(View.VISIBLE);
        applyKeepAwake(keepAwake);
        scheduleTuck();
        restartTimerTick();
        restartReminderTick();
    }

    // ------------------------------------------------------------ notification

    private void startForegroundSafe() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Thanh bên",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("Thanh bên Essentials")
                .setContentText("Đang bật")
                .setSmallIcon(R.drawable.ic_note)
                .setContentIntent(pi)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, b.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    // ------------------------------------------------------------ handle

    private boolean addHandle() {
        FrameLayout box = new FrameLayout(this);

        // the chip: rounded bar that shrinks to a sliver when tucked
        handleChip = new View(this);
        handleBg = new GradientDrawable();
        handleBg.setColor(GLASS_HANDLE);
        handleBg.setCornerRadius(dp(13));
        strokePx = Math.max(1, dp(1));
        handleBg.setStroke(strokePx, strokeColor);
        handleChip.setBackground(handleBg);
        chipWidthPx = dp(HANDLE_CHIP_DP);
        final boolean left = Side.sidebarLeft(this);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                chipWidthPx, ViewGroup.LayoutParams.MATCH_PARENT,
                left ? Gravity.START : Gravity.END);
        box.addView(handleChip, chipLp);

        // the chevron lives beside the chip, so it stays visible when the chip tucks
        chevron = new TextView(this);
        chevron.setText(left ? "\u203A" : "\u2039");
        chevron.setTextColor(Ui.ACCENT);
        chevron.setTextSize(18);
        chevron.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(HANDLE_CHEVRON_DP), ViewGroup.LayoutParams.MATCH_PARENT,
                left ? Gravity.START : Gravity.END);
        if (left) clp.leftMargin = dp(HANDLE_CHEVRON_MARGIN_DP);
        else clp.rightMargin = dp(HANDLE_CHEVRON_MARGIN_DP);
        box.addView(chevron, clp);

        box.setAlpha(HANDLE_A_NORMAL);

        handleParams = new WindowManager.LayoutParams(dp(HANDLE_W_DP), dp(HANDLE_H_NORMAL), TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (keepAwake ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0),
                PixelFormat.TRANSLUCENT);
        handleParams.gravity = Gravity.TOP
                | (left ? Gravity.START : Gravity.END);
        // clear of the system back-gesture edge
        handleParams.x = dp(EDGE_INSET_DP);
        handleParams.y = startY();

        try {
            wm.addView(box, handleParams);
        } catch (Exception e) {
            return false;
        }
        handle = box;
        box.setOnTouchListener(new View.OnTouchListener() {
            float startRaw;
            int startParam;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        handler.removeCallbacks(tuckIdle);
                        startRaw = e.getRawY();
                        startParam = handleParams.y;
                        moved = false;
                        untuckingAtDown = tucked;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - startRaw);
                        if (Math.abs(dy) > ViewConfiguration.get(SidebarService.this)
                                .getScaledTouchSlop()) {
                            moved = true;
                        }
                        handleParams.y = clamp(startParam + dy, 0, maxY());
                        try {
                            wm.updateViewLayout(handle, handleParams);
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        Side.setPos(SidebarService.this, handleParams.y);
                        if (moved) {
                            scheduleTuck();
                        } else {
                            if (tucked) animateHandle(false);
                            togglePanel();
                        }
                        untuckingAtDown = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        return true;
    }

    /** Chevron rotates, border/icon brighten. */
    private void setHandleOpen(boolean open) {
        if (chevron != null) {
            chevron.animate()
                    .rotation(open ? 180f : 0f)
                    .setDuration(ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            chevron.setTextColor(open ? 0xFFC2D2FF : Ui.ACCENT);
        }
        animateStroke(open ? STROKE_OPEN : STROKE_CLOSED);
    }

    private void animateStroke(int to) {
        if (handleBg == null) return;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), strokeColor, to);
        a.setDuration(ANIM_MS);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator an) {
                strokeColor = (Integer) an.getAnimatedValue();
                handleBg.setStroke(strokePx, strokeColor);
            }
        });
        a.start();
    }

    // ------------------------------------------------------------ tuck / untuck

    /** After 5s of no use, the handle shrinks into the edge: longer, 50% opacity. */
    private void scheduleTuck() {
        handler.removeCallbacks(tuckIdle);
        if (!panelOpen && !tucked) handler.postDelayed(tuckIdle, TUCK_DELAY_MS);
    }

    private void tuckHandle() {
        if (tucked || panelOpen || handle == null) return;
        untuckingAtDown = false;
        animateHandle(true);
    }

    /**
     * Animates x / height / alpha of the tiny handle window.
     * A 26dp-wide window relayout is cheap; the big panels use view transforms instead.
     */
    private void animateHandle(final boolean toTucked) {
        if (handleAnim != null) handleAnim.cancel();
        final int fromX = handleParams.x;
        final int toX = dp(toTucked ? HANDLE_X_TUCKED : HANDLE_X_NORMAL);
        final int fromH = handleParams.height;
        final int toH = dp(toTucked ? HANDLE_H_TUCKED : HANDLE_H_NORMAL);
        final int center = handleParams.y + handleParams.height / 2;
        final float fromA = handle.getAlpha();
        final float toA = toTucked ? HANDLE_A_TUCKED : HANDLE_A_NORMAL;
        final int overhang = dp(HANDLE_TUCK_OVERHANG_DP);
        final int fromBar = chipWidthPx;
        final int toBar = toTucked
                ? tuckBarPx() + overhang : dp(HANDLE_CHIP_DP);
        final boolean left = Side.sidebarLeft(this);
        final FrameLayout.LayoutParams fromChipLp =
                (FrameLayout.LayoutParams) handleChip.getLayoutParams();
        final int fromChipMargin = left ? fromChipLp.leftMargin : fromChipLp.rightMargin;
        final int toChipMargin = toTucked ? -overhang : 0;
        final FrameLayout.LayoutParams fromChevronLp = chevron == null ? null
                : (FrameLayout.LayoutParams) chevron.getLayoutParams();
        final int fromMargin = fromChevronLp == null ? 0
                : (left ? fromChevronLp.leftMargin : fromChevronLp.rightMargin);
        // tucked: the arrow centres inside the visible part of the bar
        final int toMargin = toTucked
                ? Math.max(0, (tuckBarPx() - dp(HANDLE_CHEVRON_DP)) / 2)
                : dp(HANDLE_CHEVRON_MARGIN_DP);

        handleAnim = ValueAnimator.ofFloat(0f, 1f);
        handleAnim.setDuration(260);
        handleAnim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        handleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = a.getAnimatedFraction();
                handleParams.x = Math.round(fromX + (toX - fromX) * t);
                handleParams.height = Math.round(fromH + (toH - fromH) * t);
                handleParams.y = clamp(center - handleParams.height / 2, 0, maxY());
                handle.setAlpha(fromA + (toA - fromA) * t);
                int barW = Math.round(fromBar + (toBar - fromBar) * t);
                int chipMargin = Math.round(fromChipMargin
                        + (toChipMargin - fromChipMargin) * t);
                if (handleChip != null) {
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) handleChip.getLayoutParams();
                    int currentMargin = left ? lp.leftMargin : lp.rightMargin;
                    if (barW != chipWidthPx || currentMargin != chipMargin) {
                        chipWidthPx = barW;
                        lp.width = barW;
                        if (left) lp.leftMargin = chipMargin;
                        else lp.rightMargin = chipMargin;
                        handleChip.setLayoutParams(lp);
                    }
                }
                if (chevron != null) {
                    // the arrow stays visible, sliding left as the chip narrows
                    int margin = Math.round(fromMargin + (toMargin - fromMargin) * t);
                    FrameLayout.LayoutParams clp =
                            (FrameLayout.LayoutParams) chevron.getLayoutParams();
                    int current = left ? clp.leftMargin : clp.rightMargin;
                    if (current != margin) {
                        if (left) clp.leftMargin = margin;
                        else clp.rightMargin = margin;
                        chevron.setLayoutParams(clp);
                    }
                }
                try {
                    wm.updateViewLayout(handle, handleParams);
                } catch (Exception ignored) {
                }
            }
        });
        handleAnim.start();
        tucked = toTucked;
    }

    private int startY() {
        int stored = Side.pos(this);
        if (stored >= 0) return clamp(stored, 0, maxY());
        return Math.max(0, screenH() / 2 - dp(34));
    }

    private int maxY() {
        int h = handleParams != null ? handleParams.height : dp(HANDLE_H_NORMAL);
        return Math.max(0, screenH() - h);
    }

    private int maxPanelY() {
        return Math.max(0, screenH() - dp(330));
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * Width of the tucked sliver. A fixed dp size keeps it identical in
     * portrait and landscape, and on every screen density.
     */
    private int tuckBarPx() {
        return dp(HANDLE_BAR_DP);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------ panel plumbing

    /** Flips the handle and dock between the right and left screen edges. */
    private void switchSidebarSide() {
        int pos = handleParams != null ? handleParams.y : Side.pos(this);
        boolean left = !Side.sidebarLeft(this);
        Side.setSidebarLeft(this, left);
        resetSidebar();
        if (handleParams != null && pos >= 0) {
            handleParams.y = clamp(pos, 0, maxY());
            try {
                wm.updateViewLayout(handle, handleParams);
            } catch (Exception ignored) {
            }
            Side.setPos(this, handleParams.y);
        }
        toast(left ? "Thanh bên chuyển sang trái" : "Thanh bên chuyển sang phải");
    }

    private void togglePanel() {
        if (panelOpen) closePanel();
        else openMenu();
    }

    /**
     * Opens the dock: the menu stays put on the right edge while tool tabs
     * stack in a scrollable column beside it. Tapping the handle again (or
     * anywhere outside) closes the whole dock.
     */
    private void openMenu() {
        if (panelOpen && panelKind == KIND_DOCK) return;
        hideNow();
        panelKind = KIND_DOCK;
        final boolean left = Side.sidebarLeft(this);
        panelOnRight = !left;
        handler.removeCallbacks(tuckIdle);

        FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) bumpAutoHide();
                return super.dispatchTouchEvent(e);
            }
        };
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    boolean inMenu = insideView(dockMenu, e.getX(), e.getY());
                    boolean inTabs = dockScroll != null
                            && dockScroll.getVisibility() == View.VISIBLE
                            && insideView(dockScroll, e.getX(), e.getY());
                    if (!inMenu && !inTabs) closePanel();
                }
                return true;
            }
        });
        root.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent ev) {
                if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                    if (tabViews.containsKey(TAB_QUICKNOTE)) closeTab(TAB_QUICKNOTE);
                    else closePanel();
                    return true;
                }
                return false;
            }
        });
        dockRoot = root;

        dockTabs = Ui.col(this);
        MaxScroll scroll = new MaxScroll(this);
        scroll.setMaxHeight(screenH() - dp(48));
        scroll.setFillViewport(false);
        scroll.addView(dockTabs, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dockScroll = scroll;

        LinearLayout menu = buildMenu();
        dockMenu = menu;

        // Measure before the window is shown so the card is already at its
        // final vertical position. Re-positioning it in a post used to make
        // the menu jump up from the bottom for one frame.
        menu.measure(View.MeasureSpec.makeMeasureSpec(screenW(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH(), View.MeasureSpec.AT_MOST));
        int menuW = menu.getMeasuredWidth();
        int menuH = menu.getMeasuredHeight();

        // Right mode: menu on the right, tabs fill the space to its left.
        // Left mode mirrors it: menu on the left, tabs to its right.
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                dockTabWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.START;
        sp.leftMargin = left
                ? dp(PANEL_INSET_DP) + menuW + dp(10)
                : dp(EDGE_INSET_DP);
        sp.topMargin = dp(24);
        root.addView(dockScroll, sp);
        dockScroll.setVisibility(tabViews.isEmpty() ? View.GONE : View.VISIBLE);

        final FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        if (left) mp.leftMargin = dp(PANEL_INSET_DP);
        else mp.rightMargin = dp(PANEL_INSET_DP);
        int center = handleParams.y + handleParams.height / 2;
        mp.topMargin = clamp(center - menuH / 2, dp(8),
                Math.max(dp(8), screenH() - menuH - dp(8)));
        // Off-screen on the menu's own edge before the first frame, so the
        // slide-in never flashes at its final position.
        menu.setTranslationX(dp(left ? -340 : 340));
        root.addView(menu, mp);

        // Size the tab column from the menu's measured width up front, so tabs
        // do not reflow after the window appears.
        sp.width = Math.max(dp(180), screenW() - menuW - dp(PANEL_INSET_DP)
                - dp(EDGE_INSET_DP) - dp(10));

        panelParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, panelParams);
        } catch (Exception e) {
            panelKind = KIND_NONE;
            toast("Không mở được thanh bên");
            return;
        }
        panel = root;
        panelCard = menu;
        panelOpen = true;
        dockFocusable = false;
        setHandleOpen(true);

        menu.post(new Runnable() {
            public void run() {
                if (dockMenu == null) return;
                dockMenu.animate().translationX(0f)
                        .setDuration(ANIM_MS)
                        .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                        .start();

                for (View tab : tabViews.values()) {
                    tab.setAlpha(0f);
                    tab.setTranslationX(dp(left ? 22 : -22));
                    tab.animate().alpha(1f).translationX(0f)
                            .setDuration(180)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            }
        });

        bumpAutoHide();
    }

    /** Rebuilds just the menu card, keeping every open tab untouched. */
    private void rebuildDockMenu() {
        if (dockRoot == null || dockMenu == null) return;
        ViewGroup.LayoutParams lp = dockMenu.getLayoutParams();
        LinearLayout fresh = buildMenu();
        dockRoot.removeView(dockMenu);
        dockRoot.addView(fresh, lp);
        dockMenu = fresh;
        panelCard = fresh;
    }

    private int dockTabWidth() {
        return Math.max(dp(132), Math.min(dp(312), screenW() - dp(228)));
    }

    private static boolean insideView(View v, float x, float y) {
        return v != null && v.getVisibility() == View.VISIBLE
                && x >= v.getLeft() && x <= v.getRight()
                && y >= v.getTop() && y <= v.getBottom();
    }

    /** Menu alone times out after 12 s; open tabs keep the dock alive. */
    private void bumpAutoHide() {
        handler.removeCallbacks(autoHide);
        if (!tabViews.isEmpty()) return;
        handler.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    /**
     * Hosts one full-screen transparent window and slides the card inside it.
     * Animating a view (translationX) is GPU work; animating window params
     * relayouts the window every frame and stutters.
     *
     * @param right true = card on the right edge, false = left edge
     */
    private void showPanel(View card, boolean right, int widthPx, int y,
                           boolean autoHideAfter, boolean focusable, int kind,
                           boolean centerOnHandle) {
        handler.removeCallbacks(tuckIdle);
        hideNow();
        panelKind = kind;
        panelOnRight = right;

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        cp.topMargin = y;
        if (right) cp.rightMargin = dp(PANEL_INSET_DP);
        else cp.leftMargin = dp(EDGE_INSET_DP);
        root.addView(card, cp);

        // Tapping anywhere outside the card closes the panel.
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    View c = panelCard;
                    if (c == null || e.getX() < c.getLeft() || e.getX() > c.getRight()
                            || e.getY() < c.getTop() || e.getY() > c.getBottom()) {
                        closePanel();
                    }
                }
                return true;
            }
        });
        root.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent ev) {
                if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                    closePanel();
                    return true;
                }
                return false;
            }
        });

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }

        panelParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, flags, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        if (focusable) {
            panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        }

        try {
            wm.addView(root, panelParams);
        } catch (Exception e) {
            panelKind = KIND_NONE;
            return;
        }
        panel = root;
        panelCard = card;
        panelOpen = true;
        if (right) setHandleOpen(true);

        final int travel = widthPx == ViewGroup.LayoutParams.WRAP_CONTENT
                ? dp(340)
                : widthPx + dp(right ? PANEL_INSET_DP : EDGE_INSET_DP);
        card.setAlpha(1f);
        card.setTranslationX(right ? travel : -travel);
        final FrameLayout.LayoutParams cpRef = cp;
        card.post(new Runnable() {
            public void run() {
                if (centerOnHandle) {
                    int ph = card.getHeight();
                    if (ph > 0) {
                        int center = handleParams.y + handleParams.height / 2;
                        cpRef.topMargin = clamp(center - ph / 2, 0,
                                Math.max(0, screenH() - ph));
                        card.setLayoutParams(cpRef);
                    }
                }
                card.animate()
                        .translationX(0f)
                        .setDuration(ANIM_MS)
                        .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                        .start();
                if (card instanceof ViewGroup) {
                    Ui.stagger((ViewGroup) card, 50, 20);
                }
            }
        });

        if (autoHideAfter) handler.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    private void closePanel() {
        closePanel(true);
    }

    /**
     * @param animate false when an overlay is about to open on top (screenshot,
     *                colour picker, touch lock) and the dock must vanish at once.
     */
    private void closePanel(boolean animate) {
        handler.removeCallbacks(autoHide);
        final View root = panel;
        if (root == null) {
            hideNow();
            return;
        }
        if (!animate || panelKind != KIND_DOCK) {
            hideNow();
            return;
        }
        panelOpen = false;
        setHandleOpen(false);
        panelKind = KIND_NONE;

        if (dockScroll != null) {
            dockScroll.animate().alpha(0f)
                    .translationX(panelOnRight ? -dp(22) : dp(22))
                    .setDuration(TAB_ANIM_MS)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        }
        if (dockMenu != null) {
            final int travel = dockMenu.getWidth() > 0
                    ? dockMenu.getWidth() + dp(60) : dp(340);
            dockMenu.animate()
                    .translationX(panelOnRight ? travel : -travel)
                    .setDuration(ANIM_MS)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 0.6f, 1f))
                    .withEndAction(new Runnable() {
                        public void run() {
                            if (panel != root) return;
                            hideNow();
                        }
                    })
                    .start();
        } else {
            hideNow();
        }
    }

    /** Removes the dock/tab window immediately (no animation). */
    private void hideNow() {
        handler.removeCallbacks(autoHide);
        View root = panel;
        if (dockMenu != null) dockMenu.animate().cancel();
        if (dockScroll != null) dockScroll.animate().cancel();
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        panel = null;
        panelCard = null;
        panelOpen = false;
        panelKind = KIND_NONE;
        dockRoot = null;
        dockMenu = null;
        dockScroll = null;
        dockTabs = null;
        dockFocusable = false;
        tabViews.clear();
        menuButtons.clear();
        buildingTab = null;
        Peeker.listener = null;
        peekerList = null;
        peekerStatus = null;
        stickyStatusView = null;
        stickyPinButton = null;
        stickyClickButton = null;
        reminderStatusView = null;
        reminderStartButton = null;
        reminderInput = null;
        reminderMinValue = null;
        reminderSecValue = null;
        for (int i = 0; i < FARM_SLOTS; i++) {
            farmValues[i] = null;
            farmNames[i] = null;
        }
        bpmValue = null;
        bpmMs = null;
        bpmStatus = null;
        bpmTargetValue = null;
        setHandleOpen(false);
        scheduleTuck();
    }

    // ------------------------------------------------------------ tabs

    /** Toggles a tool tab: opening it leaves the menu where it is. */
    private void toggleTab(String key) {
        if (tabViews.containsKey(key)) {
            closeTab(key);
            return;
        }
        if (TAB_NOTES.equals(key)) openNotes();
        else if (TAB_VOLUME.equals(key)) openVolume();
        else if (TAB_QUICKNOTE.equals(key)) openQuickNote();
        else if (TAB_SHOT.equals(key)) openShot();
        else if (TAB_EDITOR.equals(key)) showEditor();
        else if (TAB_TALLY.equals(key)) openTally();
        else if (TAB_CLIP.equals(key)) openClip();
        else if (TAB_QR.equals(key)) openQr();
        else if (TAB_CROSSHAIR.equals(key)) openCrosshair();
        else if (TAB_TIMERS.equals(key)) openTimers();
        else if (TAB_AUTOCLICK.equals(key)) openAutoClick();
        else if (TAB_SECURE.equals(key)) openSecure();
        else if (TAB_BATTERY.equals(key)) openBattery();
        else if (TAB_REFIMAGE.equals(key)) openRefImage();
        else if (TAB_PEEKER.equals(key)) openPeeker();
        else if (TAB_STICKY.equals(key)) openSticky();
        else if (TAB_REMINDER.equals(key)) openReminder();
        else if (TAB_FARM.equals(key)) openFarm();
        else if (TAB_DMG.equals(key)) openDmgCalc();
        else if (TAB_BPM.equals(key)) openBpm();
        else if (TAB_NOTEDETAIL.equals(key)) openNotes();
    }

    private void addTab(final String key, View card) {
        if (panel == null || panelKind != KIND_DOCK) {
            openMenu();
            if (panel == null) return;
        }
        if (tabViews.containsKey(key) || dockTabs == null) return;

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        card.setAlpha(0f);
        card.setTranslationX(dp(-22));
        dockTabs.addView(card, p);
        tabViews.put(key, card);
        dockScroll.setVisibility(View.VISIBLE);
        paintTabButton(key, true);
        handler.removeCallbacks(autoHide);

        card.animate().alpha(1f).translationX(0f)
                .setDuration(TAB_ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // Newest tab is appended at the bottom; bring it into view.
        card.post(new Runnable() {
            public void run() {
                if (dockScroll == null || dockTabs == null) return;
                int y = Math.max(0, dockTabs.getHeight() - dockScroll.getHeight());
                dockScroll.smoothScrollTo(0, y);
            }
        });

        if (dockFocusable) panelParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
    }

    private void closeTab(String key) {
        final View card = tabViews.remove(key);
        if (card == null) return;

        if (TAB_QUICKNOTE.equals(key)) {
            try {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(card.getWindowToken(), 0);
            } catch (Exception ignored) {
            }
        }

        final boolean lastTab = tabViews.isEmpty();
        if (dockTabs != null) {
            // Reverse of the entrance: fade out and slide back to the left.
            card.animate().cancel();
            card.setAlpha(1f);
            card.setTranslationX(0f);

            // Every tab below the closing one is moved up by exactly its
            // height with a translation (pure GPU property, no layout pass),
            // so the column drifts instead of snapping when the view goes.
            final int index = dockTabs.indexOfChild(card);
            final List<View> below = new ArrayList<>();
            if (index >= 0) {
                for (int i = index + 1; i < dockTabs.getChildCount(); i++) {
                    below.add(dockTabs.getChildAt(i));
                }
            }
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) card.getLayoutParams();
            int cardH = card.getHeight();
            if (cardH <= 0) cardH = card.getMeasuredHeight();
            if (cardH <= 0) cardH = dp(120);
            final int shift = cardH + (lp != null ? lp.bottomMargin : 0);

            ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
            out.setDuration(TAB_ANIM_MS);
            out.setInterpolator(new AccelerateInterpolator());
            out.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    if (dockTabs == null) return;
                    float f = a.getAnimatedFraction();
                    card.setAlpha(1f - f);
                    card.setTranslationX(-dp(22) * f);
                    float up = -shift * f;
                    for (int i = 0; i < below.size(); i++) {
                        below.get(i).setTranslationY(up);
                    }
                }
            });
            out.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    // Reset to zero in the same frame the card is removed:
                    // layout repositions them to exactly that spot, so no jump.
                    for (int i = 0; i < below.size(); i++) {
                        below.get(i).setTranslationY(0f);
                    }
                    if (dockTabs != null) dockTabs.removeView(card);
                    if (lastTab && tabViews.isEmpty() && dockScroll != null) {
                        dockScroll.setVisibility(View.GONE);
                        bumpAutoHide();
                    }
                }
            });
            out.start();
        } else if (lastTab && dockScroll != null) {
            dockScroll.setVisibility(View.GONE);
            bumpAutoHide();
        }
        paintTabButton(key, false);
        if (TAB_PEEKER.equals(key)) {
            Peeker.listener = null;
            peekerList = null;
            peekerStatus = null;
        }
        if (TAB_STICKY.equals(key)) {
            stickyStatusView = null;
            stickyPinButton = null;
            stickyClickButton = null;
        }
        if (TAB_BPM.equals(key)) {
            bpmValue = null;
            bpmMs = null;
            bpmStatus = null;
            bpmTargetValue = null;
        }
        if (TAB_FARM.equals(key)) {
            for (int i = 0; i < FARM_SLOTS; i++) {
                farmValues[i] = null;
                farmNames[i] = null;
            }
        }
        if (TAB_REMINDER.equals(key)) {
            reminderStatusView = null;
            reminderStartButton = null;
            reminderInput = null;
            reminderMinValue = null;
            reminderSecValue = null;
        }
        if (TAB_EDITOR.equals(key)) rebuildDockMenu();
        setDockFocusable(needsKeyboard());
    }

    /** True while a tab with a text field is open, so the IME can show. */
    private boolean needsKeyboard() {
        return tabViews.containsKey(TAB_QUICKNOTE)
                || tabViews.containsKey(TAB_CLIP)
                || tabViews.containsKey(TAB_QR)
                || tabViews.containsKey(TAB_SECURE)
                || tabViews.containsKey(TAB_FARM)
                || tabViews.containsKey(TAB_DMG)
                || tabViews.containsKey(TAB_REMINDER);
    }

    /** Warm accent frame on menu buttons whose tab is open. */
    private void paintTabButton(String key, boolean on) {
        String id = tabButtonId(key);
        if (id == null) return;
        View b = menuButtons.get(id);
        if (b == null) return;
        b.setBackground(Ui.bg(on ? 0x2E6E9BFF : ITEM_BG_SOFT,
                on ? Ui.ACCENT : Ui.FRAME, this, 14, on ? 1.5f : 1f));
    }

    /** The dock window becomes focusable only while the note editor needs IME. */
    private void setDockFocusable(boolean on) {
        if (panel == null || panelParams == null || dockFocusable == on) return;
        dockFocusable = on;
        panelParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | (on ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                     : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        panelParams.softInputMode = on
                ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                  | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        try {
            wm.updateViewLayout(panel, panelParams);
        } catch (Exception ignored) {
        }
    }

    /** ScrollView that never grows past the screen height. */
    private class MaxScroll extends ScrollView {
        private int maxHeight = Integer.MAX_VALUE;

        MaxScroll(Context c) {
            super(c);
        }

        void setMaxHeight(int h) {
            maxHeight = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int h = Math.min(MeasureSpec.getSize(heightSpec), maxHeight);
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        }
    }

    // ------------------------------------------------------------ glass bits

    private LinearLayout glassCard() {
        LinearLayout col = Ui.col(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GLASS);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1)), Ui.FRAME);
        col.setBackground(bg);
        return col;
    }

    private View header(String title, boolean back, View.OnClickListener onBack) {
        LinearLayout row = Ui.row(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(dp(4), dp(2), dp(2), dp(2));

        if (back) {
            TextView b = Ui.text(this, "\u2039", 22, Ui.ACCENT, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(6), 0, dp(8), dp(2));
            b.setOnClickListener(onBack);
            row.addView(b);
            Ui.press(b, 0.86f);
        }

        TextView t = Ui.text(this, title, 15, Ui.TEXT, true);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String owner = buildingTab;
        TextView close = Ui.text(this, "\u00d7", 20, Ui.MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), 0, dp(4), dp(2));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (owner != null) closeTab(owner);
                else closePanel();
            }
        });
        row.addView(close);
        Ui.press(close, 0.86f);
        return row;
    }

    // ------------------------------------------------------------ menu panel

    /**
     * Tools visible before the menu starts scrolling. Older builds wrapped the
     * menu into extra columns, which made it too wide for the tab area (and in
     * landscape it ran off-screen), so the menu is a single scrolling column
     * now: six buttons tall, then swipe.
     */
    private static final int MENU_ROWS = 6;
    private static final int MENU_CELL_DP = 50;
    private static final int MENU_GAP_DP = 6;

    // Long-press drag-to-reorder state for the menu column.
    private View dragView;
    private int dragIndex = -1;
    private int dragBaseIndex = -1;
    private float dragBaseRawY;
    private float dragTouchRawY;
    private float dragGrabOffset;
    private int dragScrollStart;
    private LinearLayout dragColumn;
    private MaxScroll dragScroll;

    private LinearLayout buildMenu() {
        torchIcon = null;
        torchBox = null;
        caffeineIcon = null;
        caffeineBox = null;
        caffeineIconTint = Ui.ACCENT;
        caffeineBoxTint = ITEM_BG_SOFT;
        toggleIcons.clear();
        toggleBoxes.clear();
        toggleIconTint.clear();
        toggleBoxTint.clear();

        List<View> buttons = new ArrayList<>();
        menuButtons.clear();
        for (String id : Side.items(this)) {
            View b = itemButton(id);
            if (b != null) {
                b.setTag(id);
                attachReorder(b);
                buttons.add(b);
                menuButtons.put(id, b);
            }
        }

        LinearLayout card = glassCard();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout column = Ui.col(this);
        column.setClipChildren(false);
        for (int i = 0; i < buttons.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    dp(MENU_CELL_DP), dp(MENU_CELL_DP));
            p.bottomMargin = dp(MENU_GAP_DP);
            column.addView(buttons.get(i), p);
        }

        int pitch = dp(MENU_CELL_DP + MENU_GAP_DP);
        int rows = Math.min(MENU_ROWS, Math.max(1, buttons.size()));
        int wanted = rows * pitch - dp(MENU_GAP_DP);
        // Leave room for the pinned add button and both screen edges so the
        // menu still fits in landscape.
        int room = Math.max(dp(MENU_CELL_DP * 2), screenH() - dp(150));
        int maxHeight = Math.min(wanted, room);

        MaxScroll tools = new MaxScroll(this);
        tools.setMaxHeight(maxHeight);
        tools.setFillViewport(false);
        tools.setVerticalScrollBarEnabled(false);
        tools.setHorizontalScrollBarEnabled(false);
        // No stock scrollbar and no fade layer: the buttons themselves dim
        // near the edges (see updateMenuScrollUi), so the fade always matches
        // the real menu background instead of painting a darker patch on top.
        tools.setVerticalFadingEdgeEnabled(false);
        tools.setHorizontalFadingEdgeEnabled(false);
        tools.setClipChildren(false);
        tools.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(tools, new LinearLayout.LayoutParams(
                dp(MENU_CELL_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        dragColumn = column;
        dragScroll = tools;

        tools.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                updateMenuScrollUi();
            }
        });
        tools.post(new Runnable() {
            public void run() {
                updateMenuScrollUi();
            }
        });
        column.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                updateMenuScrollUi();
            }
        });

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dl.topMargin = dp(5);
        dl.bottomMargin = dp(7);
        card.addView(divider, dl);

        // The add/edit button sits outside the scroll view, so it stays put
        // while the tools scroll and never leaves the bottom of the menu.
        View edit = iconButton(R.drawable.ic_edit, "Sửa thanh bên",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        toggleTab(TAB_EDITOR);
                    }
                }, ITEM_BG_SOFT);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                dp(MENU_CELL_DP), dp(MENU_CELL_DP));
        ep.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(edit, ep);

        paintCaffeine(false);
        for (String key : tabViews.keySet()) paintTabButton(key, true);
        return card;
    }

    /**
     * Fades the menu buttons themselves near the scroll edges: only the side
     * that still has hidden content is faded, so the top row stays solid when
     * the list is at the top and the bottom row stays solid at the end.
     */
    private void updateMenuScrollUi() {
        MaxScroll s = dragScroll;
        LinearLayout col = dragColumn;
        if (s == null || col == null) return;
        int track = s.getHeight();
        int content = col.getHeight();
        int count = col.getChildCount();
        if (track <= 0 || count == 0) return;

        int scroll = s.getScrollY();
        int maxScroll = Math.max(0, content - track);
        boolean scrollable = content > track + dp(1);
        boolean hasTop = scrollable && scroll > dp(2);
        boolean hasBottom = scrollable && scroll < maxScroll - dp(2);
        float zone = Math.max(1f, dp(44));
        float min = 0.15f;

        for (int i = 0; i < count; i++) {
            View c = col.getChildAt(i);
            if (c == dragView) continue;
            if (!scrollable) {
                c.setAlpha(1f);
                continue;
            }
            float center = c.getTop() + c.getHeight() / 2f - scroll;
            float topF = hasTop
                    ? Math.max(0f, Math.min(1f, center / zone)) : 1f;
            float bottomF = hasBottom
                    ? Math.max(0f, Math.min(1f, (track - center) / zone)) : 1f;
            float a = Math.min(topF, bottomF);
            c.setAlpha(min + (1f - min) * a);
        }
    }

    /** Wires long-press drag so tools can be moved up or down the menu. */
    private void attachReorder(final View v) {
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View view) {
                return startDrag(view);
            }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragTouchRawY = e.getRawY();
                        view.animate().cancel();
                        view.animate().scaleX(0.90f).scaleY(0.90f)
                                .setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        dragTouchRawY = e.getRawY();
                        if (dragView == view) moveDrag(e.getRawY());
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140)
                                .setInterpolator(new DecelerateInterpolator(2f))
                                .start();
                        if (dragView == view) finishDrag();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private boolean startDrag(View v) {
        if (dragColumn == null || dragColumn.indexOfChild(v) < 0) return false;
        dragView = v;
        dragIndex = dragColumn.indexOfChild(v);
        dragBaseIndex = dragIndex;
        dragBaseRawY = dragTouchRawY;
        dragScrollStart = dragScroll == null ? 0 : dragScroll.getScrollY();
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        dragGrabOffset = dragTouchRawY - loc[1];
        v.setAlpha(0.92f);
        v.setTranslationZ(dp(10));
        v.animate().scaleX(1.06f).scaleY(1.06f)
                .setDuration(110)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        if (dragScroll != null) {
            dragScroll.requestDisallowInterceptTouchEvent(true);
        }
        Ui.pop(v);
        toast("Kéo lên/xuống để đổi thứ tự");
        return true;
    }

    private void moveDrag(float rawY) {
        if (dragView == null || dragColumn == null) return;
        int pitch = Math.max(1, dp(MENU_CELL_DP + MENU_GAP_DP));

        // Keep the lifted button under the finger even when its slot changes
        // or the list auto-scrolls, so the drag feels continuous.
        int[] loc = new int[2];
        dragView.getLocationOnScreen(loc);
        dragView.setTranslationY(dragView.getTranslationY()
                + (rawY - dragGrabOffset - loc[1]));

        int scrolled = (dragScroll == null ? 0 : dragScroll.getScrollY()) - dragScrollStart;
        int count = dragColumn.getChildCount();
        int target = dragBaseIndex
                + Math.round((rawY - dragBaseRawY + scrolled) / pitch);
        target = clamp(target, 0, Math.max(0, count - 1));

        while (dragIndex < target && dragIndex + 1 < count) {
            View sibling = dragColumn.getChildAt(dragIndex + 1);
            dragColumn.removeView(sibling);
            dragColumn.addView(sibling, dragIndex);
            slideToSlot(sibling, pitch);
            dragIndex++;
        }
        while (dragIndex > target && dragIndex - 1 >= 0) {
            View sibling = dragColumn.getChildAt(dragIndex - 1);
            dragColumn.removeView(sibling);
            dragColumn.addView(sibling, dragIndex);
            slideToSlot(sibling, -pitch);
            dragIndex--;
        }

        if (dragScroll != null) {
            int[] sloc = new int[2];
            dragScroll.getLocationOnScreen(sloc);
            int top = sloc[1];
            int bottom = top + dragScroll.getHeight();
            if (rawY < top + dp(46)) dragScroll.scrollBy(0, -dp(6));
            else if (rawY > bottom - dp(46)) dragScroll.scrollBy(0, dp(6));
        }
    }

    /**
     * Makes a neighbour appear to slide from its old slot into the new one:
     * the layout already moved it, so a matching start offset animates back
     * to zero.
     */
    private void slideToSlot(View sibling, int oldOffsetPx) {
        sibling.animate().cancel();
        sibling.setTranslationY(oldOffsetPx);
        sibling.animate().translationY(0f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    private void finishDrag() {
        View v = dragView;
        LinearLayout col = dragColumn;
        dragView = null;
        dragIndex = -1;
        dragBaseIndex = -1;
        if (dragScroll != null) {
            dragScroll.requestDisallowInterceptTouchEvent(false);
        }
        if (v != null) {
            v.setAlpha(1f);
            v.animate().translationY(0f).translationZ(0f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        }
        if (col == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++) {
            Object tag = col.getChildAt(i).getTag();
            if (tag instanceof String) ids.add((String) tag);
        }
        if (ids.isEmpty()) return;
        // Keep items that had no visible button (e.g. an uninstalled shortcut)
        // at the end instead of dropping them from the saved list.
        for (String id : Side.items(this)) {
            if (!ids.contains(id)) ids.add(id);
        }
        Side.setItems(this, ids);
        toast("Đã lưu thứ tự tiện ích");
        handler.postDelayed(new Runnable() {
            public void run() {
                rebuildDockMenu();
            }
        }, 190);
    }

    private View itemButton(final String id) {
        if (id.startsWith("app:")) {
            String pkg = id.substring(4);
            Drawable icon = Apps.iconOf(this, pkg);
            if (icon == null) return null;
            return iconButton(icon, Apps.labelOf(this, pkg), new View.OnClickListener() {
                public void onClick(View v) {
                    Apps.launch(SidebarService.this, id.substring(4));
                    closePanel();
                }
            }, ITEM_BG_SOFT);
        }

        if ("#torch".equals(id)) {
            torchIcon = new ImageView(this);
            torchIcon.setImageResource(R.drawable.ic_flash);
            View b = iconButton(torchIcon, "Đèn pin", new View.OnClickListener() {
                public void onClick(View v) {
                    perform(id);
                }
            }, ITEM_BG_SOFT);
            torchBox = b;
            paintTorch(Torch.isOn(), false);
            return b;
        }

        if ("#caffeine".equals(id)) {
            caffeineIcon = new ImageView(this);
            caffeineIcon.setImageResource(R.drawable.ic_caffeine);
            View b = iconButton(caffeineIcon, "Giữ sáng màn hình",
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            toggleCaffeine();
                        }
                    }, ITEM_BG_SOFT);
            caffeineBox = b;
            paintCaffeine(false);
            return b;
        }

        final int iconRes;
        switch (id) {
            case "#quicknote":
                iconRes = R.drawable.ic_edit;
                break;
            case "#notes":
                iconRes = R.drawable.ic_note;
                break;
            case "#timer":
                iconRes = R.drawable.ic_timer;
                break;
            case "#convert":
                iconRes = R.drawable.ic_convert;
                break;
            case "#shot":
                iconRes = R.drawable.ic_camera;
                break;
            case "#volume":
                iconRes = R.drawable.ic_volume;
                break;
            case "#hud":
                iconRes = R.drawable.ic_stats;
                break;
            case "#power":
                iconRes = R.drawable.ic_power;
                break;
            case "#notifications":
                iconRes = R.drawable.ic_bell;
                break;
            case "#quicksettings":
                iconRes = R.drawable.ic_quick;
                break;
            case "#ringer":
                iconRes = R.drawable.ic_ringer;
                break;
            case "#color":
                iconRes = R.drawable.ic_dropper;
                break;
            case "#lock":
                iconRes = R.drawable.ic_lock;
                break;
            case "#back":
                iconRes = R.drawable.ic_back;
                break;
            case "#home":
                iconRes = R.drawable.ic_home;
                break;
            case "#recents":
                iconRes = R.drawable.ic_recents;
                break;
            case "#ruler":
                iconRes = R.drawable.ic_ruler;
                break;
            case "#tally":
                iconRes = R.drawable.ic_tally;
                break;
            case "#clip":
                iconRes = R.drawable.ic_clip;
                break;
            case "#black":
                iconRes = R.drawable.ic_black;
                break;
            case "#qr":
                iconRes = R.drawable.ic_qr;
                break;
            case "#crosshair":
                iconRes = R.drawable.ic_crosshair;
                break;
            case "#timers":
                iconRes = R.drawable.ic_multitimer;
                break;
            case "#brightness":
                iconRes = R.drawable.ic_brightness;
                break;
            case "#rotate":
                iconRes = R.drawable.ic_rotate;
                break;
            case "#dnd":
                iconRes = R.drawable.ic_dnd;
                break;
            case "#autoclick":
                iconRes = R.drawable.ic_autoclick;
                break;
            case "#secure":
                iconRes = R.drawable.ic_secure;
                break;
            case "#battery":
                iconRes = R.drawable.ic_battery_alert;
                break;
            case "#refimage":
                iconRes = R.drawable.ic_refimage;
                break;
            case "#side":
                iconRes = R.drawable.ic_side;
                break;
            case "#peeker":
                iconRes = R.drawable.ic_peeker;
                break;
            case "#data":
                iconRes = R.drawable.ic_data;
                break;
            case "#hotspot":
                iconRes = R.drawable.ic_hotspot;
                break;
            case "#sticky":
                iconRes = R.drawable.ic_sticky;
                break;
            case "#toasttimer":
                iconRes = R.drawable.ic_toasttimer;
                break;
            case "#farm":
                iconRes = R.drawable.ic_farm;
                break;
            case "#dmgcalc":
                iconRes = R.drawable.ic_dmgcalc;
                break;
            case "#bpm":
                iconRes = R.drawable.ic_bpm;
                break;
            case "#menu":
                iconRes = R.drawable.ic_settings;
                break;
            default:
                return null;
        }

        View.OnClickListener click = new View.OnClickListener() {
            public void onClick(View v) {
                perform(id);
            }
        };
        if (statefulToggle(id)) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            View box = iconButton(icon, Side.labelOf(id), click, ITEM_BG_SOFT);
            toggleIcons.put(id, icon);
            toggleBoxes.put(id, box);
            toggleIconTint.put(id, Ui.ACCENT);
            toggleBoxTint.put(id, ITEM_BG_SOFT);
            paintToggle(id, toggleOn(id), false);
            return box;
        }
        return iconButton(iconRes, Side.labelOf(id), click, ITEM_BG_SOFT);
    }

    private boolean statefulToggle(String id) {
        return "#hud".equals(id) || "#crosshair".equals(id)
                || "#brightness".equals(id) || "#rotate".equals(id)
                || "#dnd".equals(id) || "#autoclick".equals(id)
                || "#refimage".equals(id) || "#sticky".equals(id);
    }

    private boolean toggleOn(String id) {
        if ("#hud".equals(id)) return hudView != null;
        if ("#crosshair".equals(id)) return crosshairView != null;
        if ("#brightness".equals(id)) return lockPrefs().getBoolean("bright", false);
        if ("#rotate".equals(id)) return lockPrefs().getBoolean("rotate", false);
        if ("#dnd".equals(id)) return dndOn();
        if ("#autoclick".equals(id)) return autoRunning;
        if ("#refimage".equals(id)) return refPinned;
        if ("#sticky".equals(id)) return stickyOn;
        return false;
    }

    private boolean dndOn() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return false;
        try {
            int filter = nm.getCurrentInterruptionFilter();
            return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        } catch (Exception e) {
            return false;
        }
    }

    private int toggleColor(String id) {
        if ("#crosshair".equals(id) || "#autoclick".equals(id)
                || "#refimage".equals(id) || "#sticky".equals(id)) return TOGGLE_CYAN;
        if ("#dnd".equals(id)) return TOGGLE_AMBER;
        return TOGGLE_GREEN;
    }

    private int toggleBoxColor(String id) {
        if ("#crosshair".equals(id) || "#autoclick".equals(id)
                || "#refimage".equals(id) || "#sticky".equals(id)) return TOGGLE_CYAN_BOX;
        if ("#dnd".equals(id)) return TOGGLE_AMBER_BOX;
        return TOGGLE_GREEN_BOX;
    }

    /** Colours a toggle menu icon green/cyan/amber while the feature is on. */
    private void paintToggle(String id, boolean on, boolean animate) {
        final ImageView icon = toggleIcons.get(id);
        final View box = toggleBoxes.get(id);
        if (icon == null || box == null) return;
        final int iconTo = on ? toggleColor(id) : Ui.ACCENT;
        final int boxTo = on ? toggleBoxColor(id) : ITEM_BG_SOFT;

        if (!animate) {
            toggleIconTint.put(id, iconTo);
            toggleBoxTint.put(id, boxTo);
            icon.setColorFilter(iconTo);
            setBoxColor(box, boxTo);
            return;
        }

        Integer storedIcon = toggleIconTint.get(id);
        Integer storedBox = toggleBoxTint.get(id);
        final int iconFrom = storedIcon == null ? Ui.ACCENT : storedIcon;
        final int boxFrom = storedBox == null ? ITEM_BG_SOFT : storedBox;
        final String key = id;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(180);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                float t = va.getAnimatedFraction();
                int iconColor = blend(iconFrom, iconTo, t);
                int boxColor = blend(boxFrom, boxTo, t);
                toggleIconTint.put(key, iconColor);
                toggleBoxTint.put(key, boxColor);
                ImageView currentIcon = toggleIcons.get(key);
                View currentBox = toggleBoxes.get(key);
                if (currentIcon != null) currentIcon.setColorFilter(iconColor);
                if (currentBox != null) setBoxColor(currentBox, boxColor);
            }
        });
        a.start();
        Ui.pop(box);
    }

    private void perform(String id) {
        if ("#torch".equals(id)) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Cấp quyền Camera trong app trước");
                return;
            }
            if (Torch.id(this) == null) {
                toast("Máy không có đèn flash");
                return;
            }
            if (Torch.toggle(this)) {
                paintTorch(Torch.isOn(), true);
            } else {
                toast("Không bật được đèn pin");
            }
            return; // keep panel open for another toggle
        }

        if ("#quicknote".equals(id)) {
            toggleTab(TAB_QUICKNOTE);
            return;
        }
        if ("#notes".equals(id)) {
            toggleTab(TAB_NOTES);
            return;
        }

        if ("#shot".equals(id) || "#capture".equals(id)) {
            toggleTab(TAB_SHOT);
            return;
        }
        if ("#volume".equals(id)) {
            toggleTab(TAB_VOLUME);
            return;
        }
        if ("#tally".equals(id)) {
            toggleTab(TAB_TALLY);
            return;
        }
        if ("#clip".equals(id)) {
            toggleTab(TAB_CLIP);
            return;
        }
        if ("#qr".equals(id)) {
            toggleTab(TAB_QR);
            return;
        }
        if ("#crosshair".equals(id)) {
            toggleTab(TAB_CROSSHAIR);
            return;
        }
        if ("#timers".equals(id)) {
            toggleTab(TAB_TIMERS);
            return;
        }
        if ("#brightness".equals(id)) {
            toggleBrightnessLock();
            return;
        }
        if ("#rotate".equals(id)) {
            toggleRotationLock();
            return;
        }
        if ("#dnd".equals(id)) {
            toggleDnd();
            return;
        }
        if ("#autoclick".equals(id)) {
            toggleTab(TAB_AUTOCLICK);
            return;
        }
        if ("#secure".equals(id)) {
            toggleTab(TAB_SECURE);
            return;
        }
        if ("#battery".equals(id)) {
            toggleTab(TAB_BATTERY);
            return;
        }
        if ("#refimage".equals(id)) {
            toggleTab(TAB_REFIMAGE);
            return;
        }
        if ("#side".equals(id)) {
            switchSidebarSide();
            return;
        }
        if ("#peeker".equals(id)) {
            toggleTab(TAB_PEEKER);
            return;
        }
        if ("#sticky".equals(id)) {
            toggleTab(TAB_STICKY);
            return;
        }
        if ("#toasttimer".equals(id)) {
            toggleTab(TAB_REMINDER);
            return;
        }
        if ("#farm".equals(id)) {
            toggleTab(TAB_FARM);
            return;
        }
        if ("#dmgcalc".equals(id)) {
            toggleTab(TAB_DMG);
            return;
        }
        if ("#bpm".equals(id)) {
            toggleTab(TAB_BPM);
            return;
        }
        if ("#data".equals(id)) {
            closePanel(false);
            openSystemSettings(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                    Settings.ACTION_WIRELESS_SETTINGS);
            return;
        }
        if ("#hotspot".equals(id)) {
            closePanel(false);
            openSystemSettings(new Intent("android.settings.TETHER_SETTINGS"),
                    Settings.ACTION_WIRELESS_SETTINGS);
            return;
        }
        if ("#ruler".equals(id)) {
            closePanel(false);
            handler.postDelayed(new Runnable() {
                public void run() {
                    hideHandleForCapture();
                    startRuler();
                }
            }, 240);
            return;
        }
        if ("#black".equals(id)) {
            closePanel(false);
            handler.postDelayed(new Runnable() {
                public void run() {
                    hideHandleForCapture();
                    startBlackScreen();
                }
            }, 240);
            return;
        }
        if ("#hud".equals(id)) {
            toggleHud();
            return;
        }
        if ("#power".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_POWER_DIALOG);
            return;
        }
        if ("#notifications".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_NOTIFICATIONS);
            return;
        }
        if ("#quicksettings".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_QUICK_SETTINGS);
            return;
        }
        if ("#back".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            return;
        }
        if ("#home".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        if ("#recents".equals(id)) {
            fireGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);
            return;
        }
        if ("#ringer".equals(id)) {
            cycleRinger();
            return;
        }
        if ("#caffeine".equals(id)) {
            toggleCaffeine();
            return;
        }
        if ("#color".equals(id)) {
            startColorPick();
            return;
        }
        if ("#lock".equals(id)) {
            closePanel(false);
            handler.postDelayed(new Runnable() {
                public void run() {
                    enableTouchLock();
                }
            }, 240);
            return;
        }

        Class<?> target = null;
        if ("#timer".equals(id)) target = StopwatchActivity.class;
        else if ("#convert".equals(id)) target = ConverterActivity.class;
        else if ("#menu".equals(id)) target = MainActivity.class;

        if (target != null) open(target);
        closePanel();
    }

    private void open(Class<?> c) {
        try {
            Intent i = new Intent(this, c);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Không mở được");
        }
    }

    // ------------------------------------------------------------ quick note tab

    private void openQuickNote() {
        buildingTab = TAB_QUICKNOTE;
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        card.setFocusableInTouchMode(true);

        card.addView(header("Ghi chú nhanh", false, null));

        final EditText input = new EditText(this);
        input.setBackground(Ui.framed(0x33000000, this, 12));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xB3AAB3C4);
        input.setHint("Viết gì đó...");
        input.setTextSize(14);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(10);
        card.addView(input, ip);

        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.END);

        TextView cancel = Ui.text(this, "Huỷ", 13, Ui.MUTED, false);
        cancel.setPadding(dp(16), dp(12), dp(16), dp(12));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeTab(TAB_QUICKNOTE);
            }
        });
        buttons.addView(cancel);
        Ui.press(cancel, 0.94f);

        TextView save = Ui.text(this, "Lưu", 13, 0xFF0B1020, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Ui.bg(Ui.ACCENT, this, 12));
        save.setPadding(dp(22), dp(9), dp(22), dp(9));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = input.getText().toString();
                if (TextUtils.isEmpty(text.trim())) {
                    toast("Chưa có nội dung");
                    return;
                }
                Notes.insert(SidebarService.this, text);
                toast("Đã lưu ghi chú");
                closeTab(TAB_QUICKNOTE);
            }
        });
        buttons.addView(save);
        Ui.press(save, 0.94f);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(6);
        card.addView(buttons, bp);

        // Back key inside the focusable overlay closes the tab.
        card.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                    closeTab(TAB_QUICKNOTE);
                    return true;
                }
                return false;
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                    closeTab(TAB_QUICKNOTE);
                    return true;
                }
                return false;
            }
        });

        buildingTab = null;
        addTab(TAB_QUICKNOTE, card);
        setDockFocusable(true);
        input.postDelayed(new Runnable() {
            public void run() {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 240);
    }

    // ------------------------------------------------------------ notes tab (left)

    private int notesWidth() {
        return Math.min(screenW() - dp(48), dp(320));
    }

    private void openNotes() {
        if (tabViews.containsKey(TAB_NOTES)) return;
        buildingTab = TAB_NOTES;
        LinearLayout card = buildNotesPage();
        buildingTab = null;
        addTab(TAB_NOTES, card);
    }

    private LinearLayout buildNotesPage() {
        LinearLayout wrap = glassCard();
        wrap.setPadding(dp(12), dp(10), dp(12), dp(10));

        wrap.addView(header("Ghi chú", false, null));

        TextView hint = Ui.text(this, "Chạm để xem · × để xoá", 11, Ui.MUTED, false);
        hint.setLayoutParams(Ui.lp());
        Ui.margin(this, hint, 2, 2, 0, 6);
        wrap.addView(hint);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = Ui.col(this);
        notesList = list;
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        TextView all = Ui.text(this, "Mở màn ghi chú đầy đủ", 12, Ui.ACCENT, false);
        all.setPadding(dp(2), dp(12), 0, dp(2));
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
                open(NotesActivity.class);
            }
        });
        wrap.addView(all);
        Ui.press(all, 0.96f);

        renderNotes();
        return wrap;
    }

    private LinearLayout notesList;

    private void renderNotes() {
        if (notesList == null) return;
        notesList.removeAllViews();
        List<Notes.Note> notes = Notes.load(this);

        if (notes.isEmpty()) {
            TextView empty = Ui.text(this, "Chưa có ghi chú nào", 13, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, dp(28));
            notesList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (final Notes.Note note : notes) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(8), dp(8), dp(2), dp(8));
            row.setBackground(Ui.framed(ITEM_BG_SOFT, this, 14));

            LinearLayout col = Ui.col(this);
            TextView body = Ui.text(this, note.text, 13, Ui.TEXT, false);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(body);

            TextView time = Ui.text(this, when(note.time), 10, Ui.MUTED, false);
            time.setLayoutParams(Ui.lp());
            Ui.margin(this, time, 0, 4, 0, 0);
            col.addView(time);

            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView del = Ui.text(this, "\u00d7", 18, 0xFF8B94A7, false);
            del.setGravity(Gravity.CENTER);
            del.setPadding(dp(12), dp(4), dp(9), dp(4));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Notes.delete(SidebarService.this, note);
                    toast("Đã xoá ghi chú");
                    renderNotes();
                }
            });
            row.addView(del);
            Ui.press(del, 0.82f);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openNoteDetail(note);
                }
            });
            row.setClickable(true);
            Ui.press(row, 0.98f);

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = dp(6);
            notesList.addView(row, p);
        }
    }

    private void openNoteDetail(final Notes.Note note) {
        buildingTab = TAB_NOTEDETAIL;
        LinearLayout wrap = glassCard();
        wrap.setPadding(dp(14), dp(10), dp(14), dp(12));

        wrap.addView(header("Ghi chú", true, new View.OnClickListener() {
            public void onClick(View v) {
                closeTab(TAB_NOTEDETAIL);
            }
        }));

        TextView time = Ui.text(this, when(note.time), 10, Ui.MUTED, false);
        time.setLayoutParams(Ui.lp());
        Ui.margin(this, time, 2, 2, 0, 6);
        wrap.addView(time);

        ScrollView sv = new ScrollView(this);
        TextView body = Ui.text(this, note.text, 14, Ui.TEXT, false);
        body.setLineSpacing(dp(4), 1f);
        int pad = dp(10);
        body.setPadding(pad, pad, pad, pad);
        body.setBackground(Ui.framed(0x33000000, this, 14));
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        TextView del = Ui.text(this, "Xoá ghi chú", 12, 0xFFFF9A9A, false);
        del.setGravity(Gravity.CENTER);
        del.setPadding(0, dp(12), 0, dp(4));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Notes.delete(SidebarService.this, note);
                toast("Đã xoá ghi chú");
                closeTab(TAB_NOTEDETAIL);
                renderNotes();
            }
        });
        wrap.addView(del);
        Ui.press(del, 0.96f);

        buildingTab = null;
        closeTab(TAB_NOTEDETAIL);
        addTab(TAB_NOTEDETAIL, wrap);
    }

    private String when(long t) {
        if (t <= 0) return "";
        long d = System.currentTimeMillis() - t;
        if (d < 60000L) return "vừa xong";
        if (d < 3600000L) return (d / 60000L) + " phút trước";
        if (d < 86400000L) return (d / 3600000L) + " giờ trước";
        return new SimpleDateFormat("d MMM, HH:mm", new Locale("vi", "VN")).format(new Date(t));
    }


    // ------------------------------------------------------------ screenshot tab (left)

    private int captureWidth() {
        return Math.min(screenW() - dp(40), dp(360));
    }

    private void openShot() {
        if (tabViews.containsKey(TAB_SHOT)) return;
        buildingTab = TAB_SHOT;
        LinearLayout card = buildShotPanel();
        buildingTab = null;
        addTab(TAB_SHOT, card);
    }

    private LinearLayout buildShotPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(16));

        card.addView(header("Chụp màn hình", false, null));

        boolean ready = ShotService.ready();
        TextView hint = Ui.text(this, ready
                ? "Ảnh lưu ngay, không hỏi quyền. Tay nắm tự ẩn khi chụp."
                : "Bật Trợ năng cho Essentials một lần để chụp không popup.",
                11, ready ? Ui.MUTED : 0xFFFFC57A, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(12);
        card.addView(hint, hp);

        LinearLayout full = shotButton("Chụp toàn màn hình",
                "Cả màn hình, đúng độ phân giải máy " + screenW() + "×" + screenH(), true);
        full.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startShotAction(false);
            }
        });
        card.addView(full, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout region = shotButton("Chọn vùng",
                "Kéo khung trên màn hình rồi bấm Lưu vùng chọn", false);
        region.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startShotAction(true);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        rlp.topMargin = dp(10);
        card.addView(region, rlp);

        TextView path = Ui.text(this, "Pictures/Essentials", 11, Ui.MUTED, false);
        path.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(12);
        card.addView(path, pp);

        return card;
    }

    private LinearLayout shotButton(String title, String sub, boolean primary) {
        LinearLayout b = Ui.col(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.bg(primary ? 0xFF23314C : 0xFF1B2230, Ui.FRAME, this, 16, 1));
        b.setPadding(dp(14), dp(9), dp(14), dp(9));

        TextView t = Ui.text(this, title, 14, primary ? 0xFFDCE7FF : Ui.TEXT, true);
        t.setLayoutParams(Ui.lp());
        b.addView(t);

        TextView s = Ui.text(this, sub, 11, Ui.MUTED, false);
        s.setLayoutParams(Ui.lp());
        Ui.margin(this, s, 0, 3, 0, 0);
        b.addView(s);

        b.setClickable(true);
        Ui.press(b, 0.97f);
        return b;
    }

    // ------------------------------------------------------------ shot runtime

    private View pickRoot;
    private Bitmap freezeBmp;
    private boolean shotBusy;
    private int lastConfigW = -1;
    private int lastConfigH = -1;

    private void startShotAction(final boolean region) {
        if (!ShotService.ready()) {
            toast("Bật Trợ năng cho Essentials một lần rồi thử lại");
            openAccessibilitySettings();
            closePanel(false);
            return;
        }
        if (shotBusy) return;
        shotBusy = true;
        closePanel(false);
        hideHandleForCapture();
        if (hudView != null) hudView.setVisibility(View.GONE);
        if (region) {
            // Freeze first: the screenshot is taken a beat after the panel is
            // removed, then the picker draws that exact frame. Whatever was on
            // screen at the tap stays put while the user drags a rectangle.
            handler.postDelayed(new Runnable() {
                public void run() {
                    freezeShotPicker();
                }
            }, 90);
        } else {
            handler.postDelayed(new Runnable() {
                public void run() {
                    grabShot(null);
                }
            }, 420);
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Mở Cài đặt → Trợ năng để bật Essentials");
        }
    }

    private void grabShot(final Rect crop) {
        ShotService.take(new ShotService.ShotCallback() {
            public void onShot(Bitmap bmp) {
                saveShot(bmp, crop);
            }

            public void onFail(String message) {
                shotBusy = false;
                toast(message);
                restoreAfterCapture();
            }
        });
    }

    /**
     * Crops the frozen frame using the same stretch the picker drew, so the
     * saved region is exactly the rectangle the user saw, even when the
     * screenshot was captured without the status bar or at a lower resolution.
     */
    private void saveFrozenShot(Bitmap frozen, Rect crop, int viewW, int viewH) {
        Bitmap out = frozen;
        if (crop != null) {
            try {
                int w = frozen.getWidth();
                int h = frozen.getHeight();
                float scale = Math.min(viewW / (float) Math.max(1, w),
                        viewH / (float) Math.max(1, h));
                if (scale <= 0f) scale = 1f;
                float drawnW = w * scale;
                float drawnH = h * scale;
                float offX = (viewW - drawnW) / 2f;
                float offY = (viewH - drawnH) / 2f;
                int l = clamp(Math.round((crop.left - offX) / scale), 0, w - 2);
                int t = clamp(Math.round((crop.top - offY) / scale), 0, h - 2);
                int r = clamp(Math.round((crop.right - offX) / scale), l + 2, w);
                int b = clamp(Math.round((crop.bottom - offY) / scale), t + 2, h);
                out = Bitmap.createBitmap(frozen, l, t, r - l, b - t);
            } catch (Exception ignored) {
                out = frozen;
            }
        }
        String path = Shots.saveImage(this, out);
        if (out != frozen) out.recycle();
        if (!frozen.isRecycled()) frozen.recycle();
        shotBusy = false;
        toast(path != null ? "Đã lưu: " + path : "Không lưu được ảnh");
        restoreAfterCapture();
    }

    private void saveShot(Bitmap bmp, Rect crop) {
        Bitmap out = bmp;
        if (crop != null) {
            Rect c = new Rect(crop);
            // The picker reports physical screen coordinates, but the
            // accessibility screenshot can be captured at a different size
            // (system bars, cutouts, reduced-resolution screenshots). Map the
            // rectangle onto the bitmap instead of assuming 1:1 pixels, which
            // used to shift the result upwards on some displays.
            DisplayMetrics dm = new DisplayMetrics();
            try {
                wm.getDefaultDisplay().getRealMetrics(dm);
            } catch (Exception e) {
                dm = getResources().getDisplayMetrics();
            }
            if (dm.widthPixels > 0 && dm.heightPixels > 0
                    && (dm.widthPixels != out.getWidth()
                    || dm.heightPixels != out.getHeight())) {
                float rx = out.getWidth() / (float) dm.widthPixels;
                float ry = out.getHeight() / (float) dm.heightPixels;
                if (Math.abs(rx - ry) < 0.01f) {
                    // Uniformly downscaled screenshot: scale the rectangle.
                    c.left = Math.round(c.left * rx);
                    c.top = Math.round(c.top * ry);
                    c.right = Math.round(c.right * rx);
                    c.bottom = Math.round(c.bottom * ry);
                } else {
                    // The bitmap is the display minus one or more system bars,
                    // so it still starts at the top-left corner. Only the
                    // status bar moves the origin downwards.
                    int statusH = systemBarHeight("status_bar_height");
                    int navH = systemBarHeight("navigation_bar_height");
                    int lostH = dm.heightPixels - out.getHeight();
                    if (statusH > 0 && (Math.abs(lostH - statusH) <= dp(2)
                            || (navH > 0
                            && Math.abs(lostH - statusH - navH) <= dp(2)))) {
                        c.top -= statusH;
                        c.bottom -= statusH;
                    }
                }
            }
            c.left = Math.max(0, Math.min(c.left, out.getWidth() - 2));
            c.top = Math.max(0, Math.min(c.top, out.getHeight() - 2));
            c.right = Math.max(c.left + 2, Math.min(c.right, out.getWidth()));
            c.bottom = Math.max(c.top + 2, Math.min(c.bottom, out.getHeight()));
            try {
                Bitmap cut = Bitmap.createBitmap(out, c.left, c.top,
                        c.right - c.left, c.bottom - c.top);
                out.recycle();
                out = cut;
            } catch (Exception ignored) {
            }
        }
        String path = Shots.saveImage(this, out);
        out.recycle();
        if (bmp != out && !bmp.isRecycled()) bmp.recycle();
        shotBusy = false;
        toast(path != null ? "Đã lưu: " + path : "Không lưu được ảnh");
        restoreAfterCapture();
    }

    /** Captures one frame so the region picker works on a still image. */
    private void freezeShotPicker() {
        ShotService.take(new ShotService.ShotCallback() {
            public void onShot(Bitmap bmp) {
                if (bmp == null) {
                    shotBusy = false;
                    toast("Không chụp được màn hình");
                    restoreAfterCapture();
                    return;
                }
                showShotPicker(bmp);
            }

            public void onFail(String message) {
                shotBusy = false;
                toast(message);
                restoreAfterCapture();
            }
        });
    }

    private void showShotPicker(final Bitmap frozen) {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        if (frozen != null) {
            freezeBmp = frozen;
            ImageView still = new ImageView(this);
            // FIT_CENTER keeps the aspect ratio: stretching the frame to the
            // window made the picture look squashed. The crop maths below uses
            // the same fit so the saved region matches what is visible.
            still.setScaleType(ImageView.ScaleType.FIT_CENTER);
            still.setImageBitmap(frozen);
            root.addView(still, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        final PickView pick = new PickView(this);
        root.addView(pick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView hint = Ui.text(this, "Kéo để chọn vùng cần chụp", 13, Ui.TEXT, false);
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
                removeShotPicker();
                shotBusy = false;
                toast("Đã huỷ");
                restoreAfterCapture();
            }
        });
        bar.addView(cancel);
        Ui.press(cancel, 0.94f);

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
                Bitmap still = freezeBmp;
                freezeBmp = null;
                int viewW = pick.getWidth();
                int viewH = pick.getHeight();
                removeShotPicker();
                if (still != null) {
                    saveFrozenShot(still, r, viewW, viewH);
                } else {
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            grabShot(r);
                        }
                    }, 350);
                }
            }
        });
        bar.addView(ok);
        Ui.press(ok, 0.94f);

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

        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, lp);
            pickRoot = root;
        } catch (Exception e) {
            shotBusy = false;
            toast("Không mở được vùng chọn");
            restoreAfterCapture();
        }
    }

    private void removeShotPicker() {
        if (pickRoot != null) {
            try {
                wm.removeView(pickRoot);
            } catch (Exception ignored) {
            }
            pickRoot = null;
        }
        if (freezeBmp != null) {
            freezeBmp.recycle();
            freezeBmp = null;
        }
    }

    /** Dim overlay with a draggable rectangle. */
    private class PickView extends View {
        private final Paint dim = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sel = new RectF();
        private final RectF rawSel = new RectF();
        private float startX, startY;
        private float rawStartX, rawStartY;
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
                    rawStartX = e.getRawX();
                    rawStartY = e.getRawY();
                    sel.set(startX, startY, startX, startY);
                    rawSel.set(rawStartX, rawStartY, rawStartX, rawStartY);
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    sel.set(Math.min(startX, e.getX()), Math.min(startY, e.getY()),
                            Math.max(startX, e.getX()), Math.max(startY, e.getY()));
                    rawSel.set(Math.min(rawStartX, e.getRawX()),
                            Math.min(rawStartY, e.getRawY()),
                            Math.max(rawStartX, e.getRawX()),
                            Math.max(rawStartY, e.getRawY()));
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
            if (rawSel.width() < 2 || rawSel.height() < 2) return null;
            // Raw coordinates are in display space, unaffected by any window
            // origin offset introduced by the overlay flags.
            return new Rect(Math.round(rawSel.left), Math.round(rawSel.top),
                    Math.round(rawSel.right), Math.round(rawSel.bottom));
        }
    }

    // ------------------------------------------------------------ system actions

    /** Closes the menu, waits for the overlay to slide away, then fires an action. */
    private void fireGlobal(final int action) {
        if (!ShotService.ready()) {
            toast("Bật Trợ năng cho Essentials để dùng nút này");
            openAccessibilitySettings();
            closePanel(false);
            return;
        }
        closePanel(false);
        handler.postDelayed(new Runnable() {
            public void run() {
                if (!ShotService.global(action)) {
                    toast("Máy không cho thực hiện thao tác này");
                }
            }
        }, 320);
    }

    /** RINGER_MODE_NORMAL -> VIBRATE -> SILENT -> NORMAL. */
    private void cycleRinger() {
        final AudioManager am = audio();
        if (am == null) return;
        int mode = am.getRingerMode();
        int next = mode == AudioManager.RINGER_MODE_NORMAL
                ? AudioManager.RINGER_MODE_VIBRATE
                : mode == AudioManager.RINGER_MODE_VIBRATE
                ? AudioManager.RINGER_MODE_SILENT
                : AudioManager.RINGER_MODE_NORMAL;
        try {
            am.setRingerMode(next);
            toast(next == AudioManager.RINGER_MODE_NORMAL ? "Chuông"
                    : next == AudioManager.RINGER_MODE_VIBRATE ? "Rung" : "Im lặng");
        } catch (Exception e) {
            toast("Hệ thống chặn đổi chế độ — cấp quyền Không làm phiền");
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ keep screen on

    private void toggleCaffeine() {
        keepAwake = !keepAwake;
        Side.setCaffeineOn(this, keepAwake);
        applyKeepAwake(keepAwake);
        paintCaffeine(true);
        toast(keepAwake ? "Giữ sáng màn hình: BẬT" : "Giữ sáng màn hình: TẮT");
    }

    /**
     * FLAG_KEEP_SCREEN_ON on the always-visible handle (and the HUD when shown)
     * keeps the display awake without a wake lock, so there is nothing to leak.
     */
    private void applyKeepAwake(boolean on) {
        int flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (handleParams != null && handle != null) {
            handleParams.flags = on ? (handleParams.flags | flag)
                    : (handleParams.flags & ~flag);
            try {
                wm.updateViewLayout(handle, handleParams);
            } catch (Exception ignored) {
            }
        }
        if (hudParams != null && hudView != null) {
            hudParams.flags = on ? (hudParams.flags | flag)
                    : (hudParams.flags & ~flag);
            try {
                wm.updateViewLayout(hudView, hudParams);
            } catch (Exception ignored) {
            }
        }
    }

    /** Yellow when the screen is pinned awake, default otherwise. */
    private void paintCaffeine(boolean animate) {
        if (caffeineIcon == null || caffeineBox == null) return;
        final int iconTo = keepAwake ? 0xFFFFD166 : Ui.ACCENT;
        final int boxTo = keepAwake ? 0x33FFD166 : ITEM_BG_SOFT;

        if (!animate) {
            caffeineIconTint = iconTo;
            caffeineBoxTint = boxTo;
            caffeineIcon.setColorFilter(iconTo);
            setBoxColor(caffeineBox, boxTo);
            return;
        }

        final int iconFrom = caffeineIconTint;
        final int boxFrom = caffeineBoxTint;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(180);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                float t = va.getAnimatedFraction();
                caffeineIconTint = blend(iconFrom, iconTo, t);
                caffeineBoxTint = blend(boxFrom, boxTo, t);
                if (caffeineIcon != null) caffeineIcon.setColorFilter(caffeineIconTint);
                if (caffeineBox != null) setBoxColor(caffeineBox, caffeineBoxTint);
            }
        });
        a.start();
        Ui.pop(caffeineBox);
    }

    // ------------------------------------------------------------ touch lock

    private void enableTouchLock() {
        if (lockRoot != null) return;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable shield = new GradientDrawable();
        shield.setColor(0x08000000);
        shield.setStroke(Math.max(1, dp(2)), 0x806E9BFF);
        root.setBackground(shield);

        // Swallow every touch that is not on the unlock button.
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                return true;
            }
        });

        final TextView hint = Ui.text(this,
                "Đã khoá cảm ứng · chạm ổ khoá để mở", 12, Ui.TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.framed(0xE6171C24, this, 14));
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.END;
        hp.bottomMargin = dp(98);
        hp.rightMargin = dp(18);
        root.addView(hint, hp);

        FrameLayout lock = new FrameLayout(this);
        lock.setBackground(Ui.framed(0xE6171C24, this, 27));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(0xFFFFD166);
        lock.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        lock.setContentDescription("Mở khoá cảm ứng");
        lock.setClickable(true);
        lock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeTouchLock(true);
            }
        });
        Ui.press(lock, 0.90f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(54), dp(54));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.bottomMargin = dp(28);
        lp.rightMargin = dp(18);
        root.addView(lock, lp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, wlp);
            lockRoot = root;
        } catch (Exception e) {
            toast("Không khoá được cảm ứng");
            return;
        }

        toast("Đã khoá cảm ứng");
        hint.animate().alpha(0f).setStartDelay(2600).setDuration(600)
                .withEndAction(new Runnable() {
                    public void run() {
                        hint.setVisibility(View.GONE);
                    }
                }).start();
    }

    private void removeTouchLock(boolean announce) {
        if (lockRoot == null) return;
        try {
            wm.removeView(lockRoot);
        } catch (Exception ignored) {
        }
        lockRoot = null;
        if (announce) {
            toast("Đã mở khoá cảm ứng");
            if (panel == null) openMenu();
        }
    }

    // ------------------------------------------------------------ colour picker

    private void startColorPick() {
        if (!ShotService.ready()) {
            toast("Bật Trợ năng cho Essentials để soi màu");
            openAccessibilitySettings();
            closePanel(false);
            return;
        }
        if (shotBusy) return;
        shotBusy = true;
        closePanel(false);
        hideHandleForCapture();
        if (hudView != null) hudView.setVisibility(View.GONE);
        handler.postDelayed(new Runnable() {
            public void run() {
                ShotService.take(new ShotService.ShotCallback() {
                    public void onShot(Bitmap bmp) {
                        shotBusy = false;
                        showColorPicker(bmp);
                    }

                    public void onFail(String message) {
                        shotBusy = false;
                        toast(message);
                        restoreAfterCapture();
                    }
                });
            }
        }, 420);
    }

    private void showColorPicker(Bitmap bmp) {
        colorBitmap = bmp;
        FrameLayout root = new FrameLayout(this);
        final ColorPickView pick = new ColorPickView(this);
        root.addView(pick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView hex = Ui.text(this, "#------", 15, Ui.TEXT, true);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.framed(0xF0171C24, this, 16));
        bar.setPadding(dp(14), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(hex, hp);

        TextView copy = Ui.text(this, "Chép", 13, 0xFF0B1020, true);
        copy.setGravity(Gravity.CENTER);
        copy.setBackground(Ui.bg(Ui.ACCENT, this, 12));
        copy.setPadding(dp(16), dp(9), dp(16), dp(9));
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (copyHex(pick.hex())) Ui.pop(copy);
            }
        });
        bar.addView(copy);
        Ui.press(copy, 0.94f);

        TextView close = Ui.text(this, "Đóng", 13, Ui.MUTED, false);
        close.setPadding(dp(14), dp(9), dp(12), dp(9));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeColorPicker(true);
            }
        });
        bar.addView(close);
        Ui.press(close, 0.94f);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;
        bp.leftMargin = dp(14);
        bp.rightMargin = dp(14);
        bp.bottomMargin = dp(30);
        root.addView(bar, bp);

        TextView hint = Ui.text(this,
                "Kéo để soi màu · chạm để chép mã", 11, Ui.TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.framed(0xCC171C24, this, 13));
        hint.setPadding(dp(12), dp(7), dp(12), dp(7));
        FrameLayout.LayoutParams hp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp2.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp2.topMargin = dp(40);
        root.addView(hint, hp2);

        pick.onColor = new Runnable() {
            public void run() {
                hex.setText(pick.hex());
            }
        };
        pick.onTap = new Runnable() {
            public void run() {
                copyHex(pick.hex());
            }
        };
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, wlp);
            colorRoot = root;
        } catch (Exception e) {
            colorRoot = null;
            closeColorPicker(true);
            toast("Không mở được bảng soi màu");
        }
    }

    private boolean copyHex(String value) {
        if (value == null || value.startsWith("#-")) return false;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("Hex", value));
            toast("Đã chép " + value);
            return true;
        } catch (Exception e) {
            toast("Không chép được");
            return false;
        }
    }

    private void closeColorPicker(boolean restore) {
        if (colorRoot != null) {
            try {
                wm.removeView(colorRoot);
            } catch (Exception ignored) {
            }
            colorRoot = null;
        }
        if (colorBitmap != null) {
            try {
                colorBitmap.recycle();
            } catch (Exception ignored) {
            }
            colorBitmap = null;
        }
        if (restore) {
            if (hudView != null) hudView.setVisibility(View.VISIBLE);
            restoreAfterCapture();
        }
    }

    // ------------------------------------------------------------ volume tab (right)

    private void openVolume() {
        if (tabViews.containsKey(TAB_VOLUME)) return;
        buildingTab = TAB_VOLUME;
        LinearLayout card = buildVolumePanel();
        buildingTab = null;
        addTab(TAB_VOLUME, card);
    }

    private AudioManager audio() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private LinearLayout buildVolumePanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        card.addView(header("Âm lượng", false, null));

        TextView hint = Ui.text(this, "Kéo để chỉnh · giá trị đang áp dụng",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(10);
        card.addView(hint, hp);

        card.addView(volumeRow(AudioManager.STREAM_MUSIC, "Nhạc & video", true));
        card.addView(volumeRow(AudioManager.STREAM_RING, "Chuông & thông báo", false));
        card.addView(volumeRow(AudioManager.STREAM_ALARM, "Báo thức", false));

        return card;
    }

    private View volumeRow(final int stream, String label, boolean first) {
        LinearLayout col = Ui.col(this);

        LinearLayout head = Ui.row(this);
        TextView name = Ui.text(this, label, 12, Ui.TEXT, true);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final AudioManager am = audio();
        final int max = am == null ? 1 : Math.max(1, am.getStreamMaxVolume(stream));
        final TextView value = Ui.text(this, percent(am, stream), 11, Ui.ACCENT, true);
        head.addView(value);
        col.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(am == null ? 0 : am.getStreamVolume(stream));
        try {
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        } catch (Exception ignored) {
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || am == null) return;
                try {
                    am.setStreamVolume(stream, progress, 0);
                    value.setText((progress * 100 / Math.max(1, sb.getMax())) + "%");
                } catch (Exception e) {
                    toast("Hệ thống đang chặn đổi âm lượng");
                }
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        bp.topMargin = dp(4);
        col.addView(bar, bp);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) cp.topMargin = dp(10);
        col.setLayoutParams(cp);
        return col;
    }

    private String percent(AudioManager am, int stream) {
        if (am == null) return "--";
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        return (am.getStreamVolume(stream) * 100 / max) + "%";
    }

    // ------------------------------------------------------------ performance HUD

    private TextView hudView;
    private WindowManager.LayoutParams hudParams;
    private boolean hudCompact;
    private boolean hudLongFired;
    private boolean hudHiding;

    private final Runnable hudTick = new Runnable() {
        public void run() {
            updateHud();
            handler.postDelayed(SidebarService.this.hudTick, 1000);
        }
    };

    private final Runnable hudLongPress = new Runnable() {
        public void run() {
            hudLongFired = true;
            if (hudView != null) removeHud(true);
        }
    };

    private void toggleHud() {
        if (hudView != null) removeHud(true);
        else addHud(true);
    }

    private void addHud(boolean announce) {
        if (hudView != null) return;
        hudCompact = Side.hudCompact(this);

        TextView v = new TextView(this);
        v.setTextColor(Ui.TEXT);
        v.setTypeface(android.graphics.Typeface.create("monospace",
                android.graphics.Typeface.NORMAL));
        v.setBackground(Ui.framed(0xE6171C24, this, 14));
        v.setSingleLine(true);
        hudView = v;
        applyHudStyle();

        hudParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (keepAwake ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0),
                PixelFormat.TRANSLUCENT);
        hudParams.gravity = Gravity.TOP | Gravity.START;
        int sx = Side.hudX(this);
        int sy = Side.hudY(this);
        hudParams.x = sx >= 0 ? sx : dp(16);
        hudParams.y = sy >= 0 ? sy : dp(96);

        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = hudParams.x;
                        startY = hudParams.y;
                        moved = false;
                        hudLongFired = false;
                        handler.postDelayed(hudLongPress,
                                ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int slop = ViewConfiguration.get(SidebarService.this)
                                .getScaledTouchSlop();
                        if (!moved && (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop)) {
                            moved = true;
                            handler.removeCallbacks(hudLongPress);
                        }
                        if (moved) {
                            hudParams.x = clamp(startX + (int) (e.getRawX() - downX), 0,
                                    Math.max(0, screenW() - view.getWidth()));
                            hudParams.y = clamp(startY + (int) (e.getRawY() - downY), 0,
                                    Math.max(0, screenH() - view.getHeight()));
                            try {
                                wm.updateViewLayout(hudView, hudParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(hudLongPress);
                        if (moved) {
                            Side.setHudPos(SidebarService.this, hudParams.x, hudParams.y);
                        } else if (!hudLongFired && hudView != null) {
                            hudCompact = !hudCompact;
                            Side.setHudCompact(SidebarService.this, hudCompact);
                            applyHudStyle();
                            updateHud();
                            Ui.pop(hudView);
                            handler.post(new Runnable() {
                                public void run() {
                                    clampHudPosition();
                                    if (hudParams != null) {
                                        Side.setHudPos(SidebarService.this,
                                                hudParams.x, hudParams.y);
                                    }
                                }
                            });
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(v, hudParams);
            clampHudPosition();
            if (sx < 0 || sy < 0) {
                Side.setHudPos(this, hudParams.x, hudParams.y);
            }
        } catch (Exception e) {
            hudView = null;
            hudParams = null;
            toast("Không tạo được HUD");
            return;
        }

        Side.setHudOn(this, true);
        paintToggle("#hud", true, true);
        hudHiding = false;
        applyKeepAwake(keepAwake);
        Stats.cpu(); // prime the /proc/stat delta
        hudView.setText(hudCompact ? "--% • -- • -- • --°C"
                : "CPU --%  FPS --  RAM --  --°C");
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
        handler.removeCallbacks(hudTick);
        handler.postDelayed(hudTick, 700);
        if (announce) toast("HUD: chạm để thu gọn · giữ để ẩn");
    }

    private void removeHud(boolean announce) {
        handler.removeCallbacks(hudTick);
        handler.removeCallbacks(hudLongPress);
        Side.setHudOn(this, false);
        paintToggle("#hud", false, true);
        final TextView v = hudView;
        if (v == null) {
            hudParams = null;
            if (announce) toast("Đã ẩn HUD hiệu năng");
            return;
        }
        hudHiding = true;
        v.animate().cancel();
        v.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        if (hudView == v) {
                            hudView = null;
                            hudParams = null;
                            hudHiding = false;
                        }
                        try {
                            wm.removeView(v);
                        } catch (Exception ignored) {
                        }
                    }
                }).start();
        if (announce) toast("Đã ẩn HUD hiệu năng");
    }

    /** Removes the window but keeps the "HUD on" preference for a service restart. */
    private void detachHud() {
        handler.removeCallbacks(hudTick);
        handler.removeCallbacks(hudLongPress);
        hudHiding = false;
        if (hudView != null) {
            hudView.animate().cancel();
            try {
                wm.removeView(hudView);
            } catch (Exception ignored) {
            }
            hudView = null;
        }
        hudParams = null;
    }

    private void applyHudStyle() {
        if (hudView == null) return;
        if (hudCompact) {
            hudView.setTextSize(10f);
            hudView.setPadding(dp(8), dp(5), dp(8), dp(5));
        } else {
            hudView.setTextSize(10.5f);
            hudView.setPadding(dp(10), dp(6), dp(10), dp(6));
        }
    }

    private void updateHud() {
        if (hudView == null || hudHiding) return;
        int cpu = Stats.cpu();
        int fps = Stats.fps(this);
        String ram = Stats.ram(this);
        int temp = Stats.batteryTempC(this);
        String c = cpu < 0 ? "--" : String.valueOf(cpu);
        String t = temp < 0 ? "--" : String.valueOf(temp);
        if (hudCompact) {
            hudView.setText(c + "% \u2022 " + fps + " \u2022 " + ram
                    + " \u2022 " + t + "\u00b0C");
        } else {
            hudView.setText("CPU " + c + "%  FPS " + fps + "  RAM " + ram
                    + "  " + t + "\u00b0C");
        }
        if (temp >= 42) hudView.setTextColor(0xFFFF5A5A);
        else if (temp >= 40) hudView.setTextColor(0xFFFFB05A);
        else hudView.setTextColor(Ui.TEXT);
        clampHudPosition();
    }

    private void clampHudPosition() {
        if (hudView == null || hudParams == null) return;
        int w = hudView.getWidth();
        int h = hudView.getHeight();
        if (w <= 0 || h <= 0) return;
        hudParams.x = clamp(hudParams.x, 0, Math.max(0, screenW() - w));
        hudParams.y = clamp(hudParams.y, 0, Math.max(0, screenH() - h));
        try {
            wm.updateViewLayout(hudView, hudParams);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ editor

    private void showEditor() {
        if (tabViews.containsKey(TAB_EDITOR)) return;
        buildingTab = TAB_EDITOR;
        LinearLayout card = buildEditor();
        buildingTab = null;
        addTab(TAB_EDITOR, card);
    }

    private LinearLayout buildEditor() {
        LinearLayout wrap = glassCard();
        wrap.setPadding(dp(14), dp(12), dp(14), dp(10));

        wrap.addView(Ui.text(this, "Sửa thanh bên", 14, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Chạm để bật/tắt", 11, Ui.MUTED, false);
        hint.setLayoutParams(Ui.lp());
        Ui.margin(this, hint, 0, 3, 0, 6);
        wrap.addView(hint);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = Ui.col(this);

        List<String> ids = Side.items(this);
        for (int i = 0; i < Side.BUILTIN.length; i++) {
            list.addView(editorRow(Side.BUILTIN[i], Side.LABELS[i]));
        }
        for (String id : ids) {
            if (id.startsWith("app:")) {
                list.addView(editorRow(id, Apps.labelOf(this, id.substring(4))));
            }
        }

        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        TextView add = Ui.text(this, "+ Thêm ứng dụng", 13, Ui.ACCENT, false);
        add.setPadding(0, dp(12), 0, dp(8));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
                open(AddShortcutActivity.class);
            }
        });
        wrap.addView(add);
        Ui.press(add, 0.95f);

        TextView done = Ui.text(this, "Xong", 13, 0xFF0B1020, true);
        done.setGravity(Gravity.CENTER);
        done.setBackground(Ui.bg(Ui.ACCENT, this, 14));
        done.setPadding(0, dp(10), 0, dp(10));
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeTab(TAB_EDITOR);
            }
        });
        wrap.addView(done);
        Ui.press(done, 0.97f);
        return wrap;
    }

    /** A toggle row that updates itself in place, no rebuild. */
    private View editorRow(final String id, String label) {
        final boolean on = Side.has(this, id);

        LinearLayout row = Ui.row(this);
        row.setPadding(dp(2), dp(10), dp(2), dp(10));

        final TextView name = Ui.text(this, label, 13, on ? Ui.TEXT : Ui.MUTED, false);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView mark = Ui.text(this, on ? "✓" : "○", 14,
                on ? Ui.ACCENT : 0xFF6A7382, true);
        row.addView(mark);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Side.toggle(SidebarService.this, id);
                boolean now = Side.has(SidebarService.this, id);
                mark.setText(now ? "✓" : "○");
                mark.setTextColor(now ? Ui.ACCENT : 0xFF6A7382);
                name.setTextColor(now ? Ui.TEXT : Ui.MUTED);
                mark.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90)
                        .withEndAction(new Runnable() {
                            public void run() {
                                mark.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                            }
                        }).start();
            }
        });
        Ui.press(row, 0.98f);
        return row;
    }

    // ------------------------------------------------------------ state

    /** Yellow when on, default when off. Animates tint, background and a small pop. */
    private void paintTorch(boolean on, boolean animate) {
        if (torchIcon == null || torchBox == null) return;
        final int iconTo = on ? 0xFFFFD166 : Ui.ACCENT;
        final int boxTo = on ? 0x33FFD166 : ITEM_BG_SOFT;

        if (!animate) {
            iconTint = iconTo;
            boxTint = boxTo;
            torchIcon.setColorFilter(iconTo);
            setBoxColor(torchBox, boxTo);
            return;
        }

        final int iconFrom = iconTint;
        final int boxFrom = boxTint;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(180);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                float t = va.getAnimatedFraction();
                iconTint = blend(iconFrom, iconTo, t);
                boxTint = blend(boxFrom, boxTo, t);
                if (torchIcon != null) torchIcon.setColorFilter(iconTint);
                if (torchBox != null) setBoxColor(torchBox, boxTint);
            }
        });
        a.start();

        torchBox.animate().scaleX(1.10f).scaleY(1.10f).setDuration(90)
                .withEndAction(new Runnable() {
                    public void run() {
                        if (torchBox != null) {
                            torchBox.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                        }
                    }
                }).start();
    }

    private static int blend(int from, int to, float t) {
        return (Integer) new ArgbEvaluator().evaluate(t, from, to);
    }

    private void setBoxColor(View v, int color) {
        if (v == null) return;
        Drawable d = v.getBackground();
        if (d instanceof GradientDrawable) ((GradientDrawable) d).setColor(color);
    }

    // ------------------------------------------------------------ widgets

    private View iconButton(int iconRes, String label, View.OnClickListener click, int bgColor) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Ui.ACCENT);
        return iconButton(iv, label, click, bgColor);
    }

    private View iconButton(Drawable icon, String label, View.OnClickListener click, int bgColor) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        return iconButton(iv, label, click, bgColor);
    }

    private View iconButton(ImageView iv, String label, View.OnClickListener click, int bgColor) {
        FrameLayout box = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), Ui.FRAME);
        box.setBackground(g);
        box.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        box.setContentDescription(label);
        box.setOnClickListener(click);
        box.setClickable(true);
        addPressFeedback(box);
        return box;
    }

    /** Subtle press-in/press-out scale; does not consume the event. */
    private void addPressFeedback(final View v) {
        addPressFeedback(v, 0.90f);
    }

    private void addPressFeedback(final View v, final float scale) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().cancel();
                        view.animate().scaleX(scale).scaleY(scale)
                                .setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140)
                                .setInterpolator(new DecelerateInterpolator(2f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** Frozen screenshot + zoomed loupe that samples the pixel under the finger. */
    private class ColorPickView extends View {
        private final Paint shotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF src = new RectF();
        private final RectF dst = new RectF();
        private final Rect srcI = new Rect();

        private float x, y;
        private float downX, downY;
        private boolean dragged;
        private boolean primed;
        private int color;

        Runnable onColor;
        Runnable onTap;

        ColorPickView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(Ui.ACCENT);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeWidth(Math.max(1, dp(1)));
            cross.setColor(0xAAFFFFFF);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xFFFFFFFF);
        }

        String hex() {
            return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
        }

        private void sample() {
            if (colorBitmap == null) return;
            int w = getWidth() > 0 ? getWidth() : 1;
            int h = getHeight() > 0 ? getHeight() : 1;
            int bw = colorBitmap.getWidth();
            int bh = colorBitmap.getHeight();
            int bx = clamp((int) (x * bw / (float) w), 0, bw - 1);
            int by = clamp((int) (y * bh / (float) h), 0, bh - 1);
            try {
                color = colorBitmap.getPixel(bx, by);
            } catch (Exception e) {
                return;
            }
            if (onColor != null) onColor.run();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (colorBitmap == null) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (!primed) {
                x = w / 2f;
                y = h / 2f;
                primed = true;
                sample();
            }

            canvas.drawBitmap(colorBitmap, null, new RectF(0, 0, w, h), shotPaint);

            // crosshair on the sampled point
            float gap = dp(13);
            float arm = dp(26);
            canvas.drawLine(x - arm, y, x - gap, y, cross);
            canvas.drawLine(x + gap, y, x + arm, y, cross);
            canvas.drawLine(x, y - arm, x, y - gap, cross);
            canvas.drawLine(x, y + gap, x, y + arm, cross);
            canvas.drawCircle(x, y, gap, cross);

            // loupe above the finger, flipped below when it would leave the screen
            float mr = dp(46);
            float mx = Math.max(mr + dp(10), Math.min(w - mr - dp(10), x));
            float my = y - dp(96);
            if (my - mr < dp(96)) {
                my = Math.min(h - mr - dp(12), y + dp(96));
            }
            if (my - mr < 0) my = mr + dp(10);

            float zoom = 7f;
            float scale = colorBitmap.getWidth() / (float) w;
            float half = mr * scale / zoom;
            src.set(x * scale - half, y * scale - half, x * scale + half, y * scale + half);
            if (src.left < 0) src.offset(-src.left, 0);
            if (src.top < 0) src.offset(0, -src.top);
            if (src.right > colorBitmap.getWidth()) {
                src.offset(colorBitmap.getWidth() - src.right, 0);
            }
            if (src.bottom > colorBitmap.getHeight()) {
                src.offset(0, colorBitmap.getHeight() - src.bottom);
            }

            clipPath.reset();
            clipPath.addCircle(mx, my, mr, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            dst.set(mx - mr, my - mr, mx + mr, my + mr);
            src.roundOut(srcI);
            canvas.drawBitmap(colorBitmap, srcI, dst, shotPaint);
            canvas.restore();
            canvas.drawCircle(mx, my, mr, ring);

            // centre marker of the loupe
            canvas.drawCircle(mx, my, dp(3.5f), dot);
            ring.setColor(0x66000000);
            canvas.drawCircle(mx, my, mr + dp(1.5f), ring);
            ring.setColor(Ui.ACCENT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragged = false;
                    x = downX;
                    y = downY;
                    sample();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(e.getX() - downX) > slop
                            || Math.abs(e.getY() - downY) > slop) {
                        dragged = true;
                    }
                    x = e.getX();
                    y = e.getY();
                    sample();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged && onTap != null) onTap.run();
                    return true;
                default:
                    return true;
            }
        }
    }


    // ------------------------------------------------------------ tally tab

    private void openTally() {
        if (tabViews.containsKey(TAB_TALLY)) return;
        buildingTab = TAB_TALLY;
        LinearLayout card = buildTallyPanel();
        buildingTab = null;
        addTab(TAB_TALLY, card);
    }

    private LinearLayout buildTallyPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Bộ đếm & ngẫu nhiên", false, null));

        final TextView count = Ui.text(this, String.valueOf(Side.tally(this)),
                44, Ui.ACCENT, true);
        count.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        card.addView(count, cp);

        TextView unit = Ui.text(this, "lượt", 11, Ui.MUTED, false);
        unit.setGravity(Gravity.CENTER);
        card.addView(unit);

        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.CENTER);
        TextView minus = pill("\u22121", false);
        TextView reset = pill("Đặt lại", false);
        TextView plus = pill("+1", true);
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Side.setTally(SidebarService.this, Side.tally(SidebarService.this) - 1);
                refreshTally(count);
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Side.setTally(SidebarService.this, 0);
                refreshTally(count);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Side.setTally(SidebarService.this, Side.tally(SidebarService.this) + 1);
                refreshTally(count);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        buttons.addView(minus, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(reset, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(plus, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(buttons, bp);

        card.addView(label("NGẪU NHIÊN"));

        final TextView result = Ui.text(this, "\u2014", 30, Ui.TEXT, true);
        result.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(10);
        card.addView(result, rp);

        LinearLayout randomRow = Ui.row(this);
        randomRow.setGravity(Gravity.CENTER);
        TextView dice = pill("Xúc xắc", false);
        TextView coin = pill("Đồng xu", false);
        TextView number = pill("1\u2013100", false);
        dice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                randomResult(result, String.valueOf(1 + (int) (Math.random() * 6)));
            }
        });
        coin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                randomResult(result, Math.random() < 0.5 ? "Sấp" : "Ngửa");
            }
        });
        number.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                randomResult(result, String.valueOf(1 + (int) (Math.random() * 100)));
            }
        });
        LinearLayout.LayoutParams rr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rr.topMargin = dp(10);
        randomRow.addView(dice, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        randomRow.addView(coin, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        randomRow.addView(number, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(randomRow, rr);

        TextView note = Ui.text(this,
                "Bộ đếm được lưu lại, tắt app vẫn còn.", 11, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        card.addView(note, np);
        return card;
    }

    private void refreshTally(TextView count) {
        count.setText(String.valueOf(Side.tally(this)));
        Ui.pop(count);
    }

    private void randomResult(TextView view, String text) {
        view.setText(text);
        Ui.pop(view);
    }

    // ------------------------------------------------------------ clipboard tab

    private void openClip() {
        if (tabViews.containsKey(TAB_CLIP)) return;
        String clip = readClipboard();
        if (clip != null) Snippets.addHistory(this, clip);
        buildingTab = TAB_CLIP;
        LinearLayout card = buildClipPanel();
        buildingTab = null;
        addTab(TAB_CLIP, card);
        setDockFocusable(true);
    }

    private LinearLayout buildClipPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.addView(header("Clipboard & đoạn mẫu", false, null));

        card.addView(label("ĐOẠN MẪU ĐÃ GHIM"));
        clipPinnedList = Ui.col(this);
        card.addView(clipPinnedList);

        final EditText input = new EditText(this);
        input.setBackground(Ui.framed(0x33000000, this, 12));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xB3AAB3C4);
        input.setHint("Câu lệnh, mã màu, ký tự hay dùng...");
        input.setTextSize(13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(2);
        input.setMaxLines(3);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout add = Ui.row(this);
        add.addView(input, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = pill("Ghim", true);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    toast("Chưa có nội dung");
                    return;
                }
                Snippets.addPinned(SidebarService.this, text);
                input.setText("");
                renderClipLists();
                toast("Đã ghim");
            }
        });
        LinearLayout.LayoutParams saveLp = Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.leftMargin = dp(8);
        add.addView(save, saveLp);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = dp(8);
        card.addView(add, addLp);

        card.addView(label("CLIPBOARD GẦN ĐÂY"));
        clipHistoryList = Ui.col(this);
        card.addView(clipHistoryList);

        TextView refresh = pill("Đọc clipboard ngay", false);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String clip = readClipboard();
                if (clip == null) {
                    toast("Không đọc được clipboard — mở app Essentials rồi thử lại");
                    return;
                }
                Snippets.addHistory(SidebarService.this, clip);
                renderClipLists();
                toast("Đã thêm đoạn vừa copy");
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        card.addView(refresh, rp);

        clipStatus = Ui.text(this, "", 11, Ui.MUTED, false);
        clipStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        card.addView(clipStatus, sp);

        renderClipLists();
        return card;
    }

    private void renderClipLists() {
        if (clipPinnedList != null) {
            clipPinnedList.removeAllViews();
            List<String> pinned = Snippets.pinned(this);
            if (pinned.isEmpty()) {
                clipPinnedList.addView(emptyRow("Chưa ghim đoạn nào"));
            } else {
                for (String text : pinned) clipPinnedList.addView(clipRow(text, true));
            }
        }
        if (clipHistoryList != null) {
            clipHistoryList.removeAllViews();
            List<String> history = Snippets.history(this);
            if (history.isEmpty()) {
                clipHistoryList.addView(emptyRow("Chưa có đoạn nào"));
            } else {
                for (String text : history) clipHistoryList.addView(clipRow(text, false));
            }
        }
        if (clipStatus != null) {
            clipStatus.setText("Chạm một dòng để chép vào bộ nhớ đệm.");
        }
    }

    private View emptyRow(String text) {
        TextView empty = Ui.text(this, text, 12, Ui.MUTED, false);
        empty.setPadding(dp(6), dp(8), dp(6), dp(8));
        return empty;
    }

    private View clipRow(final String text, final boolean pinned) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.framed(ITEM_BG_SOFT, this, 12));
        row.setPadding(dp(10), dp(9), dp(2), dp(9));

        TextView body = Ui.text(this, text.replace('\n', ' '), 12, Ui.TEXT, false);
        body.setSingleLine(true);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(body, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = Ui.text(this, "\u00d7", 17, Ui.MUTED, false);
        del.setPadding(dp(10), 0, dp(9), 0);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pinned) Snippets.removePinned(SidebarService.this, text);
                else Snippets.removeHistory(SidebarService.this, text);
                renderClipLists();
            }
        });
        row.addView(del);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyToClipboard(text);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        Ui.press(row, 0.98f);
        return row;
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException();
            cm.setPrimaryClip(ClipData.newPlainText("Essentials", text));
            Snippets.addHistory(this, text);
            renderClipLists();
            toast("Đã chép");
        } catch (Exception e) {
            toast("Không chép được");
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return null;
            String value = text.toString().trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ QR tab

    private void openQr() {
        if (tabViews.containsKey(TAB_QR)) return;
        buildingTab = TAB_QR;
        LinearLayout card = buildQrPanel();
        buildingTab = null;
        addTab(TAB_QR, card);
        setDockFocusable(true);
    }

    private LinearLayout buildQrPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.addView(header("Mã QR offline", false, null));

        final EditText input = new EditText(this);
        input.setBackground(Ui.framed(0x33000000, this, 12));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xB3AAB3C4);
        input.setHint("Dán văn bản hoặc link...");
        input.setTextSize(13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(8);
        card.addView(input, ip);

        final ImageView image = new ImageView(this);
        // CENTER keeps the modules on exact pixel boundaries; FIT_CENTER would
        // blur large symbols when it scales them up to the view.
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setBackground(Ui.bg(0xFFFFFFFF, this, 12));
        image.setPadding(dp(6), dp(6), dp(6), dp(6));
        int side = dp(228);
        LinearLayout.LayoutParams imLp = new LinearLayout.LayoutParams(side, side);
        imLp.gravity = Gravity.CENTER_HORIZONTAL;
        imLp.topMargin = dp(10);
        card.addView(image, imLp);

        final TextView status = Ui.text(this,
                "Nhập nội dung rồi bấm Tạo mã (tối đa " + QrCode.maxBytes() + " byte)",
                11, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(10);
        card.addView(status, stp);

        LinearLayout buttons = Ui.row(this);
        TextView paste = pill("Dán clipboard", false);
        TextView make = pill("Tạo mã", true);
        paste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String clip = readClipboard();
                if (clip == null) {
                    toast("Không đọc được clipboard — mở app Essentials rồi thử lại");
                    return;
                }
                input.setText(clip);
            }
        });
        make.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                renderQr(image, status, input.getText().toString());
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(10);
        buttons.addView(paste, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mk = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mk.leftMargin = dp(8);
        buttons.addView(make, mk);
        card.addView(buttons, bp);
        return card;
    }

    private void renderQr(final ImageView view, final TextView status, String text) {
        final String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            toast("Chưa có nội dung");
            return;
        }
        if (!QrCode.fits(value)) {
            status.setText("Quá dài: tối đa " + QrCode.maxBytes() + " byte");
            status.setTextColor(0xFFFF7A7A);
            return;
        }
        status.setTextColor(Ui.MUTED);
        status.setText("Đang tạo mã QR...");
        // Large symbols need eight mask trials; build them off the UI thread so
        // the dock does not freeze for a second on the biggest payloads.
        new Thread(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                int n = 0;
                int bytes = 0;
                try {
                    bytes = value.getBytes("UTF-8").length;
                    boolean[][] modules = QrCode.encode(value);
                    n = modules.length;
                    int quiet = 4;
                    int target = dp(216);
                    int scale = Math.max(1, target / (n + quiet * 2));
                    int px = (n + quiet * 2) * scale;
                    bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(0xFFFFFFFF);
                    Paint paint = new Paint();
                    paint.setColor(0xFF000000);
                    for (int y = 0; y < n; y++) {
                        for (int x = 0; x < n; x++) {
                            if (modules[y][x]) {
                                int left = (x + quiet) * scale;
                                int top = (y + quiet) * scale;
                                canvas.drawRect(left, top, left + scale, top + scale, paint);
                            }
                        }
                    }
                } catch (Throwable t) {
                    bmp = null;
                }
                final Bitmap image = bmp;
                final int size = n;
                final int count = bytes;
                handler.post(new Runnable() {
                    public void run() {
                        if (image == null) {
                            status.setTextColor(0xFFFF7A7A);
                            status.setText("Không tạo được mã QR");
                            return;
                        }
                        view.setImageBitmap(image);
                        status.setTextColor(Ui.MUTED);
                        status.setText("QR " + size + "×" + size + " · "
                                + count + " byte");
                    }
                });
            }
        }, "qr-encode").start();
    }

    // ------------------------------------------------------------ shared bits

    private TextView pill(String label, boolean primary) {
        TextView t = Ui.text(this, label, 13, primary ? 0xFF0B1020 : Ui.TEXT, primary);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bg(primary ? Ui.ACCENT : ITEM_BG_SOFT, this, 14));
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        Ui.press(t, 0.94f);
        return t;
    }

    private TextView label(String text) {
        TextView t = Ui.text(this, text, 10, Ui.MUTED, true);
        t.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        p.bottomMargin = dp(2);
        t.setLayoutParams(p);
        return t;
    }


    // ------------------------------------------------------------ crosshair

    private CrosshairView crosshairView;
    private WindowManager.LayoutParams crosshairParams;

    private void openCrosshair() {
        if (tabViews.containsKey(TAB_CROSSHAIR)) return;
        buildingTab = TAB_CROSSHAIR;
        LinearLayout card = buildCrosshairPanel();
        buildingTab = null;
        addTab(TAB_CROSSHAIR, card);
    }

    private LinearLayout buildCrosshairPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(header("Tâm ngắm ảo", false, null));

        final TextView status = Ui.text(this, "", 11, Ui.MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(2);
        sp.bottomMargin = dp(8);
        card.addView(status, sp);

        final TextView toggle = pill("", true);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (crosshairView == null) addCrosshair(true);
                else removeCrosshair(true);
                paintCrosshairStatus(status, toggle);
            }
        });
        card.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintCrosshairStatus(status, toggle);

        card.addView(label("KIỂU TÂM"));

        final String[] names = {"Chấm", "Chữ thập", "Tròn"};
        final TextView[] stylePills = new TextView[names.length];
        LinearLayout styles = Ui.row(this);
        for (int i = 0; i < names.length; i++) {
            final int style = i;
            final TextView p = pill(names[i], false);
            p.setPadding(dp(6), dp(9), dp(6), dp(9));
            p.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Crosshair.setStyle(SidebarService.this, style);
                    for (int k = 0; k < stylePills.length; k++) {
                        setPillState(stylePills[k], k == style);
                    }
                    updateCrosshair();
                }
            });
            stylePills[i] = p;
            LinearLayout.LayoutParams pp = Ui.lp(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) pp.leftMargin = dp(6);
            styles.addView(p, pp);
        }
        card.addView(styles);
        for (int i = 0; i < stylePills.length; i++) {
            setPillState(stylePills[i], Crosshair.style(this) == i);
        }

        card.addView(label("MÀU"));

        final GradientDrawable[] swatchBg = new GradientDrawable[Crosshair.COLORS.length];
        LinearLayout colorRow1 = Ui.row(this);
        LinearLayout colorRow2 = Ui.row(this);
        for (int i = 0; i < Crosshair.COLORS.length; i++) {
            final int color = Crosshair.COLORS[i];
            View swatch = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            g.setStroke(Math.max(1, dp(1)), Ui.FRAME);
            swatch.setBackground(g);
            swatchBg[i] = g;
            swatch.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Crosshair.setColor(SidebarService.this, color);
                    updateCrosshair();
                    for (int k = 0; k < swatchBg.length; k++) {
                        boolean on = Crosshair.COLORS[k] == color;
                        swatchBg[k].setStroke(Math.max(1, dp(on ? 2 : 1)),
                                on ? 0xFFFFFFFF : Ui.FRAME);
                    }
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(26), dp(26));
            if (i % 4 > 0) cp.leftMargin = dp(7);
            (i < 4 ? colorRow1 : colorRow2).addView(swatch, cp);
        }
        card.addView(colorRow1);
        LinearLayout.LayoutParams cr2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cr2.topMargin = dp(7);
        card.addView(colorRow2, cr2);
        for (int i = 0; i < swatchBg.length; i++) {
            boolean on = Crosshair.COLORS[i] == Crosshair.color(this);
            swatchBg[i].setStroke(Math.max(1, dp(on ? 2 : 1)),
                    on ? 0xFFFFFFFF : Ui.FRAME);
        }

        card.addView(label("KÍCH THƯỚC"));
        final TextView sizeValue = Ui.text(this, Crosshair.sizeDp(this) + "dp",
                11, Ui.ACCENT, true);
        sizeValue.setGravity(Gravity.END);
        card.addView(sizeValue);
        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(42);
        sizeBar.setProgress(Crosshair.sizeDp(this) - 6);
        tintSeek(sizeBar);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int size = progress + 6;
                Crosshair.setSizeDp(SidebarService.this, size);
                sizeValue.setText(size + "dp");
                updateCrosshair();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        card.addView(sizeBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        card.addView(label("ĐỘ MỜ"));
        final TextView alphaValue = Ui.text(this, Crosshair.alphaPercent(this) + "%",
                11, Ui.ACCENT, true);
        alphaValue.setGravity(Gravity.END);
        card.addView(alphaValue);
        SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(80);
        alphaBar.setProgress(Crosshair.alphaPercent(this) - 20);
        tintSeek(alphaBar);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int percent = progress + 20;
                Crosshair.setAlphaPercent(SidebarService.this, percent);
                alphaValue.setText(percent + "%");
                updateCrosshair();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        card.addView(alphaBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        card.addView(label("TINH CHỈNH VỊ TRÍ"));
        final TextView[] offsets = new TextView[2];
        offsets[0] = offsetRow(card, "Lệch X", true);
        offsets[1] = offsetRow(card, "Lệch Y", false);

        TextView center = pill("Đưa về chính giữa", false);
        center.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Crosshair.setOffset(SidebarService.this, 0, 0);
                offsets[0].setText("0dp");
                offsets[1].setText("0dp");
                updateCrosshair();
            }
        });
        LinearLayout.LayoutParams ctr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctr.topMargin = dp(10);
        card.addView(center, ctr);

        TextView note = Ui.text(this,
                "Tâm ngắm nằm trên mọi ứng dụng và không chặn thao tác chạm.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private void paintCrosshairStatus(TextView status, TextView toggle) {
        boolean on = crosshairView != null;
        status.setText(on
                ? "Đang hiển thị · thay đổi bên dưới được áp dụng ngay"
                : "Đang tắt · bật để xem trước và chỉnh trực tiếp");
        toggle.setText(on ? "Đang bật · chạm để tắt" : "Đang tắt · chạm để bật");
        setPillState(toggle, on);
    }

    private void setPillState(TextView t, boolean active) {
        t.setBackground(Ui.bg(active ? Ui.ACCENT : ITEM_BG_SOFT, this, 14));
        t.setTextColor(active ? 0xFF0B1020 : Ui.TEXT);
    }

    private void tintSeek(SeekBar bar) {
        try {
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        } catch (Exception ignored) {
        }
    }

    private TextView offsetRow(LinearLayout card, String name, final boolean xAxis) {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.text(this, name, 12, Ui.TEXT, false);
        row.addView(label, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = Ui.text(this,
                (xAxis ? Crosshair.offsetX(this) : Crosshair.offsetY(this)) + "dp",
                11, Ui.ACCENT, true);
        row.addView(value);

        TextView minus = pill("\u2212", false);
        minus.setPadding(dp(13), dp(6), dp(13), dp(6));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stepOffset(xAxis, -2, value);
            }
        });
        LinearLayout.LayoutParams ml = Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = dp(10);
        row.addView(minus, ml);

        TextView plus = pill("+", false);
        plus.setPadding(dp(13), dp(6), dp(13), dp(6));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stepOffset(xAxis, 2, value);
            }
        });
        LinearLayout.LayoutParams pl = Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = dp(6);
        row.addView(plus, pl);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        card.addView(row, rp);
        return value;
    }

    private void stepOffset(boolean xAxis, int delta, TextView value) {
        int x = Crosshair.offsetX(this);
        int y = Crosshair.offsetY(this);
        if (xAxis) x += delta;
        else y += delta;
        x = clamp(x, -400, 400);
        y = clamp(y, -400, 400);
        Crosshair.setOffset(this, x, y);
        value.setText((xAxis ? x : y) + "dp");
        updateCrosshair();
    }

    private void addCrosshair(boolean announce) {
        if (crosshairView != null) {
            updateCrosshair();
            return;
        }
        CrosshairView v = new CrosshairView(this);
        v.setStyle(Crosshair.style(this));
        v.setColor(Crosshair.color(this));
        v.setSizeDp(Crosshair.sizeDp(this));
        v.setAlpha(Math.max(0.05f, Math.min(1f, Crosshair.alphaPercent(this) / 100f)));
        int size = v.windowSizePx();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size, TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        crosshairView = v;
        crosshairParams = lp;
        positionCrosshair();
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            crosshairView = null;
            crosshairParams = null;
            Crosshair.setEnabled(this, false);
            toast("Không bật được tâm ngắm");
            return;
        }
        Crosshair.setEnabled(this, true);
        paintToggle("#crosshair", true, true);
        if (announce) toast("Đã bật tâm ngắm · mở tab để tinh chỉnh");
    }

    private void updateCrosshair() {
        if (crosshairView == null || crosshairParams == null) return;
        crosshairView.setStyle(Crosshair.style(this));
        crosshairView.setColor(Crosshair.color(this));
        crosshairView.setSizeDp(Crosshair.sizeDp(this));
        crosshairView.setAlpha(Math.max(0.05f, Math.min(1f,
                Crosshair.alphaPercent(this) / 100f)));
        int size = crosshairView.windowSizePx();
        crosshairParams.width = size;
        crosshairParams.height = size;
        positionCrosshair();
        try {
            wm.updateViewLayout(crosshairView, crosshairParams);
        } catch (Exception ignored) {
        }
    }

    private void positionCrosshair() {
        if (crosshairView == null || crosshairParams == null) return;
        int size = crosshairParams.width;
        int x = (screenW() - size) / 2 + dp(Crosshair.offsetX(this));
        int y = (screenH() - size) / 2 + dp(Crosshair.offsetY(this));
        crosshairParams.x = clamp(x, -size / 2,
                Math.max(-size / 2, screenW() - size / 2));
        crosshairParams.y = clamp(y, -size / 2,
                Math.max(-size / 2, screenH() - size / 2));
    }

    private void removeCrosshair(boolean announce) {
        detachCrosshair();
        Crosshair.setEnabled(this, false);
        paintToggle("#crosshair", false, true);
        if (announce) toast("Đã tắt tâm ngắm");
    }

    private void detachCrosshair() {
        if (crosshairView != null) {
            try {
                wm.removeView(crosshairView);
            } catch (Exception ignored) {
            }
            crosshairView = null;
        }
        crosshairParams = null;
    }

    // ------------------------------------------------------------ multi timer

    private static final int TIMER_SLOTS = 4;
    private final long[] timerRemain = new long[TIMER_SLOTS];
    private final long[] timerEnd = new long[TIMER_SLOTS];
    private final long[] timerTotal = new long[TIMER_SLOTS];
    private final boolean[] timerRunning = new boolean[TIMER_SLOTS];
    private final TextView[] timerLabels = new TextView[TIMER_SLOTS];
    private final TextView[] timerButtons = new TextView[TIMER_SLOTS];
    private boolean timersLoaded;
    private ToneGenerator tone;

    private void openTimers() {
        if (tabViews.containsKey(TAB_TIMERS)) return;
        loadTimers();
        buildingTab = TAB_TIMERS;
        LinearLayout card = buildTimersPanel();
        buildingTab = null;
        addTab(TAB_TIMERS, card);
    }

    private void loadTimers() {
        if (timersLoaded) return;
        for (int i = 0; i < TIMER_SLOTS; i++) {
            timerTotal[i] = Side.timerMinutes(this, i) * 60000L;
            timerRemain[i] = timerTotal[i];
            timerRunning[i] = false;
            timerEnd[i] = 0L;
        }
        timersLoaded = true;
    }

    private LinearLayout buildTimersPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Đa hẹn giờ", false, null));

        TextView hint = Ui.text(this,
                "4 đồng hồ chạy song song · báo tiếng khi hết giờ",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(4);
        card.addView(hint, hp);

        for (int i = 0; i < TIMER_SLOTS; i++) {
            card.addView(timerRow(i), timerRowParams(i));
        }

        TextView resetAll = pill("Đặt lại tất cả", false);
        resetAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < TIMER_SLOTS; i++) {
                    timerRunning[i] = false;
                    timerTotal[i] = Side.timerMinutes(SidebarService.this, i) * 60000L;
                    timerRemain[i] = timerTotal[i];
                }
                handler.removeCallbacks(timerTick);
                updateTimerLabels();
                toast("Đã đặt lại 4 đồng hồ");
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        card.addView(resetAll, rp);
        return card;
    }

    private LinearLayout.LayoutParams timerRowParams(int index) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) p.topMargin = dp(8);
        return p;
    }

    private View timerRow(final int index) {
        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.framed(ITEM_BG_SOFT, this, 14));
        box.setPadding(dp(10), dp(8), dp(10), dp(9));

        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, "Hẹn " + (index + 1), 12, Ui.MUTED, true);
        top.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = Ui.text(this, formatTimer(timerRemain[index]),
                20, Ui.ACCENT, true);
        time.setTypeface(android.graphics.Typeface.MONOSPACE);
        top.addView(time);
        box.addView(top);

        LinearLayout actions = Ui.row(this);
        TextView minus = pill("\u22121p", false);
        minus.setPadding(dp(9), dp(6), dp(9), dp(6));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                adjustTimer(index, -1);
            }
        });
        TextView plus = pill("+1p", false);
        plus.setPadding(dp(9), dp(6), dp(9), dp(6));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                adjustTimer(index, 1);
            }
        });
        final TextView start = pill(timerRunning[index] ? "Tạm dừng" : "Bắt đầu", true);
        start.setPadding(dp(8), dp(6), dp(8), dp(6));
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleTimer(index);
            }
        });
        TextView reset = pill("\u21ba", false);
        reset.setPadding(dp(11), dp(6), dp(11), dp(6));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                timerRunning[index] = false;
                timerTotal[index] = Side.timerMinutes(SidebarService.this, index) * 60000L;
                timerRemain[index] = timerTotal[index];
                updateTimerLabels();
                stopTimerTickIfIdle();
            }
        });

        actions.addView(minus);
        LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(5);
        actions.addView(plus, lp);
        LinearLayout.LayoutParams sp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        sp.leftMargin = dp(5);
        actions.addView(start, sp);
        LinearLayout.LayoutParams rp = Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(5);
        actions.addView(reset, rp);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(7);
        box.addView(actions, ap);

        timerLabels[index] = time;
        timerButtons[index] = start;
        return box;
    }

    private String formatTimer(long ms) {
        long total = Math.max(0, (ms + 999) / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long sec = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private void toggleTimer(int index) {
        loadTimers();
        long now = SystemClock.elapsedRealtime();
        if (timerRunning[index]) {
            timerRemain[index] = Math.max(0, timerEnd[index] - now);
            timerRunning[index] = false;
            stopTimerTickIfIdle();
        } else {
            if (timerRemain[index] <= 0) timerRemain[index] = timerTotal[index];
            if (timerRemain[index] <= 0) timerRemain[index] = 60000L;
            timerEnd[index] = now + timerRemain[index];
            timerRunning[index] = true;
            handler.removeCallbacks(timerTick);
            handler.post(timerTick);
        }
        updateTimerLabels();
        Ui.pop(timerButtons[index]);
    }

    private void adjustTimer(int index, int deltaMinutes) {
        loadTimers();
        long delta = deltaMinutes * 60000L;
        if (timerRunning[index]) {
            long now = SystemClock.elapsedRealtime();
            timerEnd[index] = Math.max(now + 1000L, timerEnd[index] + delta);
            timerRemain[index] = Math.max(0, timerEnd[index] - now);
        } else {
            timerRemain[index] = Math.max(1000L, timerRemain[index] + delta);
        }
        timerTotal[index] = Math.max(60000L, timerRemain[index]);
        int minutes = (int) Math.max(1, Math.round(timerTotal[index] / 60000d));
        Side.setTimerMinutes(this, index, minutes);
        updateTimerLabels();
    }

    private void updateTimerLabels() {
        for (int i = 0; i < TIMER_SLOTS; i++) {
            if (timerLabels[i] != null) {
                timerLabels[i].setText(formatTimer(timerRemain[i]));
            }
            if (timerButtons[i] != null) {
                timerButtons[i].setText(timerRunning[i] ? "Tạm dừng" : "Bắt đầu");
                setPillState(timerButtons[i], !timerRunning[i]);
            }
        }
    }

    private final Runnable timerTick = new Runnable() {
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean any = false;
            for (int i = 0; i < TIMER_SLOTS; i++) {
                if (!timerRunning[i]) continue;
                long left = timerEnd[i] - now;
                if (left <= 0) {
                    left = 0;
                    timerRunning[i] = false;
                    beepTimer();
                    toast("Hết giờ · Hẹn " + (i + 1));
                }
                timerRemain[i] = left;
                any = true;
            }
            updateTimerLabels();
            if (any) handler.postDelayed(timerTick, 250);
        }
    };

    private void stopTimerTickIfIdle() {
        for (int i = 0; i < TIMER_SLOTS; i++) {
            if (timerRunning[i]) return;
        }
        handler.removeCallbacks(timerTick);
    }

    private void restartTimerTick() {
        for (int i = 0; i < TIMER_SLOTS; i++) {
            if (timerRunning[i]) {
                handler.removeCallbacks(timerTick);
                handler.post(timerTick);
                return;
            }
        }
    }

    private void beepTimer() {
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 260);
            handler.postDelayed(new Runnable() {
                public void run() {
                    try {
                        if (tone != null) {
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 260);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, 430);
        } catch (Exception ignored) {
        }
    }

    private void releaseTone() {
        if (tone != null) {
            try {
                tone.release();
            } catch (Exception ignored) {
            }
            tone = null;
        }
    }

    // ------------------------------------------------------------ system locks

    private SharedPreferences lockPrefs() {
        return getSharedPreferences("locks", MODE_PRIVATE);
    }

    private boolean requireWriteSettings() {
        if (Settings.System.canWrite(this)) return true;
        toast("Cấp quyền Sửa cài đặt hệ thống cho Essentials");
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Mở Cài đặt → Ứng dụng → Sửa cài đặt hệ thống");
        }
        return false;
    }

    private void toggleBrightnessLock() {
        if (!requireWriteSettings()) return;
        SharedPreferences sp = lockPrefs();
        try {
            android.content.ContentResolver cr = getContentResolver();
            boolean locked = sp.getBoolean("bright", false);
            if (locked) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        sp.getInt("bright_mode", Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                        sp.getInt("bright_value", 128));
                sp.edit().putBoolean("bright", false).apply();
                toast("Đã mở khoá độ sáng");
            } else {
                int mode = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                int value = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS, 128);
                sp.edit().putBoolean("bright", true)
                        .putInt("bright_mode", mode)
                        .putInt("bright_value", value).apply();
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value);
                toast("Đã khoá độ sáng " + Math.round(value * 100f / 255f) + "%");
            }
        } catch (Exception e) {
            toast("Hệ thống chặn đổi độ sáng");
        }
        paintToggle("#brightness", lockPrefs().getBoolean("bright", false), true);
    }

    private void toggleRotationLock() {
        if (!requireWriteSettings()) return;
        SharedPreferences sp = lockPrefs();
        try {
            android.content.ContentResolver cr = getContentResolver();
            boolean locked = sp.getBoolean("rotate", false);
            if (locked) {
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1);
                sp.edit().putBoolean("rotate", false).apply();
                toast("Đã mở khoá xoay màn hình");
            } else {
                int rotation = wm.getDefaultDisplay().getRotation();
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, rotation);
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
                sp.edit().putBoolean("rotate", true).apply();
                boolean landscape = rotation == android.view.Surface.ROTATION_90
                        || rotation == android.view.Surface.ROTATION_270;
                toast(landscape ? "Đã khoá hướng ngang" : "Đã khoá hướng dọc");
            }
        } catch (Exception e) {
            toast("Hệ thống chặn đổi hướng xoay");
        }
        paintToggle("#rotate", lockPrefs().getBoolean("rotate", false), true);
    }

    private void toggleDnd() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (!nm.isNotificationPolicyAccessGranted()) {
            toast("Cấp quyền Không làm phiền cho Essentials");
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            int filter = nm.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                toast("Không làm phiền: chỉ ưu tiên");
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                toast("Đã tắt Không làm phiền");
            }
        } catch (Exception e) {
            toast("Hệ thống chặn đổi Không làm phiền");
        }
        paintToggle("#dnd", dndOn(), true);
    }


    // ------------------------------------------------------------ auto clicker

    private boolean autoRunning;
    private float autoX = -1f;
    private float autoY = -1f;
    private int autoInterval = 220;
    private int autoJitter = 15;
    private View autoDot;
    private WindowManager.LayoutParams autoDotParams;
    private TextView autoStatusView;
    private TextView autoStartButton;
    private TextView autoIntervalValue;
    private TextView autoJitterValue;
    private final java.util.Random autoRandom = new java.util.Random();

    private SharedPreferences toolPrefs() {
        return getSharedPreferences("tools", MODE_PRIVATE);
    }

    private void openAutoClick() {
        if (tabViews.containsKey(TAB_AUTOCLICK)) return;
        SharedPreferences sp = toolPrefs();
        autoInterval = sp.getInt("auto_interval", autoInterval);
        autoJitter = sp.getInt("auto_jitter", autoJitter);
        autoX = sp.getFloat("auto_x", autoX);
        autoY = sp.getFloat("auto_y", autoY);
        buildingTab = TAB_AUTOCLICK;
        LinearLayout card = buildAutoPanel();
        buildingTab = null;
        addTab(TAB_AUTOCLICK, card);
    }

    private LinearLayout buildAutoPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Tự động chạm", false, null));

        TextView hint = Ui.text(this,
                "Một điểm chạm lặp lại theo chu kỳ, có độ trễ ngẫu nhiên nhẹ. "
                        + "Cần bật Trợ năng cho Essentials.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        autoStatusView = Ui.text(this, "", 12, Ui.ACCENT, true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = dp(8);
        card.addView(autoStatusView, sp);

        LinearLayout chooseRow = Ui.row(this);
        TextView choose = pill("Chọn điểm trên màn hình", true);
        choose.setPadding(dp(10), dp(10), dp(10), dp(10));
        choose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickAutoPoint();
            }
        });
        chooseRow.addView(choose, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(chooseRow);

        card.addView(label("CHU KỲ"));
        LinearLayout intervalRow = Ui.row(this);
        TextView minus = pill("\u2212", false);
        minus.setPadding(dp(13), dp(7), dp(13), dp(7));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoInterval = clamp(autoInterval - 50, 50, 3000);
                toolPrefs().edit().putInt("auto_interval", autoInterval).apply();
                paintAutoValues();
            }
        });
        autoIntervalValue = Ui.text(this, autoInterval + " ms", 14, Ui.TEXT, true);
        autoIntervalValue.setGravity(Gravity.CENTER);
        TextView plus = pill("+", false);
        plus.setPadding(dp(13), dp(7), dp(13), dp(7));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoInterval = clamp(autoInterval + 50, 50, 3000);
                toolPrefs().edit().putInt("auto_interval", autoInterval).apply();
                paintAutoValues();
            }
        });
        intervalRow.addView(minus);
        intervalRow.addView(autoIntervalValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        intervalRow.addView(plus);
        card.addView(intervalRow);

        card.addView(label("ĐỘ TRỄ NGẪU NHIÊN"));
        LinearLayout jitterRow = Ui.row(this);
        TextView jminus = pill("\u2212", false);
        jminus.setPadding(dp(13), dp(7), dp(13), dp(7));
        jminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoJitter = clamp(autoJitter - 5, 0, 50);
                toolPrefs().edit().putInt("auto_jitter", autoJitter).apply();
                paintAutoValues();
            }
        });
        autoJitterValue = Ui.text(this, "\u00b1" + autoJitter + "%", 14, Ui.TEXT, true);
        autoJitterValue.setGravity(Gravity.CENTER);
        TextView jplus = pill("+", false);
        jplus.setPadding(dp(13), dp(7), dp(13), dp(7));
        jplus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoJitter = clamp(autoJitter + 5, 0, 50);
                toolPrefs().edit().putInt("auto_jitter", autoJitter).apply();
                paintAutoValues();
            }
        });
        jitterRow.addView(jminus);
        jitterRow.addView(autoJitterValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        jitterRow.addView(jplus);
        card.addView(jitterRow);

        autoStartButton = pill("", true);
        autoStartButton.setPadding(dp(10), dp(12), dp(10), dp(12));
        autoStartButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (autoRunning) stopAuto(true);
                else startAuto();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        card.addView(autoStartButton, bp);

        TextView note = Ui.text(this,
                "Điểm chạm không ảnh hưởng thao tác khác. Mở tab này bấm Dừng để tắt.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);

        paintAutoValues();
        return card;
    }

    private void paintAutoValues() {
        if (autoIntervalValue != null) autoIntervalValue.setText(autoInterval + " ms");
        if (autoJitterValue != null) autoJitterValue.setText("\u00b1" + autoJitter + "%");
        if (autoStatusView != null) {
            autoStatusView.setText(autoX < 0 || autoY < 0
                    ? "Chưa chọn điểm chạm"
                    : "Điểm: " + Math.round(autoX) + ", " + Math.round(autoY)
                        + (autoRunning ? " · đang chạy" : ""));
        }
        if (autoStartButton != null) {
            autoStartButton.setText(autoRunning ? "Dừng tự động chạm" : "Bắt đầu tự động chạm");
            setPillState(autoStartButton, !autoRunning);
        }
    }

    private void pickAutoPoint() {
        closePanel(false);
        hideHandleForCapture();
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x40000000);

        final AutoPickView view = new AutoPickView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView hint = Ui.text(this, "Chạm vào điểm cần tự động bấm", 13, Ui.TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.framed(0xCC171C24, this, 14));
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(42);
        root.addView(hint, hp);

        TextView cancel = Ui.text(this, "Huỷ", 13, Ui.MUTED, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.framed(0xCC171C24, this, 14));
        cancel.setPadding(dp(20), dp(9), dp(20), dp(9));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeAutoPicker(root);
                restoreAfterCapture();
            }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cp.bottomMargin = dp(46);
        root.addView(cancel, cp);

        view.onPick = new Runnable() {
            public void run() {
                autoX = view.pickedX;
                autoY = view.pickedY;
                toolPrefs().edit().putFloat("auto_x", autoX).putFloat("auto_y", autoY).apply();
                removeAutoPicker(root);
                toast("Đã chọn điểm chạm");
                handler.postDelayed(new Runnable() {
                    public void run() {
                        restoreAfterCapture();
                    }
                }, 120);
            }
        };

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, lp);
            autoPickRoot = root;
        } catch (Exception e) {
            restoreAfterCapture();
            toast("Không mở được lớp chọn điểm");
        }
    }

    private View autoPickRoot;

    private void removeAutoPicker(View root) {
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        if (autoPickRoot == root) autoPickRoot = null;
    }

    private class AutoPickView extends View {
        Runnable onPick;
        float pickedX;
        float pickedY;

        AutoPickView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                pickedX = e.getRawX();
                pickedY = e.getRawY();
                if (onPick != null) onPick.run();
            }
            return true;
        }
    }

    private void startAuto() {
        if (!ShotService.ready()) {
            toast("Bật Trợ năng cho Essentials một lần rồi thử lại");
            openAccessibilitySettings();
            return;
        }
        if (autoX < 0 || autoY < 0) {
            toast("Chọn điểm chạm trước");
            return;
        }
        autoRunning = true;
        showAutoDot();
        paintToggle("#autoclick", true, true);
        paintAutoValues();
        handler.removeCallbacks(autoClickTick);
        handler.postDelayed(autoClickTick, 120);
        toast("Bắt đầu tự động chạm");
    }

    private void stopAuto(boolean announce) {
        autoRunning = false;
        handler.removeCallbacks(autoClickTick);
        removeAutoDot();
        paintToggle("#autoclick", false, true);
        paintAutoValues();
        if (announce) toast("Đã dừng tự động chạm");
    }

    private final Runnable autoClickTick = new Runnable() {
        public void run() {
            if (!autoRunning) return;
            float ix = autoX;
            float iy = autoY;
            if (autoJitter > 0) {
                float span = Math.max(dp(1), dp(9) * autoJitter / 100f);
                ix += (autoRandom.nextFloat() * 2f - 1f) * span;
                iy += (autoRandom.nextFloat() * 2f - 1f) * span;
            }
            if (!ShotService.tap(ix, iy)) {
                stopAuto(false);
                toast("Trợ năng ngắt kết nối, đã dừng");
                return;
            }
            int base = Math.max(40, autoInterval);
            int spread = Math.round(base * autoJitter / 100f);
            int delay = base;
            if (spread > 0) {
                delay += autoRandom.nextInt(spread * 2 + 1) - spread;
            }
            handler.postDelayed(this, Math.max(25, delay));
        }
    };

    private void showAutoDot() {
        if (autoDot != null) updateAutoDot();
        if (autoDot == null) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xCC27D3E0);
            g.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
            dot.setBackground(g);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(14), dp(14), TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            autoDot = dot;
            autoDotParams = lp;
            try {
                wm.addView(dot, lp);
            } catch (Exception e) {
                autoDot = null;
                autoDotParams = null;
                return;
            }
        }
        updateAutoDot();
    }

    private void updateAutoDot() {
        if (autoDot == null || autoDotParams == null) return;
        autoDotParams.x = clamp(Math.round(autoX) - dp(7), 0, Math.max(0, screenW() - dp(14)));
        autoDotParams.y = clamp(Math.round(autoY) - dp(7), 0, Math.max(0, screenH() - dp(14)));
        try {
            wm.updateViewLayout(autoDot, autoDotParams);
        } catch (Exception ignored) {
        }
    }

    private void removeAutoDot() {
        if (autoDot == null) return;
        try {
            wm.removeView(autoDot);
        } catch (Exception ignored) {
        }
        autoDot = null;
        autoDotParams = null;
    }

    // ------------------------------------------------------------ password & hash

    private int passLen = 16;
    private boolean passSpecial = true;

    private void openSecure() {
        if (tabViews.containsKey(TAB_SECURE)) return;
        SharedPreferences sp = getSharedPreferences("secure", MODE_PRIVATE);
        passLen = clamp(sp.getInt("len", passLen), 8, 64);
        passSpecial = sp.getBoolean("special", passSpecial);
        buildingTab = TAB_SECURE;
        LinearLayout card = buildSecurePanel();
        buildingTab = null;
        addTab(TAB_SECURE, card);
    }

    private LinearLayout buildSecurePanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Mật khẩu & mã hoá", false, null));

        TextView hint = Ui.text(this,
                "Tạo mật khẩu mạnh và mã hoá văn bản ngay trên máy, không dùng mạng.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        card.addView(hint, hp);

        card.addView(label("MẬT KHẨU NGẪU NHIÊN"));
        final TextView password = Ui.text(this, "", 15, Ui.ACCENT, true);
        password.setTypeface(android.graphics.Typeface.MONOSPACE);
        password.setTextIsSelectable(true);
        password.setBackground(Ui.framed(ITEM_BG_SOFT, this, 12));
        password.setPadding(dp(12), dp(10), dp(12), dp(10));

        final TextView lenValue = Ui.text(this, passLen + " ký tự", 12, Ui.TEXT, false);
        lenValue.setGravity(Gravity.CENTER);
        final TextView special = pill(passSpecial ? "Có ký tự đặc biệt" : "Chỉ chữ & số", false);
        special.setPadding(dp(10), dp(7), dp(10), dp(7));
        special.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                passSpecial = !passSpecial;
                getSharedPreferences("secure", MODE_PRIVATE).edit()
                        .putBoolean("special", passSpecial).apply();
                special.setText(passSpecial ? "Có ký tự đặc biệt" : "Chỉ chữ & số");
                setPillState(special, passSpecial);
            }
        });

        final Runnable generate = new Runnable() {
            public void run() {
                password.setText(generatePassword(passLen, passSpecial));
            }
        };

        LinearLayout lenRow = Ui.row(this);
        TextView minus = pill("\u2212", false);
        minus.setPadding(dp(13), dp(7), dp(13), dp(7));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                passLen = clamp(passLen - 4, 8, 64);
                lenValue.setText(passLen + " ký tự");
                getSharedPreferences("secure", MODE_PRIVATE).edit()
                        .putInt("len", passLen).apply();
            }
        });
        TextView plus = pill("+", false);
        plus.setPadding(dp(13), dp(7), dp(13), dp(7));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                passLen = clamp(passLen + 4, 8, 64);
                lenValue.setText(passLen + " ký tự");
                getSharedPreferences("secure", MODE_PRIVATE).edit()
                        .putInt("len", passLen).apply();
            }
        });
        lenRow.addView(minus);
        lenRow.addView(lenValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lenRow.addView(plus);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = dp(2);
        card.addView(lenRow, lrp);

        LinearLayout specialRow = Ui.row(this);
        specialRow.addView(special);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(6);
        card.addView(specialRow, srp);
        setPillState(special, passSpecial);

        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(10);
        card.addView(password, pp);

        LinearLayout passActions = Ui.row(this);
        TextView renew = pill("Tạo mới", true);
        renew.setPadding(dp(10), dp(9), dp(10), dp(9));
        renew.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                generate.run();
                Ui.pop(password);
            }
        });
        TextView copyPass = pill("Chép", false);
        copyPass.setPadding(dp(10), dp(9), dp(10), dp(9));
        copyPass.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (password.getText().length() > 0) copyToClipboard(password.getText().toString());
            }
        });
        passActions.addView(renew, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp2 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp2.leftMargin = dp(6);
        passActions.addView(copyPass, cp2);
        LinearLayout.LayoutParams pap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pap.topMargin = dp(8);
        card.addView(passActions, pap);

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dl.topMargin = dp(14);
        dl.bottomMargin = dp(2);
        card.addView(divider, dl);

        card.addView(label("VĂN BẢN & MÃ HOÁ"));
        final EditText input = new EditText(this);
        input.setBackground(Ui.framed(0xFF0E1116, this, 12));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xFF6A7382);
        input.setHint("Nhập văn bản cần xử lý...");
        input.setTextSize(13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        input.setMinLines(2);
        input.setMaxLines(4);
        card.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));

        final TextView output = Ui.text(this, "", 12, Ui.TEXT, false);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setBackground(Ui.framed(ITEM_BG_SOFT, this, 12));
        output.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout actionRow1 = Ui.row(this);
        TextView b64 = pill("Base64", false);
        b64.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(encodeBase64(input.getText().toString()));
            }
        });
        TextView unb64 = pill("Giải Base64", false);
        unb64.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String r = decodeBase64(input.getText().toString());
                output.setText(r == null ? "Base64 không hợp lệ" : r);
            }
        });
        actionRow1.addView(b64, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams a1 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a1.leftMargin = dp(6);
        actionRow1.addView(unb64, a1);

        LinearLayout actionRow2 = Ui.row(this);
        TextView md5 = pill("MD5", false);
        md5.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(hash("MD5", input.getText().toString()));
            }
        });
        TextView sha = pill("SHA-256", false);
        sha.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(hash("SHA-256", input.getText().toString()));
            }
        });
        actionRow2.addView(md5, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams a2 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a2.leftMargin = dp(6);
        actionRow2.addView(sha, a2);

        LinearLayout.LayoutParams ar1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ar1.topMargin = dp(8);
        card.addView(actionRow1, ar1);
        LinearLayout.LayoutParams ar2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ar2.topMargin = dp(6);
        card.addView(actionRow2, ar2);

        View baseDivider = new View(this);
        baseDivider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams bdl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        bdl.topMargin = dp(14);
        bdl.bottomMargin = dp(2);
        card.addView(baseDivider, bdl);

        card.addView(label("HỆ CƠ SỐ & ASCII"));
        LinearLayout baseRow1 = Ui.row(this);
        TextView asBin = pill("Nhị phân", false);
        asBin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(baseReport(input.getText().toString(), 2));
            }
        });
        TextView asHex = pill("Hex", false);
        asHex.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(baseReport(input.getText().toString(), 16));
            }
        });
        TextView asDec = pill("Thập phân", false);
        asDec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(baseReport(input.getText().toString(), 10));
            }
        });
        baseRow1.addView(asBin, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams b1 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.leftMargin = dp(6);
        baseRow1.addView(asHex, b1);
        LinearLayout.LayoutParams b1b = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1b.leftMargin = dp(6);
        baseRow1.addView(asDec, b1b);
        LinearLayout.LayoutParams br1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        br1.topMargin = dp(6);
        card.addView(baseRow1, br1);

        LinearLayout baseRow2 = Ui.row(this);
        TextView a2h = pill("ASCII → Hex", false);
        a2h.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(asciiToHex(input.getText().toString()));
            }
        });
        TextView h2a = pill("Hex → ASCII", false);
        h2a.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(hexToAscii(input.getText().toString()));
            }
        });
        baseRow2.addView(a2h, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams b2 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b2.leftMargin = dp(6);
        baseRow2.addView(h2a, b2);
        LinearLayout.LayoutParams br2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        br2.topMargin = dp(6);
        card.addView(baseRow2, br2);

        card.addView(label("CAESAR"));
        final int[] caesarShift = {3};
        LinearLayout shiftRow = Ui.row(this);
        TextView shiftMinus = pill("−", false);
        shiftMinus.setPadding(dp(13), dp(7), dp(13), dp(7));
        final TextView shiftValue = Ui.text(this, "dịch " + caesarShift[0], 12, Ui.TEXT, true);
        shiftValue.setGravity(Gravity.CENTER);
        shiftMinus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                caesarShift[0] = caesarShift[0] <= 1 ? 25 : caesarShift[0] - 1;
                shiftValue.setText("dịch " + caesarShift[0]);
            }
        });
        TextView shiftPlus = pill("+", false);
        shiftPlus.setPadding(dp(13), dp(7), dp(13), dp(7));
        shiftPlus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                caesarShift[0] = caesarShift[0] >= 25 ? 1 : caesarShift[0] + 1;
                shiftValue.setText("dịch " + caesarShift[0]);
            }
        });
        shiftRow.addView(shiftMinus);
        shiftRow.addView(shiftValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        shiftRow.addView(shiftPlus);
        card.addView(shiftRow);

        LinearLayout caesarRow = Ui.row(this);
        TextView encodeCaesar = pill("Mã hoá", false);
        encodeCaesar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(caesar(input.getText().toString(), caesarShift[0]));
            }
        });
        TextView decodeCaesar = pill("Giải mã", false);
        decodeCaesar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                output.setText(caesar(input.getText().toString(), -caesarShift[0]));
            }
        });
        caesarRow.addView(encodeCaesar, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cr = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cr.leftMargin = dp(6);
        caesarRow.addView(decodeCaesar, cr);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(6);
        card.addView(caesarRow, crp);

        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(8);
        card.addView(output, op);

        TextView copyOut = pill("Chép kết quả", false);
        copyOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (output.getText().length() > 0) copyToClipboard(output.getText().toString());
            }
        });
        LinearLayout.LayoutParams cop = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cop.topMargin = dp(8);
        card.addView(copyOut, cop);

        generate.run();
        return card;
    }

    private String generatePassword(int length, boolean special) {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnopqrstuvwxyz";
        String digits = "23456789";
        String extra = "!@#$%^&*()-_=+[]{}";
        String pool = upper + lower + digits + (special ? extra : "");
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        sb.append(upper.charAt(rnd.nextInt(upper.length())));
        sb.append(lower.charAt(rnd.nextInt(lower.length())));
        sb.append(digits.charAt(rnd.nextInt(digits.length())));
        if (special) sb.append(extra.charAt(rnd.nextInt(extra.length())));
        while (sb.length() < length) sb.append(pool.charAt(rnd.nextInt(pool.length())));
        for (int i = sb.length() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            char t = sb.charAt(i);
            sb.setCharAt(i, sb.charAt(j));
            sb.setCharAt(j, t);
        }
        return sb.toString();
    }

    private String encodeBase64(String text) {
        try {
            return android.util.Base64.encodeToString(
                    text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "Không mã hoá được";
        }
    }

    private String decodeBase64(String text) {
        try {
            byte[] bytes = android.util.Base64.decode(
                    text.trim(), android.util.Base64.DEFAULT);
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private String hash(String algorithm, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Không băm được";
        }
    }

    // ------------------------------------------------------------ battery alert

    private boolean battLowOn;
    private boolean battFullOn;
    private boolean battLowFired;
    private boolean battFullFired;
    private int battLowPct = 25;
    private int battFullPct = 85;
    private int battPct = -1;
    private boolean battCharging;
    private TextView battStatusView;

    private SharedPreferences battPrefs() {
        return getSharedPreferences("battery", MODE_PRIVATE);
    }

    private void openBattery() {
        if (tabViews.containsKey(TAB_BATTERY)) return;
        SharedPreferences sp = battPrefs();
        battLowOn = sp.getBoolean("low_on", false);
        battFullOn = sp.getBoolean("full_on", false);
        battLowPct = clamp(sp.getInt("low_pct", battLowPct), 5, 50);
        battFullPct = clamp(sp.getInt("full_pct", battFullPct), 50, 100);
        buildingTab = TAB_BATTERY;
        LinearLayout card = buildBatteryPanel();
        buildingTab = null;
        addTab(TAB_BATTERY, card);
        refreshBatteryStatus();
    }

    private LinearLayout buildBatteryPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Cảnh báo pin", false, null));

        battStatusView = Ui.text(this, "Pin: --%", 13, Ui.ACCENT, true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(2);
        sp.bottomMargin = dp(6);
        card.addView(battStatusView, sp);

        card.addView(batteryThresholdRow(true));
        card.addView(batteryThresholdRow(false));

        TextView note = Ui.text(this,
                "Rung nhẹ và hiện thông báo khi pin chạm ngưỡng. Mỗi ngưỡng chỉ báo "
                        + "một lần cho tới khi pin đổi chiều đủ xa.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        card.addView(note, np);
        return card;
    }

    private View batteryThresholdRow(final boolean low) {
        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.framed(ITEM_BG_SOFT, this, 14));
        box.setPadding(dp(11), dp(9), dp(11), dp(10));

        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, low ? "Báo khi pin thấp" : "Báo khi pin đầy",
                13, Ui.TEXT, false);
        top.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView toggle = pill(low ? (battLowOn ? "Bật" : "Tắt")
                : (battFullOn ? "Bật" : "Tắt"), false);
        toggle.setPadding(dp(12), dp(6), dp(12), dp(6));
        setPillState(toggle, low ? battLowOn : battFullOn);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (low) {
                    battLowOn = !battLowOn;
                    battPrefs().edit().putBoolean("low_on", battLowOn).apply();
                    toggle.setText(battLowOn ? "Bật" : "Tắt");
                    setPillState(toggle, battLowOn);
                } else {
                    battFullOn = !battFullOn;
                    battPrefs().edit().putBoolean("full_on", battFullOn).apply();
                    toggle.setText(battFullOn ? "Bật" : "Tắt");
                    setPillState(toggle, battFullOn);
                }
                if (battPct >= 0) handleBattery(battPct, battCharging);
            }
        });
        top.addView(toggle);
        box.addView(top);

        final TextView value = Ui.text(this,
                (low ? battLowPct : battFullPct) + "%", 18, Ui.ACCENT, true);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);

        LinearLayout row = Ui.row(this);
        TextView minus = pill("\u22125", false);
        minus.setPadding(dp(11), dp(6), dp(11), dp(6));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (low) battLowPct = clamp(battLowPct - 5, 5, 50);
                else battFullPct = clamp(battFullPct - 5, 50, 100);
                value.setText((low ? battLowPct : battFullPct) + "%");
                battPrefs().edit()
                        .putInt(low ? "low_pct" : "full_pct",
                                low ? battLowPct : battFullPct).apply();
            }
        });
        TextView plus = pill("+5", false);
        plus.setPadding(dp(11), dp(6), dp(11), dp(6));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (low) battLowPct = clamp(battLowPct + 5, 5, 50);
                else battFullPct = clamp(battFullPct + 5, 50, 100);
                value.setText((low ? battLowPct : battFullPct) + "%");
                battPrefs().edit()
                        .putInt(low ? "low_pct" : "full_pct",
                                low ? battLowPct : battFullPct).apply();
            }
        });
        row.addView(minus);
        row.addView(value, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        box.addView(row, rp);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(10);
        box.setLayoutParams(bp);
        return box;
    }

    private void refreshBatteryStatus() {
        if (battStatusView == null) return;
        if (battPct < 0) {
            battStatusView.setText("Pin: --%");
            return;
        }
        battStatusView.setText("Pin: " + battPct + "% · "
                + (battCharging ? "đang sạc" : "đang dùng pin"));
    }

    private void handleBattery(int pct, boolean charging) {
        battPct = pct;
        battCharging = charging;
        if (charging || pct > battLowPct + 2) battLowFired = false;
        if (!charging || pct < battFullPct - 2) battFullFired = false;

        if (battLowOn && !charging && pct <= battLowPct && !battLowFired) {
            battLowFired = true;
            alertBattery("Pin thấp · " + pct + "%", "Cắm sạc để tránh tụt nguồn");
        }
        if (battFullOn && charging && pct >= battFullPct && !battFullFired) {
            battFullFired = true;
            alertBattery("Pin đã đầy · " + pct + "%", "Rút sạc để giữ tuổi thọ pin");
        }
        refreshBatteryStatus();
    }

    private void alertBattery(String title, String sub) {
        toast(title + " · " + sub);
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(170,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(170);
            }
        } catch (Exception ignored) {
        }
    }

    private BroadcastReceiver batteryReceiver;

    private void registerBatteryMonitor() {
        if (batteryReceiver != null) return;
        SharedPreferences sp = battPrefs();
        battLowOn = sp.getBoolean("low_on", false);
        battFullOn = sp.getBoolean("full_on", false);
        battLowPct = clamp(sp.getInt("low_pct", battLowPct), 5, 50);
        battFullPct = clamp(sp.getInt("full_pct", battFullPct), 50, 100);
        batteryReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level < 0 || scale <= 0) return;
                int pct = Math.round(level * 100f / scale);
                int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                handleBattery(pct, charging);
            }
        };
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            batteryReceiver = null;
        }
    }

    private void unregisterBatteryMonitor() {
        if (batteryReceiver == null) return;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        batteryReceiver = null;
    }

    // ------------------------------------------------------------ floating reference image

    private ImageView refView;
    private WindowManager.LayoutParams refParams;
    private Bitmap refBitmap;
    private boolean refPinned;
    private boolean refClickThrough;
    private int refAlpha = 92;
    private int refWidthPct = 60;
    private TextView refStateView;

    private SharedPreferences refPrefs() {
        return getSharedPreferences("refimage", MODE_PRIVATE);
    }

    static void setReferenceImage(Context c, Uri uri) {
        if (uri == null) return;
        c.getSharedPreferences("refimage", Context.MODE_PRIVATE).edit()
                .putString("uri", uri.toString())
                .putBoolean("on", true)
                .apply();
        if (current != null) current.showReference(true);
    }

    private void openRefImage() {
        if (tabViews.containsKey(TAB_REFIMAGE)) return;
        SharedPreferences sp = refPrefs();
        refAlpha = clamp(sp.getInt("alpha", refAlpha), 20, 100);
        refClickThrough = sp.getBoolean("click", refClickThrough);
        refWidthPct = clamp(sp.getInt("width", refWidthPct), 20, 100);
        buildingTab = TAB_REFIMAGE;
        LinearLayout card = buildRefPanel();
        buildingTab = null;
        addTab(TAB_REFIMAGE, card);
        paintRefState();
    }

    private LinearLayout buildRefPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Ảnh tham chiếu nổi", false, null));

        TextView hint = Ui.text(this,
                "Ghim một ảnh lên màn hình để vừa xem mẫu vừa vẽ hoặc làm việc ở app khác.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        refStateView = Ui.text(this, "", 12, Ui.ACCENT, true);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssp.bottomMargin = dp(8);
        card.addView(refStateView, ssp);

        TextView choose = pill("Chọn ảnh từ thư viện", true);
        choose.setPadding(dp(10), dp(11), dp(10), dp(11));
        choose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel(false);
                try {
                    Intent i = new Intent(SidebarService.this, RefImageActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Không mở được thư viện ảnh");
                }
            }
        });
        card.addView(choose);

        LinearLayout pinRow = Ui.row(this);
        final TextView pin = pill("", false);
        pin.setPadding(dp(8), dp(10), dp(8), dp(10));
        pin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (refPinned) removeReference(true);
                else showReference(true);
                paintRefState();
            }
        });
        pinRow.addView(pin, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView click = pill("", false);
        click.setPadding(dp(8), dp(10), dp(8), dp(10));
        click.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refClickThrough = !refClickThrough;
                refPrefs().edit().putBoolean("click", refClickThrough).apply();
                applyRefTouchMode();
                paintRefState();
            }
        });
        LinearLayout.LayoutParams clp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(6);
        pinRow.addView(click, clp);
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prp.topMargin = dp(8);
        card.addView(pinRow, prp);

        card.addView(label("ĐỘ MỜ"));
        final TextView alphaValue = Ui.text(this, refAlpha + "%", 12, Ui.TEXT, true);
        alphaValue.setGravity(Gravity.CENTER);
        LinearLayout alphaRow = Ui.row(this);
        TextView aminus = pill("\u2212", false);
        aminus.setPadding(dp(13), dp(7), dp(13), dp(7));
        aminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refAlpha = clamp(refAlpha - 10, 20, 100);
                refPrefs().edit().putInt("alpha", refAlpha).apply();
                alphaValue.setText(refAlpha + "%");
                applyRefAlpha();
            }
        });
        TextView aplus = pill("+", false);
        aplus.setPadding(dp(13), dp(7), dp(13), dp(7));
        aplus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refAlpha = clamp(refAlpha + 10, 20, 100);
                refPrefs().edit().putInt("alpha", refAlpha).apply();
                alphaValue.setText(refAlpha + "%");
                applyRefAlpha();
            }
        });
        alphaRow.addView(aminus);
        alphaRow.addView(alphaValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alphaRow.addView(aplus);
        card.addView(alphaRow);

        card.addView(label("KÍCH THƯỚC"));
        final TextView widthValue = Ui.text(this, refWidthPct + "% bề ngang", 12, Ui.TEXT, true);
        widthValue.setGravity(Gravity.CENTER);
        LinearLayout sizeRow = Ui.row(this);
        TextView sminus = pill("\u2212", false);
        sminus.setPadding(dp(13), dp(7), dp(13), dp(7));
        sminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refWidthPct = clamp(refWidthPct - 10, 20, 100);
                refPrefs().edit().putInt("width", refWidthPct).apply();
                widthValue.setText(refWidthPct + "% bề ngang");
                applyRefSize();
            }
        });
        TextView splus = pill("+", false);
        splus.setPadding(dp(13), dp(7), dp(13), dp(7));
        splus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refWidthPct = clamp(refWidthPct + 10, 20, 100);
                refPrefs().edit().putInt("width", refWidthPct).apply();
                widthValue.setText(refWidthPct + "% bề ngang");
                applyRefSize();
            }
        });
        sizeRow.addView(sminus);
        sizeRow.addView(widthValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeRow.addView(splus);
        card.addView(sizeRow);

        TextView note = Ui.text(this,
                "Khi tắt Xuyên thấu: kéo ảnh để dời, chụm hai ngón để phóng to/thu nhỏ. "
                        + "Bật Xuyên thấu để vẽ xuyên qua ảnh.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        card.addView(note, np);

        refPinButton = pin;
        refClickButton = click;
        paintRefState();
        return card;
    }

    private TextView refPinButton;
    private TextView refClickButton;

    private void paintRefState() {
        boolean hasImage = refPrefs().getString("uri", null) != null;
        if (refStateView != null) {
            refStateView.setText(refPinned ? "Đang ghim ảnh trên màn hình"
                    : hasImage ? "Đã chọn ảnh · chưa ghim"
                    : "Chưa chọn ảnh");
        }
        if (refPinButton != null) {
            refPinButton.setText(refPinned ? "Gỡ ảnh khỏi màn hình" : "Ghim ảnh lên màn hình");
            setPillState(refPinButton, !refPinned);
        }
        if (refClickButton != null) {
            refClickButton.setText(refClickThrough ? "Xuyên thấu: bật" : "Xuyên thấu: tắt");
            setPillState(refClickButton, refClickThrough);
        }
    }

    private Bitmap loadRefBitmap(Uri uri) {
        InputStream in = null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, probe);
            if (in != null) in.close();
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= 1400
                    && probe.outHeight / (sample * 2) >= 1400) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void showReference(boolean announce) {
        String raw = refPrefs().getString("uri", null);
        if (raw == null) {
            toast("Chọn ảnh tham chiếu trước");
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            toast("Đường dẫn ảnh không hợp lệ");
            return;
        }
        removeReferenceWindow();
        Bitmap bmp = loadRefBitmap(uri);
        if (bmp == null) {
            toast("Không đọc được ảnh");
            return;
        }
        refBitmap = bmp;
        int w = Math.max(dp(80), Math.round(screenW() * refWidthPct / 100f));
        int h = Math.max(dp(80), Math.round(w * bmp.getHeight() / (float) bmp.getWidth()));
        if (h > screenH() - dp(24)) {
            h = Math.max(dp(80), screenH() - dp(24));
            w = Math.max(dp(80), Math.round(h * bmp.getWidth() / (float) bmp.getHeight()));
        }

        ImageView view = new ImageView(this);
        view.setImageBitmap(bmp);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setAlpha(refAlpha / 100f);
        view.setOnTouchListener(refTouch);

        SharedPreferences sp = refPrefs();
        int x = clamp(sp.getInt("x", dp(16)), 0, Math.max(0, screenW() - w));
        int y = clamp(sp.getInt("y", screenH() / 3), 0, Math.max(0, screenH() - h));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (refClickThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0)
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            if (refBitmap != null) refBitmap.recycle();
            refBitmap = null;
            toast("Không ghim được ảnh");
            return;
        }
        refView = view;
        refParams = lp;
        refPinned = true;
        refPrefs().edit().putBoolean("on", true).apply();
        paintToggle("#refimage", true, true);
        paintRefState();
        if (announce) toast("Đã ghim ảnh tham chiếu");
    }

    private void removeReference(boolean announce) {
        removeReferenceWindow();
        refPrefs().edit().putBoolean("on", false).apply();
        paintToggle("#refimage", false, true);
        paintRefState();
        if (announce) toast("Đã gỡ ảnh");
    }

    private void removeReferenceWindow() {
        if (refView != null) {
            try {
                wm.removeView(refView);
            } catch (Exception ignored) {
            }
            refView = null;
        }
        refParams = null;
        if (refBitmap != null) {
            refBitmap.recycle();
            refBitmap = null;
        }
        refPinned = false;
    }

    private void applyRefAlpha() {
        if (refView != null) refView.setAlpha(refAlpha / 100f);
    }

    private void applyRefTouchMode() {
        if (refView == null || refParams == null) return;
        refParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | (refClickThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0)
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        try {
            wm.updateViewLayout(refView, refParams);
        } catch (Exception ignored) {
        }
    }

    private void applyRefSize() {
        if (refView == null || refParams == null || refBitmap == null) return;
        int w = Math.max(dp(80), Math.round(screenW() * refWidthPct / 100f));
        int h = Math.max(dp(80), Math.round(w * refBitmap.getHeight()
                / (float) refBitmap.getWidth()));
        if (h > screenH() - dp(24)) {
            h = Math.max(dp(80), screenH() - dp(24));
            w = Math.max(dp(80), Math.round(h * refBitmap.getWidth()
                    / (float) refBitmap.getHeight()));
        }
        refParams.width = w;
        refParams.height = h;
        refParams.x = clamp(refParams.x, 0, Math.max(0, screenW() - w));
        refParams.y = clamp(refParams.y, 0, Math.max(0, screenH() - h));
        try {
            wm.updateViewLayout(refView, refParams);
        } catch (Exception ignored) {
        }
        refPrefs().edit().putInt("x", refParams.x).putInt("y", refParams.y).apply();
    }

    private final View.OnTouchListener refTouch = new View.OnTouchListener() {
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private float startDist;
        private int startW;
        private int startH;
        private boolean pinching;

        public boolean onTouch(View view, MotionEvent e) {
            if (refParams == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = refParams.x;
                    startY = refParams.y;
                    pinching = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() >= 2) {
                        pinching = true;
                        startDist = spacing(e);
                        startW = refParams.width;
                        startH = refParams.height;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pinching && e.getPointerCount() >= 2 && startDist > 1f) {
                        float k = spacing(e) / startDist;
                        int w = clamp(Math.round(startW * k), dp(70), screenW());
                        int h = Math.max(dp(70), Math.round(w * startH / (float) startW));
                        if (h > screenH()) {
                            h = screenH();
                            w = Math.max(dp(70), Math.round(h * startW / (float) startH));
                        }
                        int cx = refParams.x + refParams.width / 2;
                        int cy = refParams.y + refParams.height / 2;
                        refParams.width = w;
                        refParams.height = h;
                        refParams.x = clamp(cx - w / 2, 0, Math.max(0, screenW() - w));
                        refParams.y = clamp(cy - h / 2, 0, Math.max(0, screenH() - h));
                        try {
                            wm.updateViewLayout(refView, refParams);
                        } catch (Exception ignored) {
                        }
                    } else if (!pinching) {
                        refParams.x = clamp(startX + Math.round(e.getRawX() - downX),
                                -refParams.width / 2,
                                Math.max(-refParams.width / 2, screenW() - refParams.width / 2));
                        refParams.y = clamp(startY + Math.round(e.getRawY() - downY),
                                -refParams.height / 2,
                                Math.max(-refParams.height / 2, screenH() - refParams.height / 2));
                        try {
                            wm.updateViewLayout(refView, refParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (refParams != null) {
                        refParams.x = clamp(refParams.x, 0,
                                Math.max(0, screenW() - refParams.width));
                        refParams.y = clamp(refParams.y, 0,
                                Math.max(0, screenH() - refParams.height));
                        try {
                            wm.updateViewLayout(refView, refParams);
                        } catch (Exception ignored) {
                        }
                        refPrefs().edit()
                                .putInt("x", refParams.x)
                                .putInt("y", refParams.y)
                                .apply();
                        refWidthPct = clamp(Math.round(refParams.width * 100f / screenW()),
                                20, 100);
                        refPrefs().edit().putInt("width", refWidthPct).apply();
                    }
                    pinching = false;
                    return true;
                default:
                    return false;
            }
        }

        private float spacing(MotionEvent e) {
            if (e.getPointerCount() < 2) return 1f;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return Math.max(1f, (float) Math.hypot(dx, dy));
        }
    };


    // ------------------------------------------------------------ notification peeker

    private TextView peekerStatus;
    private LinearLayout peekerList;

    private final Runnable peekerRefresh = new Runnable() {
        public void run() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(this);
                return;
            }
            renderPeeker();
        }
    };

    private void openPeeker() {
        if (tabViews.containsKey(TAB_PEEKER)) return;
        buildingTab = TAB_PEEKER;
        LinearLayout card = buildPeekerPanel();
        buildingTab = null;
        addTab(TAB_PEEKER, card);
        Peeker.listener = peekerRefresh;
        renderPeeker();
    }

    private LinearLayout buildPeekerPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Thông báo nhanh", false, null));

        TextView hint = Ui.text(this,
                "Giữ lại những dòng thông báo, toast hay cảnh báo chỉ thoáng qua rồi biến mất.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        peekerStatus = Ui.text(this, "", 12, Ui.ACCENT, true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = dp(8);
        card.addView(peekerStatus, sp);

        ScrollView sv = new ScrollView(this);
        peekerList = Ui.col(this);
        sv.addView(peekerList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        card.addView(sv, svp);

        LinearLayout actions = Ui.row(this);
        TextView copyAll = pill("Chép tất cả", false);
        copyAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<Peeker.Entry> entries = Peeker.snapshot();
                if (entries.isEmpty()) {
                    toast("Chưa có thông báo nào");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Peeker.Entry e = entries.get(i);
                    sb.append('[').append(timeText(e.time)).append("] ")
                            .append(e.app).append(": ").append(e.text).append('\n');
                }
                copyToClipboard(sb.toString().trim());
            }
        });
        TextView clear = pill("Xoá", false);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Peeker.clear();
                renderPeeker();
                toast("Đã xoá lịch sử");
            }
        });
        actions.addView(copyAll, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams clp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(6);
        actions.addView(clear, clp);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(8);
        card.addView(actions, ap);

        TextView note = Ui.text(this,
                "Cần bật Trợ năng cho Essentials để đọc được thông báo. Lịch sử chỉ nằm trong bộ nhớ.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private String timeText(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date(millis));
    }

    private void renderPeeker() {
        if (peekerList == null) return;
        List<Peeker.Entry> entries = Peeker.snapshot();
        if (peekerStatus != null) {
            if (!ShotService.ready()) {
                peekerStatus.setText("Chưa bật Trợ năng cho Essentials");
            } else {
                peekerStatus.setText("Đang ghi · " + entries.size() + " mục");
            }
        }
        peekerList.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = Ui.text(this, "Chưa bắt được thông báo nào. "
                    + "Thử mở một ứng dụng có thông báo nổi.", 12, Ui.MUTED, false);
            empty.setPadding(dp(2), dp(10), dp(2), dp(10));
            peekerList.addView(empty);
            return;
        }
        for (final Peeker.Entry e : entries) {
            LinearLayout row = Ui.col(this);
            row.setBackground(Ui.framed(ITEM_BG_SOFT, this, 12));
            row.setPadding(dp(10), dp(8), dp(10), dp(9));
            TextView meta = Ui.text(this, timeText(e.time) + " · " + e.app,
                    10, Ui.MUTED, true);
            row.addView(meta);
            TextView text = Ui.text(this, e.text, 12, Ui.TEXT, false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(3);
            row.addView(text, tp);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    copyToClipboard(e.text);
                }
            });
            Ui.press(row, 0.98f);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(6);
            peekerList.addView(row, rp);
        }
    }

    // ------------------------------------------------------------ settings shortcuts

    private void openSystemSettings(Intent intent, String fallback) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent f = new Intent(fallback);
            f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(f);
        } catch (Exception e) {
            toast("Máy không mở được trang cài đặt này");
        }
    }

    // ------------------------------------------------------------ sticky scratchpad

    private View stickyView;
    private WindowManager.LayoutParams stickyParams;
    private EditText stickyEdit;
    private boolean stickyOn;
    private int stickyAlpha = 80;
    private boolean stickyClickThrough;
    private TextView stickyStatusView;
    private TextView stickyPinButton;
    private TextView stickyClickButton;

    private SharedPreferences stickyPrefs() {
        return getSharedPreferences("sticky", MODE_PRIVATE);
    }

    private void openSticky() {
        if (tabViews.containsKey(TAB_STICKY)) return;
        SharedPreferences sp = stickyPrefs();
        stickyAlpha = clamp(sp.getInt("alpha", stickyAlpha), 20, 100);
        stickyClickThrough = sp.getBoolean("click", false);
        buildingTab = TAB_STICKY;
        LinearLayout card = buildStickyPanel();
        buildingTab = null;
        addTab(TAB_STICKY, card);
        paintStickyState();
    }

    private LinearLayout buildStickyPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Bảng nháp ghim", false, null));

        TextView hint = Ui.text(this,
                "Tờ giấy nháp nhỏ đè lên màn hình để chép token, port hay tọa độ khi làm việc giữa hai cửa sổ.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        stickyStatusView = Ui.text(this, "", 12, Ui.ACCENT, true);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssp.bottomMargin = dp(8);
        card.addView(stickyStatusView, ssp);

        LinearLayout pinRow = Ui.row(this);
        stickyPinButton = pill("", true);
        stickyPinButton.setPadding(dp(8), dp(11), dp(8), dp(11));
        stickyPinButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (stickyOn) hideSticky(true);
                else showSticky(true);
                paintStickyState();
            }
        });
        stickyClickButton = pill("", false);
        stickyClickButton.setPadding(dp(8), dp(11), dp(8), dp(11));
        stickyClickButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stickyClickThrough = !stickyClickThrough;
                stickyPrefs().edit().putBoolean("click", stickyClickThrough).apply();
                applyStickyTouchMode();
                paintStickyState();
            }
        });
        pinRow.addView(stickyPinButton, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ccp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ccp.leftMargin = dp(6);
        pinRow.addView(stickyClickButton, ccp);
        card.addView(pinRow);

        card.addView(label("ĐỘ TRONG SUỐT"));
        final TextView alphaValue = Ui.text(this, stickyAlpha + "%", 12, Ui.TEXT, true);
        alphaValue.setGravity(Gravity.CENTER);
        LinearLayout alphaRow = Ui.row(this);
        TextView aminus = pill("\u2212", false);
        aminus.setPadding(dp(13), dp(7), dp(13), dp(7));
        aminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stickyAlpha = clamp(stickyAlpha - 10, 20, 100);
                stickyPrefs().edit().putInt("alpha", stickyAlpha).apply();
                alphaValue.setText(stickyAlpha + "%");
                if (stickyView != null) stickyView.setAlpha(stickyAlpha / 100f);
            }
        });
        TextView aplus = pill("+", false);
        aplus.setPadding(dp(13), dp(7), dp(13), dp(7));
        aplus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stickyAlpha = clamp(stickyAlpha + 10, 20, 100);
                stickyPrefs().edit().putInt("alpha", stickyAlpha).apply();
                alphaValue.setText(stickyAlpha + "%");
                if (stickyView != null) stickyView.setAlpha(stickyAlpha / 100f);
            }
        });
        alphaRow.addView(aminus);
        alphaRow.addView(alphaValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alphaRow.addView(aplus);
        card.addView(alphaRow);

        TextView clear = pill("Xoá nội dung nháp", false);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stickyPrefs().edit().putString("text", "").apply();
                if (stickyEdit != null) stickyEdit.setText("");
                toast("Đã xoá bảng nháp");
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        card.addView(clear, clp);

        TextView note = Ui.text(this,
                "Kéo thanh NHÁP để dời bảng. Bật Xuyên thấu để chạm thẳng vào nút bên dưới; tắt để kéo và gõ.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        card.addView(note, np);

        paintStickyState();
        return card;
    }

    private void paintStickyState() {
        if (stickyStatusView != null) {
            boolean hasText = !stickyPrefs().getString("text", "").isEmpty();
            stickyStatusView.setText(stickyOn ? "Đang ghim trên màn hình"
                    : hasText ? "Có nội dung · chưa ghim" : "Bảng nháp trống");
        }
        if (stickyPinButton != null) {
            stickyPinButton.setText(stickyOn ? "Bỏ ghim" : "Ghim bảng");
            setPillState(stickyPinButton, !stickyOn);
        }
        if (stickyClickButton != null) {
            stickyClickButton.setText(stickyClickThrough ? "Xuyên thấu" : "Nhận chạm");
            setPillState(stickyClickButton, stickyClickThrough);
        }
    }

    private void applyStickyTouchMode() {
        if (stickyView == null || stickyParams == null) return;
        stickyParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (stickyClickThrough
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        try {
            wm.updateViewLayout(stickyView, stickyParams);
        } catch (Exception ignored) {
        }
    }

    private void showSticky(boolean announce) {
        if (stickyView != null) {
            paintStickyState();
            return;
        }
        SharedPreferences sp = stickyPrefs();
        stickyAlpha = clamp(sp.getInt("alpha", stickyAlpha), 20, 100);

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.framed(0xF0171C24, this, 14));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setAlpha(stickyAlpha / 100f);

        LinearLayout headerRow = Ui.row(this);
        TextView tag = Ui.text(this, "NHÁP", 10, Ui.ACCENT, true);
        tag.setLetterSpacing(0.14f);
        headerRow.addView(tag, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(this, "\u00d7", 16, Ui.MUTED, false);
        close.setPadding(dp(8), 0, dp(4), 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveStickyText();
                hideSticky(false);
            }
        });
        headerRow.addView(close);
        box.addView(headerRow);

        final EditText edit = new EditText(this);
        edit.setBackground(null);
        edit.setTextColor(Ui.TEXT);
        edit.setHintTextColor(0xFF6A7382);
        edit.setHint("Token, port, tọa độ...");
        edit.setTextSize(12);
        edit.setTypeface(android.graphics.Typeface.MONOSPACE);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setPadding(dp(2), dp(6), dp(2), dp(2));
        edit.setText(sp.getString("text", ""));
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable e) {
                stickyPrefs().edit().putString("text", e.toString()).apply();
            }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        ep.topMargin = dp(2);
        box.addView(edit, ep);
        stickyEdit = edit;

        int w = dp(252);
        int h = dp(150);
        int x = clamp(sp.getInt("x", dp(14)), 0, Math.max(0, screenW() - w));
        int y = clamp(sp.getInt("y", screenH() / 4), 0, Math.max(0, screenH() - h));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | (stickyClickThrough
                        ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;

        headerRow.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;

            public boolean onTouch(View v, MotionEvent e) {
                if (stickyParams == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = stickyParams.x;
                        startY = stickyParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        stickyParams.x = clamp(startX + Math.round(e.getRawX() - downX),
                                0, Math.max(0, screenW() - stickyParams.width));
                        stickyParams.y = clamp(startY + Math.round(e.getRawY() - downY),
                                0, Math.max(0, screenH() - stickyParams.height));
                        try {
                            wm.updateViewLayout(stickyView, stickyParams);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (stickyParams != null) {
                            stickyPrefs().edit()
                                    .putInt("x", stickyParams.x)
                                    .putInt("y", stickyParams.y)
                                    .apply();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(box, lp);
        } catch (Exception e) {
            stickyEdit = null;
            toast("Không ghim được bảng nháp");
            return;
        }
        stickyView = box;
        stickyParams = lp;
        stickyOn = true;
        stickyPrefs().edit().putBoolean("on", true).apply();
        paintToggle("#sticky", true, true);
        paintStickyState();
        if (announce) toast("Đã ghim bảng nháp");
    }

    private void hideSticky(boolean announce) {
        saveStickyText();
        removeStickyWindow();
        stickyPrefs().edit().putBoolean("on", false).apply();
        paintToggle("#sticky", false, true);
        paintStickyState();
        if (announce) toast("Đã bỏ ghim bảng nháp");
    }

    private void saveStickyText() {
        if (stickyEdit != null) {
            stickyPrefs().edit()
                    .putString("text", stickyEdit.getText().toString())
                    .apply();
        }
    }

    private void removeStickyWindow() {
        if (stickyView != null) {
            try {
                wm.removeView(stickyView);
            } catch (Exception ignored) {
            }
        }
        stickyView = null;
        stickyParams = null;
        stickyEdit = null;
        stickyOn = false;
    }

    // ------------------------------------------------------------ quick reminder

    private int reminderMin = 5;
    private int reminderSec = 0;
    private boolean reminderRunning;
    private long reminderEndAt;
    private TextView reminderStatusView;
    private TextView reminderStartButton;
    private EditText reminderInput;
    private TextView reminderMinValue;
    private TextView reminderSecValue;

    private static final int REMINDER_NOTIF_ID = 77;
    private static final String REMINDER_CHANNEL = "reminders";

    private SharedPreferences reminderPrefs() {
        return getSharedPreferences("reminder", MODE_PRIVATE);
    }

    private void openReminder() {
        if (tabViews.containsKey(TAB_REMINDER)) return;
        SharedPreferences sp = reminderPrefs();
        reminderMin = clamp(sp.getInt("min", reminderMin), 0, 180);
        reminderSec = clamp(sp.getInt("sec", reminderSec), 0, 55);
        buildingTab = TAB_REMINDER;
        LinearLayout card = buildReminderPanel(sp.getString("text", ""));
        buildingTab = null;
        addTab(TAB_REMINDER, card);
        paintReminder();
    }

    private LinearLayout buildReminderPanel(String savedText) {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Nhắc nhanh", false, null));

        TextView hint = Ui.text(this,
                "Hẹn giờ ngắn rồi rung và hiện thông báo kèm dòng chữ tự gõ.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        reminderInput = new EditText(this);
        reminderInput.setBackground(Ui.framed(0xFF0E1116, this, 12));
        reminderInput.setTextColor(Ui.TEXT);
        reminderInput.setHintTextColor(0xFF6A7382);
        reminderInput.setHint("15 phút nữa kiểm tra tiến trình...");
        reminderInput.setTextSize(13);
        reminderInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        reminderInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        reminderInput.setText(savedText);
        card.addView(reminderInput);

        card.addView(label("PHÚT"));
        LinearLayout minRow = Ui.row(this);
        TextView mminus = pill("\u2212", false);
        mminus.setPadding(dp(13), dp(7), dp(13), dp(7));
        mminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reminderMin = clamp(reminderMin - 1, 0, 180);
                saveReminderTime();
                paintReminder();
            }
        });
        reminderMinValue = Ui.text(this, "", 15, Ui.TEXT, true);
        reminderMinValue.setGravity(Gravity.CENTER);
        TextView mplus = pill("+", false);
        mplus.setPadding(dp(13), dp(7), dp(13), dp(7));
        mplus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reminderMin = clamp(reminderMin + 1, 0, 180);
                saveReminderTime();
                paintReminder();
            }
        });
        minRow.addView(mminus);
        minRow.addView(reminderMinValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        minRow.addView(mplus);
        card.addView(minRow);

        card.addView(label("GIÂY"));
        LinearLayout secRow = Ui.row(this);
        TextView sminus = pill("\u22125", false);
        sminus.setPadding(dp(11), dp(7), dp(11), dp(7));
        sminus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reminderSec = reminderSec - 5;
                if (reminderSec < 0) reminderSec = 55;
                saveReminderTime();
                paintReminder();
            }
        });
        reminderSecValue = Ui.text(this, "", 15, Ui.TEXT, true);
        reminderSecValue.setGravity(Gravity.CENTER);
        TextView splus = pill("+5", false);
        splus.setPadding(dp(11), dp(7), dp(11), dp(7));
        splus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reminderSec = reminderSec + 5;
                if (reminderSec > 55) reminderSec = 0;
                saveReminderTime();
                paintReminder();
            }
        });
        secRow.addView(sminus);
        secRow.addView(reminderSecValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        secRow.addView(splus);
        card.addView(secRow);

        reminderStartButton = pill("", true);
        reminderStartButton.setPadding(dp(10), dp(12), dp(10), dp(12));
        reminderStartButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (reminderRunning) cancelReminder(true);
                else startReminder();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        card.addView(reminderStartButton, bp);

        reminderStatusView = Ui.text(this, "", 12, Ui.ACCENT, true);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(8);
        card.addView(reminderStatusView, stp);

        TextView note = Ui.text(this,
                "Hết giờ sẽ rung, hiện toast và một thông báo hệ thống. Nhắc vẫn chạy khi đóng tab.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private void saveReminderTime() {
        reminderPrefs().edit()
                .putInt("min", reminderMin)
                .putInt("sec", reminderSec)
                .apply();
    }

    private void paintReminder() {
        if (reminderMinValue != null) reminderMinValue.setText(reminderMin + " phút");
        if (reminderSecValue != null) reminderSecValue.setText(reminderSec + " giây");
        if (reminderStartButton != null) {
            reminderStartButton.setText(reminderRunning ? "Huỷ nhắc" : "Bắt đầu nhắc");
            setPillState(reminderStartButton, !reminderRunning);
        }
        if (reminderStatusView != null) {
            if (reminderRunning) {
                long left = Math.max(0, reminderEndAt - SystemClock.elapsedRealtime());
                reminderStatusView.setText("Còn " + clockText(left)
                        + (reminderInput != null && reminderInput.getText().length() > 0
                        ? " · " + reminderInput.getText().toString().trim() : ""));
            } else {
                reminderStatusView.setText("Chưa có nhắc nào đang chạy");
            }
        }
    }

    private static String clockText(long millis) {
        long total = Math.max(0, Math.round(millis / 1000.0));
        long m = total / 60;
        long s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    private void startReminder() {
        int total = reminderMin * 60 + reminderSec;
        if (total <= 0) {
            toast("Đặt thời gian trước đã");
            return;
        }
        String text = reminderInput == null ? "" : reminderInput.getText().toString().trim();
        if (text.isEmpty()) text = "Hết giờ nhắc việc";
        reminderPrefs().edit().putString("text", text).apply();
        reminderRunning = true;
        reminderEndAt = SystemClock.elapsedRealtime() + total * 1000L;
        handler.removeCallbacks(reminderTick);
        handler.post(reminderTick);
        paintReminder();
        toast("Sẽ nhắc sau " + (reminderMin > 0 ? reminderMin + " phút " : "")
                + (reminderSec > 0 ? reminderSec + " giây" : "") + "");
    }

    private void cancelReminder(boolean announce) {
        reminderRunning = false;
        handler.removeCallbacks(reminderTick);
        paintReminder();
        if (announce) toast("Đã huỷ nhắc");
    }

    private void restartReminderTick() {
        if (!reminderRunning) return;
        handler.removeCallbacks(reminderTick);
        handler.post(reminderTick);
    }

    private final Runnable reminderTick = new Runnable() {
        public void run() {
            if (!reminderRunning) return;
            long left = reminderEndAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                fireReminder();
                return;
            }
            paintReminder();
            handler.postDelayed(this, 400);
        }
    };

    private void fireReminder() {
        reminderRunning = false;
        String text = reminderPrefs().getString("text", "Hết giờ nhắc việc");
        paintReminder();
        toast("\u23f0 " + text);
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 180, 120, 180}, -1));
                } else {
                    v.vibrate(new long[]{0, 180, 120, 180}, -1);
                }
            }
        } catch (Exception ignored) {
        }
        notifyReminder(text);
    }

    private void notifyReminder(String text) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(REMINDER_CHANNEL,
                        "Nhắc nhanh", NotificationManager.IMPORTANCE_HIGH);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 1,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, REMINDER_CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("Nhắc nhanh")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_toasttimer)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL);
            nm.notify(REMINDER_NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ base / ASCII / Caesar

    private String baseReport(String raw, int radix) {
        String t = raw == null ? "" : raw.trim()
                .replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (radix == 16 && t.startsWith("0x")) t = t.substring(2);
        if (radix == 2 && t.startsWith("0b")) t = t.substring(2);
        if (radix == 10 && (t.startsWith("0d") || t.startsWith("0n"))) t = t.substring(2);
        if (t.isEmpty()) return "Nhập số trước";
        try {
            java.math.BigInteger n = new java.math.BigInteger(t, radix);
            String bin = n.toString(2);
            String hex = n.toString(16).toUpperCase(Locale.ROOT);
            return "Nhị phân: " + bin + "\nHex: " + hex + "\nThập phân: " + n.toString();
        } catch (Exception e) {
            return "Không phải số cơ số " + radix;
        }
    }

    private String asciiToHex(String text) {
        try {
            byte[] bytes = text.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Không chuyển được";
        }
    }

    private String hexToAscii(String text) {
        if (text == null) return "";
        String t = text.replace("0x", "").replace("0X", "")
                .replace(" ", "").replace(",", "").replace(":", "")
                .replace("-", "").replace("\n", "").trim();
        if (t.isEmpty()) return "Dán chuỗi hex trước";
        if ((t.length() & 1) != 0) return "Chuỗi hex phải có số ký tự chẵn";
        try {
            byte[] bytes = new byte[t.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int hi = Character.digit(t.charAt(i * 2), 16);
                int lo = Character.digit(t.charAt(i * 2 + 1), 16);
                if (hi < 0 || lo < 0) return "Có ký tự không phải hex";
                bytes[i] = (byte) ((hi << 4) | lo);
            }
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "Không giải được hex";
        }
    }

    private String caesar(String text, int shift) {
        if (text == null) return "";
        int k = ((shift % 26) + 26) % 26;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + (c - 'a' + k) % 26));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + (c - 'A' + k) % 26));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }


    // ------------------------------------------------------------ farm session counter

    private static final int FARM_SLOTS = 3;
    private static final String[] FARM_DEFAULT_NAMES = {
            "Ván thắng", "Rương đã mở", "Nguyên liệu"
    };
    private final TextView[] farmValues = new TextView[FARM_SLOTS];
    private final EditText[] farmNames = new EditText[FARM_SLOTS];

    private SharedPreferences farmPrefs() {
        return getSharedPreferences("farm", MODE_PRIVATE);
    }

    private int farmValue(int i) {
        return farmPrefs().getInt("val" + i, 0);
    }

    private void setFarmValue(int i, int value) {
        farmPrefs().edit().putInt("val" + i, Math.max(0, value)).apply();
    }

    private String farmName(int i) {
        return farmPrefs().getString("name" + i, FARM_DEFAULT_NAMES[i]);
    }

    private void openFarm() {
        if (tabViews.containsKey(TAB_FARM)) return;
        buildingTab = TAB_FARM;
        LinearLayout card = buildFarmPanel();
        buildingTab = null;
        addTab(TAB_FARM, card);
    }

    private LinearLayout buildFarmPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Bộ đếm phiên cày", false, null));

        TextView hint = Ui.text(this,
                "Đếm số ván, lượt quay, rương hay nguyên liệu trong một phiên cày. Tên và số đều được lưu lại.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        for (int i = 0; i < FARM_SLOTS; i++) {
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(i == 0 ? 0 : 8);
            card.addView(farmRow(i), rp);
        }

        TextView resetAll = pill("Đặt lại cả phiên", false);
        resetAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < FARM_SLOTS; i++) {
                    setFarmValue(i, 0);
                    paintFarmValue(i);
                }
                toast("Đã đặt lại cả phiên");
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(12);
        card.addView(resetAll, ap);

        TextView note = Ui.text(this,
                "Chạm tên để đổi thành “Ván thua”, “Lượt quay”, “Quái đã farm”... Nút Đặt lại từng ô nằm ngay dưới mỗi chỉ số.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private View farmRow(final int index) {
        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.framed(ITEM_BG_SOFT, this, 14));
        box.setPadding(dp(11), dp(7), dp(11), dp(9));

        LinearLayout top = Ui.row(this);
        final EditText name = new EditText(this);
        name.setBackground(null);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(13);
        name.setHintTextColor(0xFF6A7382);
        name.setHint("Tên chỉ số");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setPadding(0, 0, dp(6), 0);
        name.setText(farmName(index));
        name.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable e) {
                farmPrefs().edit().putString("name" + index, e.toString()).apply();
            }
        });
        farmNames[index] = name;
        top.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = Ui.text(this, String.valueOf(farmValue(index)),
                26, Ui.ACCENT, true);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(58));
        farmValues[index] = value;
        top.addView(value);
        box.addView(top);

        LinearLayout actions = Ui.row(this);
        TextView minus = pill("\u22121", false);
        minus.setPadding(dp(8), dp(6), dp(8), dp(6));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setFarmValue(index, farmValue(index) - 1);
                paintFarmValue(index);
            }
        });
        TextView reset = pill("Đặt lại", false);
        reset.setPadding(dp(8), dp(6), dp(8), dp(6));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setFarmValue(index, 0);
                paintFarmValue(index);
            }
        });
        TextView plus = pill("+1", true);
        plus.setPadding(dp(8), dp(6), dp(8), dp(6));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setFarmValue(index, farmValue(index) + 1);
                paintFarmValue(index);
            }
        });
        LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(minus, lp);
        LinearLayout.LayoutParams lp2 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(5);
        actions.addView(reset, lp2);
        LinearLayout.LayoutParams lp3 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp3.leftMargin = dp(5);
        actions.addView(plus, lp3);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(6);
        box.addView(actions, ap);
        return box;
    }

    private void paintFarmValue(int index) {
        TextView value = farmValues[index];
        if (value == null) return;
        value.setText(String.valueOf(farmValue(index)));
        Ui.pop(value);
    }


    // ------------------------------------------------------------ damage calculator

    private SharedPreferences dmgPrefs() {
        return getSharedPreferences("dmgcalc", MODE_PRIVATE);
    }

    private void openDmgCalc() {
        if (tabViews.containsKey(TAB_DMG)) return;
        buildingTab = TAB_DMG;
        LinearLayout card = buildDmgPanel();
        buildingTab = null;
        addTab(TAB_DMG, card);
    }

    private EditText dmgField(LinearLayout parent, String label, String key,
                              String fallback, List<EditText> fields) {
        LinearLayout row = Ui.row(this);
        TextView name = Ui.text(this, label, 12, Ui.MUTED, false);
        row.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText e = new EditText(this);
        e.setBackground(Ui.framed(0xFF0E1116, this, 10));
        e.setTextColor(Ui.TEXT);
        e.setTextSize(13);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(dp(9), dp(7), dp(9), dp(7));
        e.setText(dmgPrefs().getString(key, fallback));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(e, ep);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(5);
        parent.addView(row, rp);
        fields.add(e);
        return e;
    }

    private LinearLayout buildDmgPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Bảng sát thương", false, null));

        TextView hint = Ui.text(this,
                "Tính nhanh sát thương và tỷ lệ: hệ số kỹ năng, chí mạng, xuyên giáp, hồi chiêu.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(6);
        card.addView(hint, hp);

        List<EditText> fields = new ArrayList<>();
        final EditText atk = dmgField(card, "Sát thương gốc", "atk", "100", fields);
        final EditText ratio = dmgField(card, "Hệ số kỹ năng %", "ratio", "100", fields);
        final EditText critChance = dmgField(card, "Tỷ lệ chí mạng %", "crit", "0", fields);
        final EditText critDmg = dmgField(card, "ST chí mạng %", "critdmg", "50", fields);
        final EditText armor = dmgField(card, "Giáp địch", "armor", "0", fields);
        final EditText pen = dmgField(card, "Xuyên giáp %", "pen", "0", fields);
        final EditText cd = dmgField(card, "Hồi chiêu gốc (s)", "cd", "10", fields);
        final EditText cdr = dmgField(card, "Giảm hồi chiêu %", "cdr", "0", fields);

        final TextView output = Ui.text(this, "", 12, Ui.TEXT, false);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setBackground(Ui.framed(ITEM_BG_SOFT, this, 12));
        output.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(12);
        card.addView(output, op);

        final Runnable update = new Runnable() {
            public void run() {
                double a = dmgNum(atk, 100);
                double r = dmgNum(ratio, 100);
                double cc = dmgNum(critChance, 0);
                double cm = dmgNum(critDmg, 50);
                double ar = dmgNum(armor, 0);
                double p = dmgNum(pen, 0);
                double baseCd = dmgNum(cd, 10);
                double cr = dmgNum(cdr, 0);

                double base = a * r / 100.0;
                double effArmor = Math.max(0, ar * (1 - p / 100.0));
                double mit = 100.0 / (100.0 + effArmor);
                double normal = base * mit;
                double crit = normal * (1 + cm / 100.0);
                double avg = normal * (1 + (cc / 100.0) * (cm / 100.0));
                double realCd = Math.max(0, baseCd * (1 - Math.min(100, cr) / 100.0));

                output.setText("Giáp hiệu dụng: " + dmgFmt(effArmor) + "\n"
                        + "ST thường: " + dmgFmt(normal) + "\n"
                        + "ST chí mạng: " + dmgFmt(crit) + "\n"
                        + "ST trung bình: " + dmgFmt(avg) + "\n"
                        + "Hồi chiêu thực: " + dmgFmt(realCd) + " s");
            }
        };
        watchDmg(atk, "atk", update);
        watchDmg(ratio, "ratio", update);
        watchDmg(critChance, "crit", update);
        watchDmg(critDmg, "critdmg", update);
        watchDmg(armor, "armor", update);
        watchDmg(pen, "pen", update);
        watchDmg(cd, "cd", update);
        watchDmg(cdr, "cdr", update);
        update.run();

        TextView note = Ui.text(this,
                "Giáp giảm sát thương theo công thức 100/(100+giáp hiệu dụng) — chỉnh lại nếu game dùng công thức khác.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private void watchDmg(final EditText field, final String key, final Runnable update) {
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable e) {
                dmgPrefs().edit().putString(key, e.toString()).apply();
                update.run();
            }
        });
    }

    private static double dmgNum(EditText field, double fallback) {
        if (field == null) return fallback;
        String raw = field.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) return fallback;
        try {
            double v = Double.parseDouble(raw);
            if (Double.isNaN(v) || Double.isInfinite(v)) return fallback;
            return Math.max(0, v);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String dmgFmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "\u2014";
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    // ------------------------------------------------------------ BPM / tap tempo

    private final long[] bpmTimes = new long[16];
    private int bpmCount;
    private int bpmTarget = 120;
    private TextView bpmValue;
    private TextView bpmMs;
    private TextView bpmStatus;
    private TextView bpmTargetValue;

    private SharedPreferences bpmPrefs() {
        return getSharedPreferences("bpm", MODE_PRIVATE);
    }

    private void openBpm() {
        if (tabViews.containsKey(TAB_BPM)) return;
        bpmTarget = clamp(bpmPrefs().getInt("target", 120), 40, 240);
        bpmCount = 0;
        buildingTab = TAB_BPM;
        LinearLayout card = buildBpmPanel();
        buildingTab = null;
        addTab(TAB_BPM, card);
        paintBpm();
    }

    private LinearLayout buildBpmPanel() {
        LinearLayout card = glassCard();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.addView(header("Nhịp & BPM", false, null));

        TextView hint = Ui.text(this,
                "Chạm đều theo nhịp cần đo. Dùng để canh combo, cancel animation hoặc luyện giữ nhịp.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(2);
        hp.bottomMargin = dp(8);
        card.addView(hint, hp);

        bpmValue = Ui.text(this, "\u2014", 46, Ui.ACCENT, true);
        bpmValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        bpmValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(6);
        card.addView(bpmValue, bp);

        TextView unit = Ui.text(this, "BPM", 11, Ui.MUTED, true);
        unit.setGravity(Gravity.CENTER);
        unit.setLetterSpacing(0.16f);
        card.addView(unit);

        bpmMs = Ui.text(this, "Chạm ít nhất 2 lần", 12, Ui.MUTED, false);
        bpmMs.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(6);
        card.addView(bpmMs, mp);

        TextView tap = pill("CHẠM THEO NHỊP", true);
        tap.setTextSize(15);
        tap.setPadding(dp(10), dp(16), dp(10), dp(16));
        tap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tapBpm();
            }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(12);
        card.addView(tap, tp);

        card.addView(label("NHỊP MỤC TIÊU"));
        LinearLayout targetRow = Ui.row(this);
        TextView minus = pill("\u22125", false);
        minus.setPadding(dp(11), dp(7), dp(11), dp(7));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bpmTarget = clamp(bpmTarget - 5, 40, 240);
                bpmPrefs().edit().putInt("target", bpmTarget).apply();
                paintBpm();
            }
        });
        bpmTargetValue = Ui.text(this, "", 15, Ui.TEXT, true);
        bpmTargetValue.setGravity(Gravity.CENTER);
        TextView plus = pill("+5", false);
        plus.setPadding(dp(11), dp(7), dp(11), dp(7));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bpmTarget = clamp(bpmTarget + 5, 40, 240);
                bpmPrefs().edit().putInt("target", bpmTarget).apply();
                paintBpm();
            }
        });
        targetRow.addView(minus);
        targetRow.addView(bpmTargetValue, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targetRow.addView(plus);
        card.addView(targetRow);

        bpmStatus = Ui.text(this, "", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        card.addView(bpmStatus, sp);

        TextView reset = pill("Đo lại", false);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bpmCount = 0;
                paintBpm();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        card.addView(reset, rp);

        TextView note = Ui.text(this,
                "Nghỉ quá 3 giây sẽ tự bắt đầu chuỗi nhịp mới. Kết quả là trung bình các lần chạm gần nhất.",
                11, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        card.addView(note, np);
        return card;
    }

    private void tapBpm() {
        long now = SystemClock.elapsedRealtime();
        if (bpmCount > 0 && now - bpmTimes[bpmCount - 1] > 3000) bpmCount = 0;
        if (bpmCount == bpmTimes.length) {
            System.arraycopy(bpmTimes, 1, bpmTimes, 0, bpmTimes.length - 1);
            bpmCount--;
        }
        bpmTimes[bpmCount++] = now;
        paintBpm();
        if (bpmValue != null) Ui.pop(bpmValue);
    }

    private void paintBpm() {
        if (bpmTargetValue != null) bpmTargetValue.setText(bpmTarget + " BPM");
        if (bpmValue == null) return;
        if (bpmCount < 2) {
            bpmValue.setText("\u2014");
            if (bpmMs != null) bpmMs.setText("Chạm ít nhất 2 lần");
            if (bpmStatus != null) {
                bpmStatus.setText(bpmCount == 1 ? "Đã chạm 1 lần" : "Chưa đo");
                bpmStatus.setTextColor(Ui.MUTED);
            }
            return;
        }
        double avg = (bpmTimes[bpmCount - 1] - bpmTimes[0]) / (double) (bpmCount - 1);
        double bpm = 60000.0 / avg;
        bpmValue.setText(String.valueOf(Math.round(bpm)));
        if (bpmMs != null) bpmMs.setText(Math.round(avg) + " ms / nhịp");
        if (bpmStatus != null) {
            double targetMs = 60000.0 / bpmTarget;
            double diff = avg - targetMs;
            String sign = diff >= 0 ? "+" : "\u2212";
            bpmStatus.setText(bpmCount + " lần chạm · lệch " + sign
                    + Math.abs(Math.round(diff)) + " ms so với " + bpmTarget + " BPM");
            bpmStatus.setTextColor(Math.abs(diff) <= 30 ? TOGGLE_GREEN : Ui.MUTED);
        }
    }

    // ------------------------------------------------------------ ruler overlay

    private void startRuler() {
        if (rulerRoot != null) return;
        final RulerView ruler = new RulerView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(ruler, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView unit = pill("Đơn vị: dp", false);
        TextView close = pill("Đóng", true);
        unit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ruler.setPixels(!ruler.isPixels());
                unit.setText(ruler.isPixels() ? "Đơn vị: px" : "Đơn vị: dp");
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopRuler(true);
            }
        });

        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER);
        bar.addView(unit);
        LinearLayout.LayoutParams gap = Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        bar.addView(close, gap);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bp.bottomMargin = dp(26);
        root.addView(bar, bp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, wlp);
            rulerRoot = root;
            rulerView = ruler;
        } catch (Exception e) {
            toast("Không mở được thước đo");
            restoreHandleAfterCapture();
            return;
        }
        toast("Chạm để đặt tâm đo · lưới 10dp / 50dp");
    }

    private void stopRuler(boolean restore) {
        if (rulerRoot == null) return;
        try {
            wm.removeView(rulerRoot);
        } catch (Exception ignored) {
        }
        rulerRoot = null;
        rulerView = null;
        if (restore) {
            if (hudView != null) hudView.setVisibility(View.VISIBLE);
            restoreHandleAfterCapture();
            if (panel == null) openMenu();
        }
    }

    // ------------------------------------------------------------ black screen

    private void startBlackScreen() {
        if (blackRoot != null) return;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        root.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                stopBlackScreen(true);
                return true;
            }
        });

        final TextView hint = Ui.text(this, "Giữ lâu để thoát", 11, 0x40FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(46);
        root.addView(hint, hp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        wlp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, wlp);
            blackRoot = root;
        } catch (Exception e) {
            toast("Không bật được màn hình đen");
            restoreHandleAfterCapture();
            return;
        }
        hint.animate().alpha(0f).setStartDelay(2600).setDuration(700).start();
    }

    private void stopBlackScreen(boolean restore) {
        if (blackRoot == null) return;
        try {
            wm.removeView(blackRoot);
        } catch (Exception ignored) {
        }
        blackRoot = null;
        if (restore) {
            if (hudView != null) hudView.setVisibility(View.VISIBLE);
            restoreHandleAfterCapture();
            if (panel == null) openMenu();
        }
    }

    /** Height of a framework system bar in pixels, or 0 if unavailable. */
    private int systemBarHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
