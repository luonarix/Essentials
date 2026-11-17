# Essentials

> Thanh bên nổi đa năng cho Android — viết thuần SDK Java, không root, không AndroidX, không quảng cáo, không kết nối internet.

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-8-ED8B00?logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/license-All%20Rights%20Reserved-red)

**Essentials** là một overlay sidebar chạy trên mọi ứng dụng Android. Chạm vào tay
nắm ở mép phải để mở **dock**: menu cố định một bên, các tab công cụ xếp dọc bên
cạnh và có thể mở nhiều tab cùng lúc. Toàn bộ giao diện, hiệu ứng và tiện ích được
viết bằng Java thuần trên SDK — không Gradle, không thư viện ngoài.

- **Tác giả:** Nguyễn Nhật Minh Hoàng
- **Nền tảng:** Android 7.0 (API 24) trở lên

---

## Tính năng

### Thanh bên & dock đa tab

- Tay nắm nổi ở mép phải, kéo dọc để đổi vị trí (tự lưu), chạm để mở/đóng dock.
- **Tự thu vào mép** sau 3 giây: thu còn một vạch mảnh, mờ 50%, vẫn kéo được.
- **Xoay màn hình**: thanh bên tự đặt lại về giữa; vạch thu vào mép giữ nguyên
  độ dày ở cả chiều dọc và chiều ngang.
- **Menu luôn hiện** khi dock mở. Mở công cụ không làm menu biến mất.
- **Menu cuộn một cột**: hiện 6 tiện ích, nhiều hơn thì lướt dọc — không còn
  chia thêm cột làm menu tràn ra ngoài ở chế độ ngang.
- **Nhấn giữ để đổi thứ tự**: giữ một tiện ích rồi kéo lên/xuống, thứ tự được
  lưu lại; nút **Sửa thanh bên** luôn ghim ở đáy menu, ngăn cách bằng một vạch
  ngang và đứng yên khi cuộn.
- **Nhiều tab cùng lúc**: Ghi chú, Ghi chú nhanh, Âm lượng, Chụp màn hình,
  Sửa thanh bên, Chi tiết ghi chú — xếp dọc trong cột cuộn được.
- Nút `×` chỉ đóng tab đó; nút menu của tab đang mở được **tô viền accent**.
- Menu tự ẩn sau 12 giây khi không có tab nào mở.
- Kính mờ 85%, khung accent 1dp trên mọi bề mặt, chuyển động trượt 220ms.

### Công cụ

| Nhóm | Công cụ |
|---|---|
| Chụp ảnh | Chụp toàn màn hình, chọn vùng trên khung hình đóng băng, lưu vào thư viện ảnh — **không popup quyền** |
| Âm thanh | Thanh âm lượng nhạc / chuông / báo thức; xoay vòng Chuông → Rung → Im lặng |
| Hệ thống | Bảng nguồn, kéo rèm thông báo, Cài đặt nhanh, Back / Home / Đa nhiệm |
| Tiện ích | Đèn pin, ghi chú nhanh, danh sách ghi chú, bấm giờ, quy đổi đơn vị |
| Đo & ngẫu nhiên | Thước đo lưới dp/px, bộ đếm lượt, xúc xắc / đồng xu / số ngẫu nhiên |
| Văn bản | Lịch sử clipboard, đoạn văn mẫu ghim sẵn, mã QR offline từ văn bản |
| Màn hình | HUD CPU / FPS / RAM / nhiệt độ, giữ sáng, khoá cảm ứng, soi màu Hex, màn hình đen |
| Chiến đấu | Tâm ngắm ảo, 4 hẹn giờ song song, khoá độ sáng, khoá xoay, Không làm phiền |
| Giao diện | Ripple lan từ đúng toạ độ chạm, phản hồi nhún, cascade nội dung, animation màn hình |

### Công cụ mới trong v8.0

- **Thước đo & lưới** (`#ruler`): phủ lưới 10dp / 50dp (hoặc 50px / 250px) lên
  toàn màn hình, có nhãn theo cạnh. Chạm để đặt tâm đo, hiện toạ độ dp và px
  cùng lúc. Nút **Đơn vị** đổi dp ↔ px, nút **Đóng** trả tay nắm và mở lại dock.
