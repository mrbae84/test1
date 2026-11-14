# Mera Office – app Android đọc Word, Excel, PDF

App cá nhân của anh Tuyển cho Samsung Galaxy Z Fold 8. Chỉ để XEM file, không quảng cáo,
không xin quyền Internet, không chỉnh sửa. Giao tiếp với anh Tuyển bằng tiếng Việt.

## Cấu trúc
- Kotlin thuần, không thư viện ngoài (chỉ Android SDK). minSdk 30, compileSdk/targetSdk 35.
- `MainActivity`: nút "Mở file" + danh sách gần đây (bản sao lưu trong `filesDir/recent`).
- `ReaderActivity`: WebView toàn màn hình; chạm giữa màn hình hiện/ẩn thanh công cụ; nút Aa mở bảng cài đặt.
  Nội dung phục vụ qua host giả `https://reader.local/` bằng `shouldInterceptRequest`.
  Cài đặt lưu trong SharedPreferences "settings" qua cầu nối JS `Reader.save(key, value)`.
- `assets/reader.css`, `assets/reader.js`: giao diện đọc, bảng cài đặt, 3 kiểu xem (scroll / slide / curl).
  Kiểu "Lật sách" vẽ nếp gấp bằng clip-path + ma trận phản chiếu, đi theo ngón tay, vuốt mạnh thì lật nhanh.
- `Content.kt`: nhận dạng loại file (sniff), `PdfContent` (PdfRenderer, render từng trang khi cuộn),
  `DocxContent`, `XlsxContent`, `Recents`.
- `DocxConverter.kt`, `XlsxConverter.kt`, `PptxConverter.kt`: tự đọc XML trong file Office và sinh HTML.
  PowerPoint: kế thừa layout/master, màu theme, fillRef/lnRef, bảng theo tableStyles.xml, SmartArt (bản vẽ dsp),
  hình tự do (custGeom + công thức), biểu đồ vẽ SVG từ số liệu lưu sẵn.
- `Html.kt`: khung trang, nhúng cài đặt (window.S) và loại file (window.KIND).
- Kiểm thử: `src/test/ConvertSamplesTest` (Robolectric) chuyển đổi các file trong `src/test/samples`
  (gồm file mẫu của Apache POI, giấy phép Apache 2.0) và ghi kết quả ra `build/converted/`.
  CI nhánh `trinhdoc-dev` đăng kết quả lên bản phát hành thử `dev-test`; nhánh `trinhdoc` đăng bản chính.

## Việc cần làm khi anh Tuyển yêu cầu
1. Build: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
   Cần JDK 17 và Android SDK (cài Android Studio là có; nếu thiếu, tạo `local.properties`
   với `sdk.dir=...`). File ra: `app/build/outputs/apk/debug/app-debug.apk`.
2. Cài lên điện thoại qua USB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (bật Tuỳ chọn nhà phát triển + Gỡ lỗi USB trên máy).
3. GitHub Actions (`.github/workflows/build-apk.yml`) tự build APK, chạy kiểm thử và đăng bản phát hành.

## Chưa hỗ trợ (có thể làm tiếp)
.doc/.xls/.ppt định dạng cũ, tìm kiếm chữ, biểu đồ trong Excel, chú thích cuối trang Word,
hiệu ứng/video PowerPoint, biểu đồ radar/bong bóng.
