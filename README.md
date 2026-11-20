# Car Tracker Server (Định Vị Ô Tô Cloud)

Backend **Java 17 + Spring Boot 4 + PostgreSQL** kết hợp giao diện **PWA iPhone / Web Dashboard / Bảng quản trị người bán (`/admin`)** và **cảnh báo Telegram 24/7**.

## Tính năng chính

- **Xác thực đa người dùng (`AuthController`, `AuthService`)**: băm mật khẩu `PBKDF2WithHmacSHA256`, token `HMAC-SHA256` 30 ngày, phân tách dữ liệu theo chủ xe (`USER` / `ADMIN`).
- **Quản trị thương mại (`/admin` -> `admin.html`)**: tạo xe, tự sinh mã kích hoạt (`CT-XXXXXX`), gắn `IMEI` (JT808), cấu hình số mắt camera (`1–4`), thư mục Google Drive, gia hạn gói tháng/năm, khóa/mở tài khoản và copy tin nhắn bàn giao.
- **Giám sát thời gian thực 6 tab (`/`, `index.html`)**: **Bảng điều khiển** | **Giám sát xe** (lọc `Tất cả / Trực tuyến / Ngoại tuyến / Cảnh báo`, trạng thái `ACC`, tìm đường ngắn nhất tới xe trực tiếp trên bản đồ qua OSRM, Google Maps / Apple Maps) | **Video 1–4 mắt & Google Drive** (HLS/HTTP-FLV/MP4 + phát preview link Google Drive) | **Hành trình** (lọc ngày + tua lại) | **Quản lý** | **Báo cáo** (xuất CSV/Excel).
- **Cổng thiết bị phần cứng `JT808` (`Jt808TcpServer`, `Jt808Codec`)**: mở cổng TCP `6808` (`JT808_PORT`) giải mã gói đăng ký `0x0100`, heartbeat `0x0002`, tọa độ/tốc độ/hướng/`ACC` `0x0200` từ cam/đầu ghi 4G chuẩn JT/T 808.
- **Kho Video & Báo cáo (`VideoAndReportController`)**: `/api/videos/{vehicleId}`, `/api/dashboard/summary`, `/api/reports/vehicles`, `/api/reports/export`.
- **Cảnh báo Telegram 24/7 (`TelegramNotificationService`)**: gửi cảnh báo tức thì kèm link Google Maps khi xe rời vùng đỗ an toàn hoặc vượt tốc độ cho phép.
- **Thu thập vị trí GPS (`/update-location`, `/update-location/batch`)**: kiểm tra mã kích hoạt thiết bị và hạn gói cước, hỗ trợ gửi bù dữ liệu ngoại tuyến theo lô (`/xe.html` và ứng dụng Android).

## Triển khai với Docker (Render / VPS + Neon/Supabase)

1. Tạo cơ sở dữ liệu PostgreSQL (ví dụ trên Neon hoặc Supabase).
2. Kết nối repository này với dịch vụ hỗ trợ `Dockerfile` (Render Web Service cho HTTP/Web, hoặc VPS nếu cần mở thêm cổng TCP `6808` cho phần cứng JT808).
3. Cấu hình biến môi trường:
   - `DB_URL`: chuỗi JDBC PostgreSQL (ví dụ `jdbc:postgresql://<host>:5432/<db>?sslmode=require`)
   - `DB_USER`: tài khoản PostgreSQL
   - `DB_PASS`: mật khẩu PostgreSQL
   - `APP_AUTH_SECRET`: chuỗi ký tự bí mật ngẫu nhiên (tối thiểu 32 ký tự)
   - `APP_ADMIN_USER`: tên đăng nhập Admin khởi tạo lần đầu
   - `APP_ADMIN_PASS`: mật khẩu Admin khởi tạo lần đầu
   - `PORT`: cổng HTTP web (mặc định `8080`)
   - `JT808_ENABLED` / `JT808_PORT`: bật/tắt và đổi cổng TCP cho cam `JT808` (mặc định `true` / `6808`)
4. Kiểm thử cục bộ:
   ```bash
   ./mvnw test
   ```