- **Bộ đếm & ngẫu nhiên** (`#tally`): bộ đếm lớn với `+1` / `−1` / `Đặt lại`,
  lưu qua các lần mở app; kèm xúc xắc 1–6, đồng xu Sấp/Ngửa và số ngẫu nhiên
  1–100.
- **Clipboard & đoạn mẫu** (`#clip`): lưu 12 đoạn vừa copy gần nhất và cho phép
  ghim sẵn câu lệnh, mã màu hay ký tự hay dùng. Chạm một dòng là chép ngay vào
  bộ nhớ đệm. Dữ liệu nằm cục bộ, không gửi đi đâu.
- **Màn hình đen** (`#black`): phủ một lớp đen tuyền `#000000` giữ cho app bên
  dưới vẫn chạy, giữ màn hình sáng để nghe nhạc hoặc chạy tiến trình dài mà
  không lộ sáng ban đêm. Giữ lâu ở bất kỳ đâu để thoát.
- **Mã QR offline** (`#qr`): dựng mã QR từ văn bản hoặc clipboard ngay trên máy,
  không cần mạng và không dùng thư viện ngoài. Hỗ trợ tới **2331 byte** (byte
  mode, mức sửa lỗi M, phiên bản 1–40); mã lớn được dựng ở luồng nền nên dock
  không bị đứng.

### Bản 8.1

- **Chụp vùng**: vùng chọn dùng toạ độ màn hình thật và được quy đổi theo kích
  thước ảnh chụp, sửa lỗi ảnh thành phẩm bị lệch lên trên.
- **QR**: nâng giới hạn từ 213 lên 2331 byte (phiên bản 1–40), vẫn mã hoá hoàn
  toàn offline.
- **Xoay màn hình**: thanh bên và tay nắm được tạo lại theo kích thước mới; vạch
  ẩn không còn dày hơn khi sang ngang.

### Công cụ mới trong v9.0

- **Tâm ngắm ảo** (`#crosshair`): overlay nhỏ nằm giữa mọi ứng dụng, không chặn
  thao tác chạm. Ba kiểu **chấm / chữ thập / vòng tròn**, 7 màu, chỉnh kích thước
  6–48dp, độ mờ 20–100% và lệch X/Y theo từng 2dp. Mọi thay đổi áp dụng ngay và
  được lưu lại cho lần mở sau.
- **Đa hẹn giờ** (`#timers`): 4 đồng hồ đếm ngược chạy song song trong một tab,
  mỗi đồng hồ chỉnh được thời lượng theo phút, bắt đầu / tạm dừng / đặt lại độc
  lập. Hết giờ có hai tiếng bíp nhẹ; bộ đếm vẫn chạy khi đóng tab hoặc mở thước
  đo, chụp màn hình, màn hình đen.
- **Khoá độ sáng** (`#brightness`): một chạm chuyển độ sáng hệ thống sang thủ
  công đúng mức đang dùng để cảm biến không tự làm tối màn hình; chạm lần nữa
  khôi phục chế độ và mức sáng trước đó.
- **Khoá xoay màn hình** (`#rotate`): một chạm khoá cứng hướng đang cầm (dọc
  hoặc ngang), chạm lần nữa bật lại tự động xoay.
- **Không làm phiền** (`#dnd`): bật chế độ ưu tiên của hệ thống để chặn thông
  báo nổi; chạm lần nữa trả về bình thường.
- **Trạng thái bật/tắt trên menu**: các nút HUD, tâm ngắm, khoá độ sáng, khoá
  xoay và Không làm phiền đổi màu ngay trên menu khi đang bật (xanh lá; tâm ngắm
  xanh ngọc; Không làm phiền màu cam), tắt thì trở lại màu mặc định.
- **HUD nhiệt độ**: HUD hiện thêm nhiệt độ pin. Từ **40°C** chữ chuyển màu cam,
  từ **42°C** chuyển đỏ để biết lúc máy bắt đầu bóp hiệu năng.

> Khoá độ sáng và khoá xoay cần quyền **Sửa cài đặt hệ thống**; Không làm phiền
> cần quyền **Truy cập chế độ Không làm phiền**. Lần đầu bấm, app sẽ mở đúng
> trang cài đặt để bật quyền.

