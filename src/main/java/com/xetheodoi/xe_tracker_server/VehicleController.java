package com.xetheodoi.xe_tracker_server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class VehicleController {

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private LocationHistoryRepository historyRepository;

    @Autowired(required = false)
    private AuthService authService;

    @Autowired(required = false)
    private TelegramNotificationService telegramService;

    @Autowired(required = false)
    private Jt808TcpServer jt808TcpServer;

    @Value("${app.apks.dir:D:\\CarTracker\\APKs_Xong}")
    private String apksDir;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double MIN_HISTORY_DISTANCE_KM = 0.012; // 12 mét để tránh nhiễu GPS khi đỗ

    // API để thiết bị GPS gửi vị trí lên (màn hình ô tô hoặc /xe.html)
    @PostMapping("/update-location")
    public ResponseEntity<Map<String, Object>> updateLocation(
            @RequestBody Vehicle incoming,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Map<String, Object> response = new HashMap<>();

        if (incoming.getVehicleId() == null || incoming.getVehicleId().trim().isEmpty()
                || incoming.getLatitude() == null || incoming.getLongitude() == null) {
            response.put("status", "error");
            response.put("message", "Thiếu thông tin vehicleId, latitude hoặc longitude");
            return ResponseEntity.badRequest().body(response);
        }

        String vId = incoming.getVehicleId().trim().toUpperCase();
        Optional<Vehicle> existingOpt = vehicleRepository.findByVehicleId(vId);
        Vehicle vehicle;

        if (existingOpt.isEmpty()) {
            // Chỉ tự động tạo xe mới nếu người gửi đang đăng nhập hợp lệ hoặc chạy unit test không có authService
            if (authService != null) {
                Optional<AppUser> callerOpt = authService.authenticateRequest(authHeader);
                if (callerOpt.isEmpty()) {
                    response.put("status", "not_registered");
                    response.put("message", "Mã xe " + vId + " chưa được tạo trên hệ thống. Vào trang Quản trị để tạo xe và cấp mã kích hoạt trước!");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                vehicle = new Vehicle();
                vehicle.setVehicleId(vId);
                vehicle.setVehicleName("Xe " + vId);
                vehicle.setOwnerId(callerOpt.get().getId());
                vehicle.setRequireActivationCode(false);
            } else {
                vehicle = new Vehicle();
                vehicle.setVehicleId(vId);
            }
        } else {
            vehicle = existingOpt.get();
            if (vehicle.isPlanExpired()) {
                response.put("status", "expired");
                response.put("message", "Gói cước theo dõi xe " + vId + " đã hết hạn (" + vehicle.getPlanExpiresAt() + "). Vui lòng gia hạn!");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            if (Boolean.TRUE.equals(vehicle.getRequireActivationCode())) {
                String expectedCode = vehicle.getActivationCode() != null ? vehicle.getActivationCode().trim().toUpperCase() : "";
                String sentCode = incoming.getActivationCode() != null ? incoming.getActivationCode().trim().toUpperCase() : "";
                if (expectedCode.isEmpty() || !expectedCode.equals(sentCode)) {
                    response.put("status", "unauthorized_device");
                    response.put("message", "Sai mã kích hoạt thiết bị cho xe " + vId + "!");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }
            }
        }

        boolean wasBreachedBefore = Boolean.TRUE.equals(vehicle.getGuardEnabled())
                && Boolean.TRUE.equals(vehicle.getGuardBreached());

        String nowStr = LocalDateTime.now().format(FORMATTER);
        double speed = incoming.getSpeed() != null ? Math.max(0.0, incoming.getSpeed()) : 0.0;

        vehicle.setLatitude(incoming.getLatitude());
        vehicle.setLongitude(incoming.getLongitude());
        vehicle.setSpeed(speed);
        if (incoming.getBearing() != null) {
            vehicle.setBearing(incoming.getBearing());
        }
        if (incoming.getAccOn() != null) {
            vehicle.setAccOn(incoming.getAccOn());
        } else if (speed > 2.0) {
            vehicle.setAccOn(true);
        }
        if (incoming.getCameraChannels() != null && incoming.getCameraChannels() >= 1 && incoming.getCameraChannels() <= 4) {
            vehicle.setCameraChannels(incoming.getCameraChannels());
        }
        vehicle.setLastUpdated(nowStr);
        evaluateGuardState(vehicle);
        vehicleRepository.save(vehicle);

        // Lọc nhiễu GPS khi xe đứng yên, chỉ lưu lịch sử khi xe di chuyển >= 12m (hoặc chưa có điểm nào)
        boolean savedToHistory = saveHistoryPointIfMoved(vId, incoming.getLatitude(), incoming.getLongitude(), speed, nowStr);

        if (telegramService != null) {
            telegramService.processTelemetryAlerts(vehicle, wasBreachedBefore);
        }

        response.put("status", "ok");
        response.put("vehicleId", vId);
        response.put("savedToHistory", savedToHistory);
        response.put("lastUpdated", nowStr);
        response.put("guardEnabled", vehicle.getGuardEnabled());
        response.put("guardRadius", vehicle.getGuardRadius());
        response.put("guardBreached", vehicle.getGuardBreached());
        response.put("guardDistanceMeters", vehicle.getGuardDistanceMeters());
        return ResponseEntity.ok(response);
    }

    /**
     * Tiếp nhận hàng loạt tọa độ được thiết bị ô tô lưu đệm lúc đi vào hầm mất sóng 4G.
     */
    @PostMapping("/update-location/batch")
    public ResponseEntity<Map<String, Object>> updateLocationBatch(@RequestBody List<Vehicle> points) {
        if (points == null || points.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Danh sách tọa độ trống"));
        }
        Vehicle first = points.get(0);
        if (first.getVehicleId() == null || first.getVehicleId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Thiếu vehicleId"));
        }

        String vId = first.getVehicleId().trim().toUpperCase();
        Optional<Vehicle> vehicleOpt = vehicleRepository.findByVehicleId(vId);
        if (vehicleOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "error", "message", "Không tìm thấy xe " + vId));
        }

        Vehicle vehicle = vehicleOpt.get();
        if (vehicle.isPlanExpired()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "expired", "message", "Gói cước đã hết hạn!"));
        }
        if (Boolean.TRUE.equals(vehicle.getRequireActivationCode())) {
            String expected = vehicle.getActivationCode() != null ? vehicle.getActivationCode().trim().toUpperCase() : "";
            String sent = first.getActivationCode() != null ? first.getActivationCode().trim().toUpperCase() : "";
            if (expected.isEmpty() || !expected.equals(sent)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "unauthorized_device", "message", "Sai mã kích hoạt thiết bị!"));
            }
        }

        int savedCount = 0;
        Vehicle latestValid = null;
        for (Vehicle pt : points) {
            if (pt.getLatitude() == null || pt.getLongitude() == null) continue;
            double spd = pt.getSpeed() != null ? Math.max(0.0, pt.getSpeed()) : 0.0;
            String ts = (pt.getLastUpdated() != null && !pt.getLastUpdated().isBlank())
                    ? pt.getLastUpdated().trim()
                    : LocalDateTime.now().format(FORMATTER);
            if (saveHistoryPointIfMoved(vId, pt.getLatitude(), pt.getLongitude(), spd, ts)) {
                savedCount++;
            }
            latestValid = pt;
        }

        boolean wasBreachedBefore = Boolean.TRUE.equals(vehicle.getGuardEnabled())
                && Boolean.TRUE.equals(vehicle.getGuardBreached());
        if (latestValid != null) {
            String ts = (latestValid.getLastUpdated() != null && !latestValid.getLastUpdated().isBlank())
                    ? latestValid.getLastUpdated().trim()
                    : LocalDateTime.now().format(FORMATTER);
            vehicle.setLatitude(latestValid.getLatitude());
            vehicle.setLongitude(latestValid.getLongitude());
            double batchSpd = latestValid.getSpeed() != null ? Math.max(0.0, latestValid.getSpeed()) : 0.0;
            vehicle.setSpeed(batchSpd);
            if (latestValid.getBearing() != null) vehicle.setBearing(latestValid.getBearing());
            if (latestValid.getAccOn() != null) {
                vehicle.setAccOn(latestValid.getAccOn());
            } else if (batchSpd > 2.0) {
                vehicle.setAccOn(true);
            }
            vehicle.setLastUpdated(ts);
            evaluateGuardState(vehicle);
            vehicleRepository.save(vehicle);
            if (telegramService != null) {
                telegramService.processTelemetryAlerts(vehicle, wasBreachedBefore);
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok", "savedPoints", savedCount));
    }

    private boolean saveHistoryPointIfMoved(String vId, double lat, double lng, double speed, String timestamp) {
        Optional<LocationHistory> lastOpt = historyRepository.findTopByVehicleIdOrderByIdDesc(vId);
        if (lastOpt.isEmpty()) {
            historyRepository.save(new LocationHistory(vId, lat, lng, speed, timestamp));
            return true;
        }
        LocationHistory last = lastOpt.get();
        double movedKm = calculateDistanceKm(last.getLatitude(), last.getLongitude(), lat, lng);
        if (movedKm >= MIN_HISTORY_DISTANCE_KM) {
            historyRepository.save(new LocationHistory(vId, lat, lng, speed, timestamp));
            return true;
        }
        return false;
    }

    private VehicleAccessResult authorizeVehicle(String vehicleId, String authHeader) {
        if (authService == null) {
            Optional<Vehicle> opt = vehicleRepository.findByVehicleId(vehicleId.trim().toUpperCase());
            return opt.map(VehicleAccessResult::ok).orElseGet(() -> VehicleAccessResult.error(HttpStatus.NOT_FOUND, "Không tìm thấy xe"));
        }
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return VehicleAccessResult.error(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập hết hạn, vui lòng đăng nhập lại!");
        }
        AppUser user = userOpt.get();
        Optional<Vehicle> opt = vehicleRepository.findByVehicleId(vehicleId.trim().toUpperCase());
        if (opt.isEmpty()) {
            return VehicleAccessResult.error(HttpStatus.NOT_FOUND, "Không tìm thấy xe " + vehicleId.trim().toUpperCase());
        }
        Vehicle v = opt.get();
        if (!user.isAdmin() && !Objects.equals(v.getOwnerId(), user.getId())) {
            return VehicleAccessResult.error(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập dữ liệu của xe này!");
        }
        return VehicleAccessResult.ok(v);
    }

    // API để app điện thoại lấy vị trí xe mới nhất
    @GetMapping("/get-location/{vehicleId}")
    public ResponseEntity<?> getLocation(
            @PathVariable String vehicleId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        VehicleAccessResult access = authorizeVehicle(vehicleId, authHeader);
        if (!access.allowed) {
            return ResponseEntity.status(access.status).body(Map.of("status", "error", "message", access.errorMessage));
        }
        Vehicle v = access.vehicle;
        evaluateGuardState(v);
        return ResponseEntity.ok(v);
    }

    // API Bật / Tắt / Đổi bán kính Khóa Bãi Đỗ Chống Trộm (đồng bộ qua DB)
    @PostMapping("/guard/{vehicleId}")
    public ResponseEntity<Map<String, Object>> updateGuard(
            @PathVariable String vehicleId,
            @RequestBody(required = false) GuardRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Map<String, Object> resp = new HashMap<>();
        String vId = vehicleId.trim().toUpperCase();
        if (vId.isEmpty()) {
            resp.put("status", "error");
            resp.put("message", "Thiếu mã định danh xe");
            return ResponseEntity.badRequest().body(resp);
        }

        if (authService != null) {
            Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
            }
            AppUser caller = userOpt.get();
            Optional<Vehicle> existingCheck = vehicleRepository.findByVehicleId(vId);
            if (existingCheck.isPresent() && !caller.isAdmin() && !Objects.equals(existingCheck.get().getOwnerId(), caller.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Không có quyền điều khiển xe này!"));
            }
        }

        Vehicle vehicle = vehicleRepository.findByVehicleId(vId).orElse(null);
        boolean enable = req != null && Boolean.TRUE.equals(req.getEnabled());

        if (enable) {
            int radius = sanitizeGuardRadius(req.getRadius() != null
                    ? req.getRadius()
                    : (vehicle != null ? vehicle.getGuardRadius() : null));
            boolean keepCenter = Boolean.TRUE.equals(req.getKeepCenter())
                    && vehicle != null
                    && vehicle.getGuardLat() != null
                    && vehicle.getGuardLng() != null;

            if (!keepCenter) {
                Double centerLat = (vehicle != null && vehicle.getLatitude() != null) ? vehicle.getLatitude() : req.getLat();
                Double centerLng = (vehicle != null && vehicle.getLongitude() != null) ? vehicle.getLongitude() : req.getLng();

                if (centerLat == null || centerLng == null) {
                    resp.put("status", "error");
                    resp.put("message", "Chưa xác định được vị trí xe để khóa!");
                    return ResponseEntity.badRequest().body(resp);
                }

                if (vehicle == null) {
                    vehicle = new Vehicle();
                    vehicle.setVehicleId(vId);
                    vehicle.setLatitude(centerLat);
                    vehicle.setLongitude(centerLng);
                    vehicle.setSpeed(0.0);
                    vehicle.setLastUpdated(LocalDateTime.now().format(FORMATTER));
                }

                vehicle.setGuardLat(centerLat);
                vehicle.setGuardLng(centerLng);
                vehicle.setGuardActivatedAt(LocalDateTime.now().format(FORMATTER));
            }

            vehicle.setGuardEnabled(true);
            vehicle.setGuardRadius(radius);
            evaluateGuardState(vehicle);
            vehicleRepository.save(vehicle);

            resp.put("status", "ok");
            resp.put("vehicle", vehicle);
            return ResponseEntity.ok(resp);
        }

        if (vehicle != null) {
            vehicle.setGuardEnabled(false);
            vehicle.setGuardLat(null);
            vehicle.setGuardLng(null);
            vehicle.setGuardActivatedAt(null);
            evaluateGuardState(vehicle);
            vehicleRepository.save(vehicle);
            resp.put("vehicle", vehicle);
        }

        resp.put("status", "ok");
        return ResponseEntity.ok(resp);
    }

    // API lấy lịch sử vết bánh xe (hỗ trợ lọc theo ngày ?date=yyyy-MM-dd)
    @GetMapping("/history/{vehicleId}")
    public ResponseEntity<?> getHistory(
            @PathVariable String vehicleId,
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        VehicleAccessResult access = authorizeVehicle(vehicleId, authHeader);
        if (!access.allowed) {
            return ResponseEntity.status(access.status).body(Map.of("status", "error", "message", access.errorMessage));
        }
        return ResponseEntity.ok(loadHistoryByDate(access.vehicle.getVehicleId(), date));
    }

    private List<LocationHistory> loadHistoryByDate(String vId, String date) {
        if (date != null && !date.trim().isEmpty()) {
            return historyRepository.findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc(vId, date.trim());
        }
        return historyRepository.findByVehicleIdOrderByIdAsc(vId);
    }

    // API xóa lịch sử hành trình khi cần
    @Transactional
    @DeleteMapping("/history/{vehicleId}")
    public ResponseEntity<Map<String, Object>> clearHistory(
            @PathVariable String vehicleId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        VehicleAccessResult access = authorizeVehicle(vehicleId, authHeader);
        if (!access.allowed) {
            return ResponseEntity.status(access.status).body(Map.of("status", "error", "message", access.errorMessage));
        }
        String vId = access.vehicle.getVehicleId();
        historyRepository.deleteByVehicleId(vId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("message", "Đã xóa toàn bộ lịch sử hành trình của xe " + vId);
        return ResponseEntity.ok(resp);
    }

    // API thống kê tổng quãng đường (km), tốc độ cao nhất & trung bình (hỗ trợ ?date=yyyy-MM-dd)
    @GetMapping("/stats/{vehicleId}")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable String vehicleId,
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        VehicleAccessResult access = authorizeVehicle(vehicleId, authHeader);
        if (!access.allowed) {
            return ResponseEntity.status(access.status).body(Map.of("status", "error", "message", access.errorMessage));
        }
        List<LocationHistory> points = loadHistoryByDate(access.vehicle.getVehicleId(), date);
        double totalKm = 0.0;
        double maxSpeed = 0.0;
        double movingSpeedSum = 0.0;
        int movingCount = 0;

        for (int i = 0; i < points.size(); i++) {
            LocationHistory p2 = points.get(i);
            if (i > 0) {
                LocationHistory p1 = points.get(i - 1);
                totalKm += calculateDistanceKm(p1.getLatitude(), p1.getLongitude(), p2.getLatitude(), p2.getLongitude());
            }
            if (p2.getSpeed() != null) {
                if (p2.getSpeed() > maxSpeed) {
                    maxSpeed = p2.getSpeed();
                }
                if (p2.getSpeed() > 2.0) {
                    movingSpeedSum += p2.getSpeed();
                    movingCount++;
                }
            }
        }

        double avgSpeed = movingCount > 0 ? (movingSpeedSum / movingCount) : 0.0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPoints", points.size());
        stats.put("totalKm", Math.round(totalKm * 100.0) / 100.0);
        stats.put("maxSpeedKmh", Math.round(maxSpeed * 10.0) / 10.0);
        stats.put("avgSpeedKmh", Math.round(avgSpeed * 10.0) / 10.0);
        return ResponseEntity.ok(stats);
    }

    // Khách hàng nhập Biển số + Mã kích hoạt để ghép xe đã mua vào tài khoản của mình
    @PostMapping("/vehicles/claim")
    public ResponseEntity<Map<String, Object>> claimVehicle(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Chưa bật dịch vụ tài khoản"));
        }
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "error", "message", "Vui lòng đăng nhập trước!"));
        }
        AppUser user = userOpt.get();

        String vId = body.get("vehicleId") != null ? body.get("vehicleId").trim().toUpperCase().replaceAll("\\s+", "") : "";
        String code = body.get("activationCode") != null ? body.get("activationCode").trim().toUpperCase() : "";
        String customName = body.get("vehicleName") != null ? body.get("vehicleName").trim() : "";

        if (vId.isEmpty() || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Vui lòng nhập đầy đủ Biển số / Mã xe và Mã kích hoạt!"));
        }

        Optional<Vehicle> existingOpt = vehicleRepository.findByVehicleId(vId);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "error", "message", "Không tìm thấy mã xe " + vId + " trên hệ thống. Hãy kiểm tra lại với người bán!"));
        }
        Vehicle v = existingOpt.get();
        String expectedCode = v.getActivationCode() != null ? v.getActivationCode().trim().toUpperCase() : "";
        if (expectedCode.isEmpty() || !expectedCode.equals(code)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Mã kích hoạt không chính xác!"));
        }

        v.setOwnerId(user.getId());
        if (!customName.isEmpty()) {
            v.setVehicleName(customName);
        }
        v = vehicleRepository.save(v);
        evaluateGuardState(v);
        return ResponseEntity.ok(Map.of("status", "ok", "vehicle", v, "message", "Đã kích hoạt và thêm xe " + vId + " vào tài khoản của bạn!"));
    }

    // Danh sách xe thuộc quyền quản lý của tài khoản đang đăng nhập
    @GetMapping("/vehicles")
    public ResponseEntity<?> getMyVehicles(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService == null) {
            List<Vehicle> list = vehicleRepository.findAll();
            list.forEach(this::evaluateGuardState);
            return ResponseEntity.ok(list);
        }
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Vui lòng đăng nhập để xem danh sách xe!"));
        }
        AppUser user = userOpt.get();
        List<Vehicle> vehicles = vehicleRepository.findByOwnerIdOrderByIdAsc(user.getId());
        vehicles.forEach(this::evaluateGuardState);
        return ResponseEntity.ok(vehicles);
    }

    // Công thức Haversine tính khoảng cách giữa 2 điểm GPS (km)
    private double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        return GeoUtils.calculateDistanceKm(lat1, lon1, lat2, lon2);
    }

    private int sanitizeGuardRadius(Integer radius) {
        return GeoUtils.sanitizeGuardRadius(radius);
    }

    void evaluateGuardState(Vehicle vehicle) {
        GeoUtils.evaluateGuardState(vehicle);
    }

    @GetMapping("/api/devices/jt808/status")
    public ResponseEntity<?> getJt808Status(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService != null && authService.authenticateRequest(authHeader).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        if (jt808TcpServer == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "listening", false, "port", 0, "activeConnections", 0));
        }
        return ResponseEntity.ok(jt808TcpServer.getStatusSnapshot());
    }

    private static class VehicleAccessResult {
        final boolean allowed;
        final HttpStatus status;
        final String errorMessage;
        final Vehicle vehicle;

        private VehicleAccessResult(boolean allowed, HttpStatus status, String errorMessage, Vehicle vehicle) {
            this.allowed = allowed;
            this.status = status;
            this.errorMessage = errorMessage;
            this.vehicle = vehicle;
        }

        static VehicleAccessResult ok(Vehicle v) {
            return new VehicleAccessResult(true, HttpStatus.OK, null, v);
        }

        static VehicleAccessResult error(HttpStatus status, String msg) {
            return new VehicleAccessResult(false, status, msg, null);
        }
    }

    public static class GuardRequest {
        private Boolean enabled;
        private Integer radius;
        private Boolean keepCenter;
        private Double lat;
        private Double lng;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }

        public Integer getRadius() { return radius; }
        public void setRadius(Integer radius) { this.radius = radius; }

        public Boolean getKeepCenter() { return keepCenter; }
        public void setKeepCenter(Boolean keepCenter) { this.keepCenter = keepCenter; }

        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }

        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
    }

    // API tải file APK trực tiếp từ trình duyệt màn hình ô tô (không cần USB)
    @GetMapping("/download/car.apk")
    public ResponseEntity<Resource> downloadCarApk() {
        return serveApk("1_CarTrackerAuto_CaiTrenManHinhOTo.apk", "CarTrackerAuto.apk");
    }

    @GetMapping("/download/mobile.apk")
    public ResponseEntity<Resource> downloadMobileApk() {
        return serveApk("2_CarTrackerMobile_CaiTrenDienThoai.apk", "CarTrackerMobile.apk");
    }

    private ResponseEntity<Resource> serveApk(String sourceName, String downloadName) {
        String baseDir = (apksDir != null && !apksDir.isBlank()) ? apksDir : "D:\\CarTracker\\APKs_Xong";
        Path apkPath = Paths.get(baseDir, sourceName);
        if (!Files.exists(apkPath)) {
            apkPath = Paths.get("/app/apks", sourceName);
        }
        if (!Files.exists(apkPath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(apkPath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .body(resource);
    }
}
