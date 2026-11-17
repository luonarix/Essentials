package dev.local.essentials;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Copyright and licence notice, opened from the main screen footer. */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 20));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));

        card.addView(Ui.text(this, "Giấy phép & bản quyền", 15, Ui.TEXT, true));

        TextView app = Ui.text(this, "Essentials " + version(), 12, Ui.ACCENT, false);
        LinearLayout.LayoutParams ap = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(6);
        card.addView(app, ap);

        TextView copyright = Ui.text(this,
                "\u00a9 2026 Nguyễn Nhật Minh Hoàng\nAll rights reserved",
                13, Ui.TEXT, true);
        LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        card.addView(copyright, cp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = Ui.col(this);
        body.addView(paragraph("Mã nguồn và ứng dụng thuộc bản quyền của tác giả. "
                + "Việc công khai mã nguồn không đặt dự án vào phạm vi công cộng và "
                + "không cấp thêm quyền nào ngoài các quyền dưới đây."));
        body.addView(paragraph("Được phép:\n"
                + "\u2022 Đọc, nghiên cứu và đánh giá mã nguồn.\n"
                + "\u2022 Cài đặt và dùng bản chính thức, không chỉnh sửa, cho mục đích "
                + "cá nhân và phi thương mại trên thiết bị của bạn.\n"
                + "\u2022 Trích dẫn ngắn kèm ghi nguồn khi review hoặc thảo luận."));
        body.addView(paragraph("Không được phép khi chưa có văn bản đồng ý của tác giả:\n"
                + "\u2022 Sao chép, đăng lại, phân phối lại mã nguồn hoặc APK, dù chỉ một phần.\n"
                + "\u2022 Sửa đổi, chuyển thể, port sang nền tảng khác hoặc tạo sản phẩm "
                + "phái sinh.\n"
                + "\u2022 Đưa mã nguồn vào project, ứng dụng hay repository khác.\n"
                + "\u2022 Bán, cho thuê, cấp phép lại hoặc khai thác thương mại dưới bất kỳ "
                + "hình thức nào.\n"
                + "\u2022 Xoá hoặc sửa thông báo bản quyền."));
        body.addView(paragraph("GitHub cho phép fork repository công khai theo Điều khoản "
                + "dịch vụ của GitHub, nhưng bản fork vẫn phải tuân theo giấy phép này và "
                + "không được phát hành như một sản phẩm riêng, không được phân phối hay "
                + "dùng cho mục đích thương mại."));
        body.addView(paragraph("Phần mềm được cung cấp \"nguyên trạng\", không kèm bảo hành "
                + "dưới bất kỳ hình thức nào."));

        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        sp.topMargin = dp(12);
        card.addView(sv, sp);

        TextView close = Ui.text(this, "Đóng", 13, 0xFF0B1020, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.bg(Ui.ACCENT, this, 14));
        close.setPadding(dp(24), dp(10), dp(24), dp(10));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        Ui.press(close, 0.96f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        card.addView(close, bp);

        setContentView(card);
        getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView paragraph(String s) {
        TextView t = Ui.text(this, s, 12, Ui.MUTED, false);
        t.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        t.setLayoutParams(p);
        return t;
    }

    private String version() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