### Bản 9.1

- **Menu cuộn thay vì chia cột**: khi có hơn 6 tiện ích, menu chỉ cao đúng 6
  nút và cuộn dọc, phần còn lại lướt tới. Cách này bỏ hẳn các cột phụ nên menu
  không còn rộng và tràn ra ngoài ở chế độ ngang; tab công cụ có thêm chỗ.
- **Đổi thứ tự tiện ích**: nhấn giữ một icon trong menu rồi kéo lên hoặc xuống,
  các nút còn lại tự dồn chỗ; thả tay là lưu ngay vào thanh bên. Kéo tới sát
  mép trên/dưới thì danh sách tự cuộn.
- **Nút thêm ghim ở đáy**: nút **Sửa thanh bên** nằm ngoài vùng cuộn, luôn đứng
  yên ở cuối menu, phía trên có một vạch ngang ngăn cách với danh sách tiện ích.
- **Chọn vùng chụp trên khung đóng băng**: bấm *Chọn vùng* là màn hình được
  chụp lại ngay lúc đó rồi mới hiện khung kéo chọn, nên ảnh không còn lệch với
  thứ đang nhìn thấy và không bị chụp lại lần hai sau khi chọn.

### Quyền riêng tư

- **Không có quyền `INTERNET`**: ứng dụng không thể gửi dữ liệu ra ngoài.
- Không analytics, không quảng cáo, không tài khoản.
- Ghi chú lưu cục bộ trong `SharedPreferences`; ảnh lưu trong thư viện ảnh của máy.

---

## Yêu cầu

- Android **7.0 (API 24)** trở lên.
- Quyền **Hiển thị trên ứng dụng khác** (`SYSTEM_ALERT_WINDOW`).
- Quyền thông báo (Android 13+) cho foreground service.
- **Trợ năng (Accessibility)** — bật thủ công một lần, dùng cho:
  chụp màn hình không popup, soi màu, và các thao tác
  `GLOBAL_ACTION` (nguồn, rèm, cài đặt nhanh, back/home/recents).
- Quyền Camera chỉ khi dùng đèn pin.

## Cách dùng

- Chạm tay nắm → dock mở; chạm lại hoặc chạm ra ngoài để đóng.
- Chạm một mục trong menu để mở tab hoặc chạy hành động.
- Tab đang mở có viền sáng; chạm lại nút đó để đóng tab.
- Kéo tay nắm để đổi vị trí; giữ nguyên sẽ tự thu vào mép sau 3 giây.
- Tab Chụp màn hình: chọn **Toàn màn hình** hoặc **Chọn vùng**;
  ảnh lưu ngay, không có hộp thoại xin quyền.

---

## Kiến trúc

| File | Vai trò |
|---|---|
| `MainActivity` | Màn hình chính: bật/tắt thanh bên, mở nhanh |
| `SidebarService` | Foreground service vẽ tay nắm, dock, tab, HUD và overlay |
| `Side` | Danh sách mục, vị trí tay nắm, cờ HUD / caffeine, migration |
| `ShotService` | `AccessibilityService`: chụp ảnh + `performGlobalAction` |
| `Shots` | Lưu ảnh vào thư viện ảnh |
| `Stats` | Đọc CPU, FPS, RAM cho HUD |
| `Notes` | Kho ghi chú JSON cục bộ |
| `Torch` | Điều khiển đèn flash |
| `AddShortcutActivity` | Thêm shortcut ứng dụng vào menu |
| `Snippets` | Lưu lịch sử clipboard và đoạn văn mẫu ghim sẵn |
| `RulerView` | Vẽ lưới đo dp/px và tâm đo trên overlay |
| `QrCode` | Bộ mã hoá QR thuần Java (byte mode, mức M, version 1–40) |
| `Crosshair`, `CrosshairView` | Cấu hình và vẽ tâm ngắm overlay |
| `AboutActivity` | Màn hình giấy phép và bản quyền |
| `NotesActivity`, `StopwatchActivity`, `ConverterActivity` | Công cụ toàn màn hình |
| `Ui`, `TouchFade` | Màu/khung, layout helper, ripple và animation |

