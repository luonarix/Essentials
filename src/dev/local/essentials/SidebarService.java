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
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
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

    /** Tab key -> the menu button that toggles it. */
    private static String tabButtonId(String key) {
        if (TAB_QUICKNOTE.equals(key)) return "#quicknote";
        if (TAB_NOTES.equals(key)) return "#notes";
        if (TAB_VOLUME.equals(key)) return "#volume";
        if (TAB_SHOT.equals(key)) return "#shot";
        return null;
    }

    // stateful buttons
    private ImageView torchIcon;
    private View torchBox;
    private int iconTint = Ui.ACCENT;
    private int boxTint = ITEM_BG_SOFT;

    private ImageView caffeineIcon;
    private View caffeineBox;
    private int caffeineIconTint = Ui.ACCENT;
    private int caffeineBoxTint = ITEM_BG_SOFT;
    private boolean keepAwake;

    // extra overlays
    private FrameLayout colorRoot;
    private Bitmap colorBitmap;
    private FrameLayout lockRoot;

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
        keepAwake = Side.caffeineOn(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundSafe();
        if (!addHandle()) {
            toast("Không tạo được thanh bên");
            stopSelf();
        } else {
            applyKeepAwake(keepAwake);
            scheduleTuck();
            if (Side.hudOn(this)) addHud(false);
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

    @Override
    public void onDestroy() {
        removeTouchLock(false);
        closeColorPicker(false);
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
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                chipWidthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        box.addView(handleChip, chipLp);

        // the chevron lives beside the chip, so it stays visible when the chip tucks
        chevron = new TextView(this);
        chevron.setText("\u2039");
        chevron.setTextColor(Ui.ACCENT);
        chevron.setTextSize(18);
        chevron.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(HANDLE_CHEVRON_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        clp.rightMargin = dp(HANDLE_CHEVRON_MARGIN_DP);
        box.addView(chevron, clp);

        box.setAlpha(HANDLE_A_NORMAL);

        handleParams = new WindowManager.LayoutParams(dp(HANDLE_W_DP), dp(HANDLE_H_NORMAL), TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (keepAwake ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0),
                PixelFormat.TRANSLUCENT);
        handleParams.gravity = Gravity.TOP | Gravity.END;
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
        final int fromChipMargin = ((FrameLayout.LayoutParams) handleChip.getLayoutParams())
                .rightMargin;
        final int toChipMargin = toTucked ? -overhang : 0;
        final int fromMargin = chevron == null ? 0
                : ((FrameLayout.LayoutParams) chevron.getLayoutParams()).rightMargin;
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
                    if (barW != chipWidthPx || lp.rightMargin != chipMargin) {
                        chipWidthPx = barW;
                        lp.width = barW;
                        lp.rightMargin = chipMargin;
                        handleChip.setLayoutParams(lp);
                    }
                }
                if (chevron != null) {
                    // the arrow stays visible, sliding left as the chip narrows
                    int margin = Math.round(fromMargin + (toMargin - fromMargin) * t);
                    FrameLayout.LayoutParams clp =
                            (FrameLayout.LayoutParams) chevron.getLayoutParams();
                    if (clp.rightMargin != margin) {
                        clp.rightMargin = margin;
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
     * Width of the tucked sliver. Everything is in dp so the physical size is
     * identical on every screen; on very wide panels it grows slightly, which
     * keeps the proportions readable. Never thinner than HANDLE_BAR_DP.
     */
    private int tuckBarPx() {
        int byWidth = Math.round(screenW() * 0.030f);
        return Math.max(dp(HANDLE_BAR_DP), byWidth);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------ panel plumbing

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
        panelOnRight = true;
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
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                dockTabWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.START;
        sp.leftMargin = dp(EDGE_INSET_DP);
        sp.topMargin = dp(24);
        root.addView(dockScroll, sp);
        dockScroll.setVisibility(tabViews.isEmpty() ? View.GONE : View.VISIBLE);

        LinearLayout menu = buildMenu();
        dockMenu = menu;

        // Measure before the window is shown so the card is already at its
        // final vertical position. Re-positioning it in a post used to make
        // the menu jump up from the bottom for one frame.
        menu.measure(View.MeasureSpec.makeMeasureSpec(screenW(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH(), View.MeasureSpec.AT_MOST));
        int menuW = menu.getMeasuredWidth();
        int menuH = menu.getMeasuredHeight();

        final FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.gravity = Gravity.TOP | Gravity.END;
        mp.rightMargin = dp(PANEL_INSET_DP);
        int center = handleParams.y + handleParams.height / 2;
        mp.topMargin = clamp(center - menuH / 2, dp(8),
                Math.max(dp(8), screenH() - menuH - dp(8)));
        // Off-screen to the right before the first frame, so the slide-in
        // never flashes at its final position.
        menu.setTranslationX(dp(340));
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
                    tab.setTranslationX(dp(-22));
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
        return Math.max(dp(210), Math.min(dp(312), screenW() - dp(206)));
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
            dockScroll.animate().alpha(0f).translationX(-dp(26))
                    .setDuration(150).start();
        }
        if (dockMenu != null) {
            final int travel = dockMenu.getWidth() > 0
                    ? dockMenu.getWidth() + dp(60) : dp(340);
            dockMenu.animate()
                    .translationX(travel)
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
                .setDuration(180)
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
            setDockFocusable(false);
        }

        if (dockTabs != null) {
            card.animate().alpha(0f).translationX(dp(-18))
                    .setDuration(130)
                    .withEndAction(new Runnable() {
                        public void run() {
                            if (dockTabs != null) dockTabs.removeView(card);
                        }
                    })
                    .start();
        }
        paintTabButton(key, false);
        if (TAB_EDITOR.equals(key)) rebuildDockMenu();
        if (tabViews.isEmpty() && dockScroll != null) {
            dockScroll.setVisibility(View.GONE);
            bumpAutoHide();
        }
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

    /** Maximum buttons per column; the menu grows sideways past that. */
    private static final int MENU_ROWS = 10;

    private LinearLayout buildMenu() {
        torchIcon = null;
        torchBox = null;
        caffeineIcon = null;
        caffeineBox = null;
        caffeineIconTint = Ui.ACCENT;
        caffeineBoxTint = ITEM_BG_SOFT;

        List<View> buttons = new ArrayList<>();
        menuButtons.clear();
        for (String id : Side.items(this)) {
            View b = itemButton(id);
            if (b != null) {
                buttons.add(b);
                menuButtons.put(id, b);
            }
        }

        View edit = iconButton(R.drawable.ic_edit, "Sửa thanh bên",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        toggleTab(TAB_EDITOR);
                    }
                }, ITEM_BG_SOFT);
        buttons.add(edit);

        LinearLayout card = glassCard();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout grid = Ui.row(this);
        grid.setGravity(Gravity.TOP);

        int total = buttons.size();
        int cols = Math.max(1, (total + MENU_ROWS - 1) / MENU_ROWS);
        int next = 0;
        for (int c = 0; c < cols; c++) {
            int remaining = total - next;
            int remainingCols = cols - c;
            int take = (remaining + remainingCols - 1) / remainingCols;

            LinearLayout col = Ui.col(this);
            for (int i = 0; i < take && next < total; i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(50), dp(50));
                p.bottomMargin = dp(6);
                col.addView(buttons.get(next++), p);
            }
            if (c == 0) {
                grid.addView(col);
            } else {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.leftMargin = dp(6);
                grid.addView(col, cp);
            }
        }
        card.addView(grid);

        paintCaffeine(false);
        for (String key : tabViews.keySet()) paintTabButton(key, true);
        return card;
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
            case "#menu":
                iconRes = R.drawable.ic_settings;
                break;
            default:
                return null;
        }

        return iconButton(iconRes, Side.labelOf(id), new View.OnClickListener() {
            public void onClick(View v) {
                perform(id);
            }
        }, ITEM_BG_SOFT);
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
    private boolean shotBusy;

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
            handler.postDelayed(new Runnable() {
                public void run() {
                    showShotPicker();
                }
            }, 260);
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

    private void saveShot(Bitmap bmp, Rect crop) {
        Bitmap out = bmp;
        if (crop != null) {
            Rect c = new Rect(crop);
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
        shotBusy = false;
        toast(path != null ? "Đã lưu: " + path : "Không lưu được ảnh");
        restoreAfterCapture();
    }

    private void showShotPicker() {
        final FrameLayout root = new FrameLayout(this);
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
                removeShotPicker();
                handler.postDelayed(new Runnable() {
                    public void run() {
                        grabShot(r);
                    }
                }, 350);
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

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
        if (pickRoot == null) return;
        try {
            wm.removeView(pickRoot);
        } catch (Exception ignored) {
        }
        pickRoot = null;
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
        hudHiding = false;
        applyKeepAwake(keepAwake);
        Stats.cpu(); // prime the /proc/stat delta
        hudView.setText(hudCompact ? "--% • -- • --"
                : "CPU --%  FPS --  RAM --");
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
        String c = cpu < 0 ? "--" : String.valueOf(cpu);
        if (hudCompact) {
            hudView.setText(c + "% \u2022 " + fps + " \u2022 " + ram);
        } else {
            hudView.setText("CPU " + c + "%  FPS " + fps + "  RAM " + ram);
        }
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
