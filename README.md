# Car Tracker Server (Định Vị Ô Tô Cloud)

Backend **Java 17 + Spring Boot 4 + PostgreSQL** kết hợp giao diện **PWA iPhone / Web Dashboard / Bảng quản trị người bán (`/admin`)** và **cảnh báo Telegram 24/7**.

## Tính năng chính

- **Xác thực đa người dùng (`AuthController`, `AuthService`)**: băm mật khẩu `PBKDF2WithHmacSHA256`, token `HMAC-SHA256` 30 ngày, phân tách dữ liệu theo chủ xe (`USER` / `ADMIN`).
- **Quản trị thương mại (`/admin` -> `admin.html`)**: tạo xe, tự sinh mã kích hoạt (`CT-XXXXXX`), gia hạn theo tháng/năm (`planExpiresAt`), giới hạn tốc độ cảnh báo, khóa/mở tài khoản, đặt lại mật khẩu và copy tin nhắn bàn giao 1 chạm.
- **Giám sát thời gian thực (`/`, `index.html`)**: bản đồ Đường phố & Vệ tinh, hướng xe di chuyển (`bearing`), vị trí điện thoại đang cầm (`Vị trí của bạn`), lọc hành trình & thống kê theo ngày (`?date=yyyy-MM-dd`), tua lại lộ trình, khóa bãi đỗ chống trộm (`50m/100m/200m/500m`) kèm còi cảnh báo.
- **Cảnh báo Telegram 24/7 (`TelegramNotificationService`)**: gửi cảnh báo tức thì kèm link Google Maps khi xe rời vùng đỗ an toàn hoặc vượt tốc độ cho phép.
- **Thu thập vị trí GPS (`/update-location`, `/update-location/batch`)**: kiểm tra mã kích hoạt thiết bị và hạn gói cước, hỗ trợ gửi bù dữ liệu ngoại tuyến theo lô (`/xe.html` và ứng dụng Android).

## Triển khai miễn phí với Docker (Render / Railway / Koyeb + Neon/Supabase)

1. Tạo cơ sở dữ liệu PostgreSQL (ví dụ trên Neon hoặc Supabase).
2. Kết nối repository này với dịch vụ hỗ trợ `Dockerfile` (ví dụ Render Web Service).
3. Cấu hình biến môi trường:
   - `DB_URL`: chuỗi JDBC PostgreSQL (ví dụ `jdbc:postgresql://<host>:5432/<db>?sslmode=require`)
   - `DB_USER`: tài khoản PostgreSQL
   - `DB_PASS`: mật khẩu PostgreSQL
   - `APP_AUTH_SECRET`: chuỗi ký tự bí mật ngẫu nhiên (tối thiểu 32 ký tự)
   - `APP_ADMIN_USER`: tên đăng nhập Admin khởi tạo lần đầu
   - `APP_ADMIN_PASS`: mật khẩu Admin khởi tạo lần đầu
   - `PORT`: cổng ứng dụng (mặc định `8080`)
4. Kiểm thử cục bộ:
   ```bash
   ./mvnw test
   ```