- Không dùng AndroidX / Material / Gradle.
- Không dùng lambda (giới hạn `javac -source 8` với `android.jar`); dùng anonymous class.
- Build bằng `aapt2` → `javac` → `d8` → `zipalign` → `apksigner`.

## Build từ mã nguồn

Yêu cầu: JDK 21, `aapt2` + `zipalign` (arm64 static), `d8.jar` + `apksigner.jar`
(build-tools 36), `android.jar` API 35. Đường dẫn công cụ và thư mục output nằm ở
đầu `build.sh`.

```bash
./build.sh
```

Đổi `VERSION_NAME` / `VERSION_CODE` ở đầu `build.sh` trước khi phát hành.

> **Lưu ý bảo mật:** không commit keystore, mật khẩu ký app hay file APK đã build.
> Hãy dùng keystore riêng của bạn khi phát hành bản public.

## Giới hạn đã biết

- Cửa sổ `FLAG_SECURE` (ngân hàng, DRM) sẽ ra ảnh đen khi chụp / soi màu.
- `takeScreenshot` cách nhau tối thiểu ~333ms.
- Chế độ Im lặng có thể bị chính sách Không làm phiền chặn.
- `GLOBAL_ACTION_POWER_DIALOG` không phải thiết bị nào cũng hỗ trợ.
- Khoá cảm ứng chỉ chặn chạm của ứng dụng; cử chỉ thanh trạng thái / rèm của
  hệ thống vẫn hoạt động.
- Cờ giữ sáng màn hình trên overlay có thể bị một số ROM bỏ qua.
- Chưa hỗ trợ kéo-thả sắp xếp lại mục trong menu.

---

## Đóng góp

Issue và pull request đều được hoan nghênh. Khi sửa code, giữ đúng phong cách hiện
tại: Java 8, không lambda, không thư viện ngoài, toàn bộ UI tạo bằng code.

Bằng việc gửi issue hoặc pull request, bạn đồng ý cho tác giả sử dụng nội dung
đóng góp đó trong dự án mà không kèm điều kiện gì thêm.

## Giấy phép

**All rights reserved** — bản quyền thuộc về **Nguyễn Nhật Minh Hoàng**, không
phát hành theo giấy phép nguồn mở nào. Xem file [LICENSE](LICENSE) để biết văn
bản đầy đủ.

Được phép:

- Đọc, nghiên cứu và đánh giá mã nguồn.
- Cài đặt và dùng bản chính thức, không chỉnh sửa, cho mục đích cá nhân và
  phi thương mại trên thiết bị của bạn.
- Trích dẫn ngắn kèm ghi nguồn khi review hoặc thảo luận.

Không được phép khi chưa có văn bản đồng ý của tác giả:

- Sao chép, đăng lại, phân phối lại mã nguồn hoặc APK, dù chỉ một phần.
- Sửa đổi, chuyển thể, port sang nền tảng khác hoặc tạo sản phẩm phái sinh.
- Đưa mã nguồn vào project, ứng dụng hay repository khác.
- Bán, cho thuê, cấp phép lại hoặc khai thác thương mại dưới bất kỳ hình thức nào.
- Xoá hoặc sửa thông báo bản quyền.

Lưu ý: GitHub cho phép **fork** repository công khai theo Điều khoản dịch vụ của
GitHub, nhưng bản fork vẫn phải tuân theo giấy phép này và không được phát hành
như một sản phẩm riêng, không được phân phối hay dùng cho mục đích thương mại.

Copyright © 2026 **Nguyễn Nhật Minh Hoàng**. Mọi quyền được bảo lưu.

Trong ứng dụng, mục **Giấy phép & bản quyền** ở cuối màn hình chính mở bản tóm tắt
các quyền trên, kèm số phiên bản đang cài.

## Miễn trừ trách nhiệm

Đây là dự án cá nhân, không liên kết với Google hay bất kỳ nhà sản xuất thiết bị nào.
Bạn tự chịu trách nhiệm khi cài đặt và sử dụng. Một số tính năng phụ thuộc vào
phiên bản Android và chính sách của từng thiết bị.
