# Trình đọc

App Android gọn nhẹ để **đọc** file Word (.docx), Excel (.xlsx) và PDF trên Samsung Galaxy Z Fold 8.
Không quảng cáo, không cần mạng, không thu thập dữ liệu.

## Tính năng
- Mở file là hiện **toàn màn hình**. Chạm một lần để hiện/ẩn thanh công cụ.
- Mở trực tiếp từ Zalo, Gmail, Google Drive, trình quản lý file ("Mở bằng" hoặc "Chia sẻ" → Trình đọc).
- PDF: cuộn mượt, phóng to bằng hai ngón tay; mở màn hình lớn tự hiện **2 trang cạnh nhau** (bấm "1 | 2 trang" để đổi).
- Word: giữ tiêu đề, chữ đậm/nghiêng, màu chữ, bảng, hình ảnh, danh sách đánh số.
- Excel: bảng cuộn ngang/dọc, giữ cố định tên cột và số hàng, chuyển trang tính bằng tab bên dưới, định dạng số và ngày kiểu Việt Nam.
- Ba chế độ nền: sáng, giấy (dịu mắt), tối.
- Danh sách file đã mở gần đây (giữ 30 file).

Chưa đọc được: file .doc/.xls định dạng cũ, PowerPoint, biểu đồ trong Excel.

## Cách ra file cài (APK) — chọn 1 trong 3 cách

**Cách 1 – Nhờ Claude Code trên máy tính:** giải nén thư mục này, mở Claude Code trong thư mục đó và gõ:
"Build file APK và cài lên điện thoại của tôi". (File CLAUDE.md đã có sẵn hướng dẫn cho Claude Code.)

**Cách 2 – Android Studio:** File → Open → chọn thư mục `TrinhDoc` → chờ tải xong →
Build → Build App Bundle(s) / APK(s) → Build APK(s). File ra ở `app/build/outputs/apk/debug/app-debug.apk`.

**Cách 3 – GitHub (không cần cài gì):** tạo kho (repository) mới trên GitHub, tải toàn bộ thư mục lên.
GitHub tự build; vào tab **Actions** → lần chạy mới nhất → tải **TrinhDoc-apk**.

## Cài lên điện thoại
Chép file `app-debug.apk` sang điện thoại (Zalo "Truyền file", cáp USB, Google Drive…), bấm mở,
cho phép "Cài ứng dụng không rõ nguồn gốc" khi được hỏi. Sau đó vào Cài đặt → Ứng dụng → Chọn ứng dụng mặc định
nếu muốn luôn mở PDF/Word/Excel bằng Trình đọc thay cho WPS.
