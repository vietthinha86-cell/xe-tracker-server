package com.xetheodoi.xe_tracker_server;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Gửi cảnh báo chống trộm / vượt tốc độ trực tiếp về Telegram iPhone của chủ xe (0đ).
 */
@Service
public class TelegramNotificationService {

    public static final String KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token";
    private static final long ALERT_COOLDOWN_MS = 60_000L;

    private final AppSettingRepository settingRepository;
    private final AppUserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VehicleLoanRepository loanRepository;

    public TelegramNotificationService(
            AppSettingRepository settingRepository,
            AppUserRepository userRepository,
            VehicleRepository vehicleRepository
    ) {
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String getBotToken() {
        Optional<AppSetting> opt = settingRepository.findById(KEY_TELEGRAM_BOT_TOKEN);
        if (opt.isPresent() && opt.get().getSettingValue() != null && !opt.get().getSettingValue().trim().isEmpty()) {
            return opt.get().getSettingValue().trim();
        }
        String env = System.getenv("TELEGRAM_BOT_TOKEN");
        return env != null ? env.trim() : "";
    }

    public boolean isConfigured() {
        return !getBotToken().isEmpty();
    }

    public void processTelemetryAlerts(Vehicle vehicle, boolean wasBreachedBefore) {
        String token = getBotToken();
        if (token.isEmpty() || vehicle.getOwnerId() == null) return;

        Optional<AppUser> ownerOpt = userRepository.findById(vehicle.getOwnerId());
        if (ownerOpt.isEmpty()) return;
        AppUser owner = ownerOpt.get();
        String chatId = owner.getTelegramChatId();
        if (chatId == null || chatId.trim().isEmpty()) return;

        long nowMs = System.currentTimeMillis();
        String label = (vehicle.getVehicleName() != null && !vehicle.getVehicleName().isBlank())
                ? vehicle.getVehicleId() + " (" + vehicle.getVehicleName() + ")"
                : vehicle.getVehicleId();

        String borrowerLine = "";
        if (loanRepository != null) {
            Optional<VehicleLoan> activeLoan = loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc(
                    vehicle.getVehicleId(), VehicleLoan.STATUS_ACTIVE);
            if (activeLoan.isPresent()) {
                VehicleLoan loan = activeLoan.get();
                String phoneSuffix = (loan.getBorrowerPhone() != null && !loan.getBorrowerPhone().isBlank())
                        ? " (" + loan.getBorrowerPhone().trim() + ")"
                        : "";
                borrowerLine = "👤 Người đang mượn: " + loan.getBorrowerName() + phoneSuffix + "\n";
            }
        }

        // 1. Cảnh báo vượt rào Khóa Bãi Đỗ Chống Trộm (ngay lúc vừa vượt hoặc lặp lại mỗi 60s)
        if (Boolean.TRUE.equals(vehicle.getGuardEnabled()) && Boolean.TRUE.equals(vehicle.getGuardBreached())) {
            long lastSent = vehicle.getLastTelegramAlertEpochMs() != null ? vehicle.getLastTelegramAlertEpochMs() : 0L;
            if (!wasBreachedBefore || (nowMs - lastSent) >= ALERT_COOLDOWN_MS) {
                vehicle.setLastTelegramAlertEpochMs(nowMs);
                vehicleRepository.save(vehicle);
                long dist = Math.round(vehicle.getGuardDistanceMeters() != null ? vehicle.getGuardDistanceMeters() : 0.0);
                int radius = vehicle.getGuardRadius() != null ? vehicle.getGuardRadius() : 100;
                double speed = vehicle.getSpeed() != null ? vehicle.getSpeed() : 0.0;
                String msg = String.format(
                        "🚨 CẢNH BÁO CHỐNG TRỘM XE %s!\n"
                                + "%s"
                                + "Xe đã rời khỏi vùng khóa an toàn: cách tâm %dm (giới hạn %dm).\n"
                                + "Vận tốc hiện tại: %.1f km/h\n"
                                + "📍 Xem vị trí trên Google Maps:\nhttps://maps.google.com/?q=%.6f,%.6f",
                        label, borrowerLine, dist, radius, speed, vehicle.getLatitude(), vehicle.getLongitude()
                );
                sendAsync(token, chatId.trim(), msg);
            }
        }

        // 2. Cảnh báo vượt ngưỡng tốc độ
        int speedLimit = vehicle.getMaxSpeedAlertKmh() != null && vehicle.getMaxSpeedAlertKmh() > 0
                ? vehicle.getMaxSpeedAlertKmh() : 90;
        if (vehicle.getSpeed() != null && vehicle.getSpeed() > speedLimit) {
            long lastSpeedSent = vehicle.getLastSpeedAlertEpochMs() != null ? vehicle.getLastSpeedAlertEpochMs() : 0L;
            if ((nowMs - lastSpeedSent) >= 120_000L) {
                vehicle.setLastSpeedAlertEpochMs(nowMs);
                vehicleRepository.save(vehicle);
                String msg = String.format(
                        "⚠️ CẢNH BÁO TỐC ĐỘ XE %s!\n"
                                + "%s"
                                + "Xe đang chạy %.1f km/h (vượt ngưỡng %d km/h).\n"
                                + "📍 https://maps.google.com/?q=%.6f,%.6f",
                        label, borrowerLine, vehicle.getSpeed(), speedLimit, vehicle.getLatitude(), vehicle.getLongitude()
                );
                sendAsync(token, chatId.trim(), msg);
            }
        }
    }

    public Map<String, Object> sendTestMessage(String chatId, String userName) {
        Map<String, Object> result = new HashMap<>();
        String token = getBotToken();
        if (token.isEmpty()) {
            result.put("ok", false);
            result.put("message", "Admin chưa cấu hình Telegram Bot Token trong /admin!");
            return result;
        }
        if (chatId == null || chatId.trim().isEmpty()) {
            result.put("ok", false);
            result.put("message", "Bạn chưa nhập Telegram Chat ID!");
            return result;
        }

        String text = "✅ Kết nối thành công Car Tracker!\n"
                + "Chủ xe: " + userName + "\n"
                + "Từ nay nếu xe rời khỏi vùng khóa chống trộm hoặc vượt tốc độ, Telegram sẽ báo động ngay kể cả khi khóa màn hình iPhone.";
        return sendSync(token, chatId.trim(), text);
    }

    private void sendAsync(String token, String chatId, String text) {
        CompletableFuture.runAsync(() -> sendSync(token, chatId, text));
    }

    private Map<String, Object> sendSync(String token, String chatId, String text) {
        Map<String, Object> result = new HashMap<>();
        try {
            String form = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                result.put("ok", true);
                result.put("message", "Đã gửi tin nhắn kiểm tra tới Telegram thành công!");
            } else {
                result.put("ok", false);
                result.put("message", "Telegram báo lỗi (" + response.statusCode() + "): hãy kiểm tra bạn đã bấm /start với Bot chưa.");
            }
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "Không gọi được Telegram API: " + e.getMessage());
        }
        return result;
    }
}

