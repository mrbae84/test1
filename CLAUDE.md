# Mera Office – app Android đọc Word, Excel, PDF

App cá nhân của anh Tuyển cho Samsung Galaxy Z Fold 8. Chỉ để XEM file, không quảng cáo,
không xin quyền Internet, không chỉnh sửa. Giao tiếp với anh Tuyển bằng tiếng Việt.

## Cấu trúc
- Kotlin thuần, không thư viện ngoài (chỉ Android SDK). minSdk 30, compileSdk/targetSdk 35.
- `MainActivity`: nút "Mở file" + danh sách gần đây (bản sao lưu trong `filesDir/recent`).
- `ReaderActivity`: WebView toàn màn hình; chạm 1 lần hiện/ẩn thanh công cụ; nền sáng/giấy/tối.
  Nội dung phục vụ qua host giả `https://reader.local/` bằng `shouldInterceptRequest`.
- `Content.kt`: nhận dạng loại file (sniff), `PdfContent` (PdfRenderer, render từng trang khi cuộn),
  `DocxContent`, `XlsxContent`, `Recents`.
- `DocxConverter.kt`, `XlsxConverter.kt`: tự đọc XML trong file .docx/.xlsx và sinh HTML.
- `Html.kt`: CSS/JS chung. PDF tự hiện 2 trang cạnh nhau khi mở màn hình lớn.

## Việc cần làm khi anh Tuyển yêu cầu
1. Build: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
   Cần JDK 17 và Android SDK (cài Android Studio là có; nếu thiếu, tạo `local.properties`
   với `sdk.dir=...`). File ra: `app/build/outputs/apk/debug/app-debug.apk`.
2. Cài lên điện thoại qua USB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (bật Tuỳ chọn nhà phát triển + Gỡ lỗi USB trên máy).
3. Mã chưa được biên dịch thử lần nào (viết ở môi trường không có Android SDK) – nếu build lỗi,
   sửa lỗi biên dịch trước, giữ nguyên thiết kế.

## Chưa hỗ trợ (có thể làm tiếp)
.doc/.xls định dạng cũ, PowerPoint, tìm kiếm chữ, biểu đồ trong Excel, chú thích cuối trang Word.
