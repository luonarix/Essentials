package dev.local.essentials;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 42;
    private static final int REQ_NOTIF = 43;

    private TextView sideStatus;
    private TextView sideButton;
    private ImageView flashIcon;
    private int flashTint = Ui.ACCENT;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 26), Ui.dp(this, 18), Ui.dp(this, 28));

        root.addView(Ui.text(this, "Essentials", 30, Ui.TEXT, true));

        TextView sub = Ui.text(this, today(), 13, Ui.MUTED, false);
        sub.setLayoutParams(Ui.lp());
        Ui.margin(this, sub, 0, 6, 0, 0);
        root.addView(sub);

        root.addView(buildSearchCard());

        root.addView(section("THANH BÊN"));
        root.addView(buildSideCard());

        root.addView(section("TÙY CHỈNH"));
        addLink(root, R.drawable.ic_settings, "Tính năng & dữ liệu", new View.OnClickListener() {
            public void onClick(View v) {
                open(FeaturesActivity.class);
            }
        });
        addLink(root, R.drawable.ic_lock, "Quyền hệ thống", new View.OnClickListener() {
            public void onClick(View v) {
                open(PermissionsActivity.class);
            }
        });

        root.addView(section("CÔNG CỤ"));

        LinearLayout row1 = Ui.row(this);
        addTile(row1, R.drawable.ic_note, "Ghi chú", new View.OnClickListener() {
            public void onClick(View v) {
                open(NotesActivity.class);
            }
        }, false);
        addTile(row1, R.drawable.ic_timer, "Bấm giờ", new View.OnClickListener() {
            public void onClick(View v) {
                open(StopwatchActivity.class);
            }
        }, true);
        root.addView(row1, toolsRow(false));

        LinearLayout row2 = Ui.row(this);
        addTile(row2, R.drawable.ic_convert, "Quy đổi", new View.OnClickListener() {
            public void onClick(View v) {
                open(ConverterActivity.class);
            }
        }, false);
        flashIcon = addTile(row2, R.drawable.ic_flash, "Đèn pin", new View.OnClickListener() {
            public void onClick(View v) {
                toggleTorch();
            }
        }, true);
        root.addView(row2, toolsRow(true));

        LinearLayout row3 = Ui.row(this);
        addTile(row3, R.drawable.ic_camera, "Chụp màn hình", new View.OnClickListener() {
            public void onClick(View v) {
                openCaptureTab();
            }
        }, true);
        root.addView(row3, toolsRow(true));

        root.addView(section("MỞ NHANH"));

        addLink(root, R.drawable.ic_camera, "Máy ảnh", new View.OnClickListener() {
            public void onClick(View v) {
                launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            }
        });
        addLink(root, R.drawable.ic_phone, "Gọi điện", new View.OnClickListener() {
            public void onClick(View v) {
                launch(new Intent(Intent.ACTION_DIAL));
            }
        });
        addLink(root, R.drawable.ic_web, "Trình duyệt", new View.OnClickListener() {
            public void onClick(View v) {
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")));
            }
        });
        addLink(root, R.drawable.ic_settings, "Cài đặt", new View.OnClickListener() {
            public void onClick(View v) {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        TextView author = Ui.text(this, "Developer: " + getString(R.string.developer),
                11, Ui.MUTED, false);
        author.setGravity(Gravity.CENTER_HORIZONTAL);
        author.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams ap = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 26);
        author.setLayoutParams(ap);
        root.addView(author);

        TextView licence = Ui.text(this, "Giấy phép & bản quyền \u203a",
                11, Ui.ACCENT, true);
        licence.setGravity(Gravity.CENTER_HORIZONTAL);
        licence.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        licence.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(AboutActivity.class);
            }
        });
        Ui.press(licence, 0.96f);
        root.addView(licence);

        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        Shortcuts.sync(this);
        Ui.stagger(root, 40, 26);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Side.on(this) && canOverlay() && !SidebarService.running()) {
            startSidebar(false);
        }
        refreshSide();
        paintFlash(false);
    }

    // ------------------------------------------------------------ sidebar

    private boolean canOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private View buildSearchCard() {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_quick);
        icon.setColorFilter(Ui.ACCENT);
        card.addView(icon, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));

        TextView hint = Ui.text(this, "Tìm công cụ...", 15, Ui.MUTED, false);
        LinearLayout.LayoutParams hp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = Ui.dp(this, 12);
        card.addView(hint, hp);

        card.addView(Ui.text(this, "Tìm \u203a", 12, Ui.ACCENT, true));

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(SearchActivity.class);
            }
        });
        card.setClickable(true);
        Ui.press(card, 0.98f);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 18);
        card.setLayoutParams(p);
        return card;
    }

    private View buildSideCard() {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 18));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));

        LinearLayout col = Ui.col(this);
        col.addView(Ui.text(this, "Thanh bên nổi", 15, Ui.TEXT, true));
        sideStatus = Ui.text(this, "", 12, Ui.MUTED, false);
        sideStatus.setLayoutParams(Ui.lp());
        Ui.margin(this, sideStatus, 0, 4, 0, 0);
        col.addView(sideStatus);
        card.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sideButton = Ui.text(this, "Bật", 13, 0xFF0B1020, true);
        sideButton.setGravity(Gravity.CENTER);
        sideButton.setBackground(Ui.bg(Ui.ACCENT, this, 18));
        sideButton.setPadding(Ui.dp(this, 20), Ui.dp(this, 9), Ui.dp(this, 20), Ui.dp(this, 9));
        // The whole card is the tap target; a second listener on the pill would
        // start two ripples for one press.
        card.addView(sideButton);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onSideClick();
            }
        });
        card.setClickable(true);
        Ui.press(card, 0.98f);
        return card;
    }

    private void onSideClick() {
        if (Side.on(this) && SidebarService.running()) {
            stopService(new Intent(this, SidebarService.class));
            Side.setOn(this, false);
            toast("Đã tắt thanh bên");
            refreshSide();
            Ui.pop(sideButton);
            return;
        }
        if (!canOverlay()) {
            toast("Cấp quyền \"Hiển thị trên ứng dụng khác\" rồi bấm lại");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            return;
        }
        startSidebar(true);
    }

    private void startSidebar(boolean announce) {
        Side.setOn(this, true);
        Intent i = new Intent(this, SidebarService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("Không khởi động được thanh bên");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        refreshSide();
        if (sideButton != null) Ui.pop(sideButton);
        if (announce) toast("Đã bật thanh bên — kéo mép phải để mở");
    }

    private void refreshSide() {
        if (sideStatus == null || sideButton == null) return;
        boolean on = Side.on(this) && SidebarService.running();
        if (on) {
            sideStatus.setText("Đang bật · thanh bên ở mép phải");
            sideButton.setText("Tắt");
        } else if (!canOverlay()) {
            sideStatus.setText("Cần quyền hiển thị trên ứng dụng khác");
            sideButton.setText("Cấp quyền");
        } else {
            sideStatus.setText("Đang tắt");
            sideButton.setText("Bật");
        }
    }

    private void openCaptureTab() {
        if (!canOverlay()) {
            toast("Cấp quyền ứng dụng hiển thị trên ứng dụng khác rồi bấm lại");
            return;
        }
        Side.setOn(this, true);
        SidebarService.requestShot(this);
        refreshSide();
    }

    // ------------------------------------------------------------ torch

    private void toggleTorch() {
        if (Torch.id(this) == null) {
            toast("Máy không có đèn flash");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (!Torch.toggle(this)) toast("Không bật được đèn pin");
        paintFlash(true);
    }

    /** Smoothly blends the torch icon between blue and amber. */
    private void paintFlash(boolean animate) {
        if (flashIcon == null) return;
        final int to = Torch.isOn() ? 0xFFFFD166 : Ui.ACCENT;
        if (!animate) {
            flashTint = to;
            flashIcon.setColorFilter(to);
            return;
        }
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), flashTint, to);
        a.setDuration(200);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                flashTint = (Integer) va.getAnimatedValue();
                if (flashIcon != null) flashIcon.setColorFilter(flashTint);
            }
        });
        a.start();
        Ui.pop(flashIcon);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            toggleTorch();
        }
    }

    // ------------------------------------------------------------ ui bits

    private String today() {
        String s = new SimpleDateFormat("EEEE, d 'tháng' M", new Locale("vi", "VN"))
                .format(new Date());
        if (!s.isEmpty()) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return s;
    }

    private TextView section(String label) {
        TextView t = Ui.text(this, label, 11, Ui.MUTED, true);
        t.setLetterSpacing(0.18f);
        t.setLayoutParams(Ui.lp());
        Ui.margin(this, t, 2, 26, 0, 10);
        return t;
    }

    private LinearLayout.LayoutParams toolsRow(boolean gapAbove) {
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gapAbove) p.topMargin = Ui.dp(this, 12);
        return p;
    }

    private ImageView addTile(LinearLayout row, int iconRes, String label,
                              View.OnClickListener click, boolean last) {
        LinearLayout tile = Ui.col(this);
        tile.setBackground(Ui.framed(Ui.SURFACE, this, 20));
        tile.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        tile.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.bg(Ui.ACCENT_SOFT, this, 15));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.ACCENT);
        box.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER));
        tile.addView(box, Ui.lp(Ui.dp(this, 46), Ui.dp(this, 46)));

        TextView name = Ui.text(this, label, 14, Ui.TEXT, false);
        name.setLayoutParams(Ui.lp());
        Ui.margin(this, name, 0, 12, 0, 0);
        tile.addView(name);

        tile.setOnClickListener(click);
        tile.setClickable(true);
        Ui.press(tile);

        LinearLayout.LayoutParams p = Ui.lpw(1f, Ui.dp(this, 118));
        if (!last) p.rightMargin = Ui.dp(this, 12);
        row.addView(tile, p);
        return icon;
    }

    private void addLink(LinearLayout root, int iconRes, String label, View.OnClickListener click) {
        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.framed(Ui.SURFACE, this, 16));
        r.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 14), 0);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.ACCENT);
        r.addView(icon, Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));

        TextView name = Ui.text(this, label, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams np = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = Ui.dp(this, 14);
        name.setLayoutParams(np);
        r.addView(name);

        r.addView(Ui.text(this, "\u203A", 20, Ui.MUTED, false));

        r.setOnClickListener(click);
        r.setClickable(true);
        Ui.press(r, 0.98f);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58));
        p.topMargin = Ui.dp(this, 8);
        root.addView(r, p);
    }

    private void open(Class<?> c) {
        startActivity(new Intent(this, c));
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Máy không có ứng dụng phù hợp");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
