package com.xetheodoi.xe_tracker_server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/admin")
public class AdminController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._@-]{3,40}$");

    private final AppUserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final LocationHistoryRepository historyRepository;
    private final AppSettingRepository settingRepository;
    private final AuthService authService;
    private final TelegramNotificationService telegramService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VideoClipRepository videoClipRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VehicleLoanRepository vehicleLoanRepository;
    private final SecureRandom random = new SecureRandom();

    public AdminController(
            AppUserRepository userRepository,
            VehicleRepository vehicleRepository,
            LocationHistoryRepository historyRepository,
            AppSettingRepository settingRepository,
            AuthService authService,
            TelegramNotificationService telegramService
    ) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.historyRepository = historyRepository;
        this.settingRepository = settingRepository;
        this.authService = authService;
        this.telegramService = telegramService;
    }

    private ResponseEntity<Map<String, Object>> forbidIfNotAdmin(String authHeader) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        if (!userOpt.get().isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Chỉ quản trị viên (ADMIN) mới có quyền này!"));
        }
        return null;
    }

    private String generateActivationCode() {
        StringBuilder sb = new StringBuilder("CT-");
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        List<AppUser> allUsers = userRepository.findAllByOrderByIdAsc();
        Map<Long, String> userNameMap = new HashMap<>();
        List<Map<String, Object>> userDtos = new ArrayList<>();
        for (AppUser u : allUsers) {
            userNameMap.put(u.getId(), (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() + " (" + u.getUsername() + ")"
                    : u.getUsername());
            userDtos.add(u.toPublicMap());
        }

        List<Vehicle> allVehicles = vehicleRepository.findAllByOrderByIdAsc();
        List<Map<String, Object>> vehicleDtos = new ArrayList<>();
        int expiredCount = 0;
        int breachedCount = 0;
        for (Vehicle v : allVehicles) {
            boolean expired = v.isPlanExpired();
            if (expired) expiredCount++;
            if (Boolean.TRUE.equals(v.getGuardEnabled()) && Boolean.TRUE.equals(v.getGuardBreached())) {
                breachedCount++;
            }
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("id", v.getId());
            vm.put("vehicleId", v.getVehicleId());
            vm.put("vehicleName", v.getVehicleName() != null ? v.getVehicleName() : v.getVehicleId());
            vm.put("ownerId", v.getOwnerId());
            vm.put("ownerLabel", v.getOwnerId() != null ? userNameMap.getOrDefault(v.getOwnerId(), "Không rõ") : "Chưa gán");
            vm.put("activationCode", v.getActivationCode() != null ? v.getActivationCode() : "");
            vm.put("requireActivationCode", Boolean.TRUE.equals(v.getRequireActivationCode()));
            vm.put("planExpiresAt", v.getPlanExpiresAt() != null ? v.getPlanExpiresAt() : "");
            vm.put("expired", expired);
            vm.put("lastUpdated", v.getLastUpdated() != null ? v.getLastUpdated() : "Chưa có tín hiệu");
            vm.put("accOn", Boolean.TRUE.equals(v.getAccOn()));
            vm.put("imei", v.getImei() != null ? v.getImei() : "");
            vm.put("cameraChannels", v.getCameraChannels() != null ? v.getCameraChannels() : 2);
            vm.put("streamBaseUrl", v.getStreamBaseUrl() != null ? v.getStreamBaseUrl() : "");
            vm.put("googleDriveFolderUrl", v.getGoogleDriveFolderUrl() != null ? v.getGoogleDriveFolderUrl() : "");
            vm.put("guardEnabled", Boolean.TRUE.equals(v.getGuardEnabled()));
            vm.put("guardBreached", Boolean.TRUE.equals(v.getGuardBreached()));
            vm.put("maxSpeedAlertKmh", v.getMaxSpeedAlertKmh() != null ? v.getMaxSpeedAlertKmh() : 90);
            vehicleDtos.add(vm);
        }

        String token = telegramService.getBotToken();
        String maskedToken = token.length() > 10
                ? token.substring(0, 6) + "..." + token.substring(token.length() - 4)
                : (!token.isEmpty() ? "***" : "");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("users", userDtos);
        resp.put("vehicles", vehicleDtos);
        resp.put("stats", Map.of(
                "totalUsers", allUsers.size(),
                "totalVehicles", allVehicles.size(),
                "expiredVehicles", expiredCount,
                "breachedVehicles", breachedCount
        ));
        resp.put("telegramConfigured", !token.isEmpty());
        resp.put("telegramMaskedToken", maskedToken);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AdminUserRequest req
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        String username = req.getUsername() != null ? req.getUsername().trim().toLowerCase() : "";
        String password = req.getPassword() != null ? req.getPassword() : "";
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Tên đăng nhập không hợp lệ!"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Mật khẩu phải >= 6 ký tự!"));
        }
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "error", "message", "Tài khoản đã tồn tại!"));
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(authService.hashPassword(password));
        user.setFullName(req.getFullName() != null && !req.getFullName().isBlank() ? req.getFullName().trim() : username);
        user.setPhone(req.getPhone() != null ? req.getPhone().trim() : "");
        user.setRole("ADMIN".equalsIgnoreCase(req.getRole()) ? "ADMIN" : "USER");
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now().format(FORMATTER));
        user = userRepository.save(user);

        return ResponseEntity.ok(Map.of("status", "ok", "user", user.toPublicMap()));
    }

    @PatchMapping("/users/{id}/status")
    public ResponseEntity<Map<String, Object>> toggleUserStatus(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        Optional<AppUser> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AppUser user = opt.get();
        AppUser caller = authService.authenticateRequest(auth).orElseThrow();
        if (Objects.equals(caller.getId(), user.getId()) && Boolean.FALSE.equals(body.get("active"))) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Không thể tự khóa tài khoản của chính mình!"));
        }
        user.setActive(Boolean.TRUE.equals(body.get("active")));
        user = userRepository.save(user);
        return ResponseEntity.ok(Map.of("status", "ok", "user", user.toPublicMap()));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        String newPass = body.get("newPassword");
        if (newPass == null || newPass.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Mật khẩu mới phải >= 6 ký tự!"));
        }
        Optional<AppUser> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AppUser user = opt.get();
        user.setPasswordHash(authService.hashPassword(newPass));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Đã đặt lại mật khẩu cho " + user.getUsername()));
    }

    @PostMapping("/vehicles")
    public ResponseEntity<Map<String, Object>> createVehicle(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AdminVehicleRequest req
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        String vId = req.getVehicleId() != null ? req.getVehicleId().trim().toUpperCase().replaceAll("\\s+", "") : "";
        if (vId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Thiếu mã định danh / biển số xe!"));
        }
        if (vehicleRepository.findByVehicleId(vId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "error", "message", "Mã xe " + vId + " đã tồn tại!"));
        }

        Vehicle v = new Vehicle();
        v.setVehicleId(vId);
        v.setVehicleName(req.getVehicleName() != null && !req.getVehicleName().isBlank() ? req.getVehicleName().trim() : vId);
        if (req.getOwnerId() != null) {
            v.setOwnerId(req.getOwnerId());
        } else {
            AppUser caller = authService.authenticateRequest(auth).orElseThrow();
            v.setOwnerId(caller.getId());
        }
        String code = (req.getActivationCode() != null && !req.getActivationCode().isBlank())
                ? req.getActivationCode().trim().toUpperCase()
                : generateActivationCode();
        v.setActivationCode(code);
        v.setRequireActivationCode(req.getRequireActivationCode() == null || req.getRequireActivationCode());

        if (req.getPlanMonths() != null && req.getPlanMonths() > 0) {
            v.setPlanExpiresAt(LocalDate.now().plusMonths(req.getPlanMonths()).toString());
        } else if (req.getPlanExpiresAt() != null && !req.getPlanExpiresAt().isBlank()) {
            v.setPlanExpiresAt(req.getPlanExpiresAt().trim());
        }
        v.setSpeed(0.0);
        v.setAccOn(false);
        if (req.getImei() != null && !req.getImei().isBlank()) {
            v.setImei(req.getImei().trim());
        }
        if (req.getCameraChannels() != null) {
            v.setCameraChannels(Math.max(1, Math.min(4, req.getCameraChannels())));
        }
        if (req.getStreamBaseUrl() != null && !req.getStreamBaseUrl().isBlank()) {
            v.setStreamBaseUrl(req.getStreamBaseUrl().trim());
        }
        if (req.getGoogleDriveFolderUrl() != null && !req.getGoogleDriveFolderUrl().isBlank()) {
            v.setGoogleDriveFolderUrl(req.getGoogleDriveFolderUrl().trim());
        }
        v.setMaxSpeedAlertKmh(req.getMaxSpeedAlertKmh() != null ? req.getMaxSpeedAlertKmh() : 90);
        v = vehicleRepository.save(v);

        return ResponseEntity.ok(Map.of("status", "ok", "vehicle", v));
    }

    @PatchMapping("/vehicles/{id}")
    public ResponseEntity<Map<String, Object>> updateVehicle(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long id,
            @RequestBody AdminVehicleRequest req
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        Optional<Vehicle> opt = vehicleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Vehicle v = opt.get();

        if (req.getVehicleName() != null) v.setVehicleName(req.getVehicleName().trim());
        if (req.getOwnerId() != null) v.setOwnerId(req.getOwnerId());
        if (req.getRequireActivationCode() != null) v.setRequireActivationCode(req.getRequireActivationCode());
        if (Boolean.TRUE.equals(req.getRegenerateCode())) {
            v.setActivationCode(generateActivationCode());
        } else if (req.getActivationCode() != null && !req.getActivationCode().isBlank()) {
            v.setActivationCode(req.getActivationCode().trim().toUpperCase());
        }

        if (req.getPlanMonths() != null && req.getPlanMonths() > 0) {
            LocalDate base = LocalDate.now();
            if (v.getPlanExpiresAt() != null && !v.getPlanExpiresAt().isBlank()) {
                try {
                    LocalDate cur = LocalDate.parse(v.getPlanExpiresAt().trim());
                    if (cur.isAfter(base)) base = cur;
                } catch (Exception ignored) {}
            }
            v.setPlanExpiresAt(base.plusMonths(req.getPlanMonths()).toString());
        } else if (req.getPlanExpiresAt() != null) {
            String exp = req.getPlanExpiresAt().trim();
            v.setPlanExpiresAt(exp.isEmpty() ? null : exp);
        }

        if (req.getMaxSpeedAlertKmh() != null && req.getMaxSpeedAlertKmh() > 0) {
            v.setMaxSpeedAlertKmh(req.getMaxSpeedAlertKmh());
        }
        if (req.getImei() != null) {
            String cleanImei = req.getImei().trim();
            v.setImei(cleanImei.isEmpty() ? null : cleanImei);
        }
        if (req.getCameraChannels() != null) {
            v.setCameraChannels(Math.max(1, Math.min(4, req.getCameraChannels())));
        }
        if (req.getStreamBaseUrl() != null) {
            String cleanStream = req.getStreamBaseUrl().trim();
            v.setStreamBaseUrl(cleanStream.isEmpty() ? null : cleanStream);
        }
        if (req.getGoogleDriveFolderUrl() != null) {
            String cleanDrive = req.getGoogleDriveFolderUrl().trim();
            v.setGoogleDriveFolderUrl(cleanDrive.isEmpty() ? null : cleanDrive);
        }

        v = vehicleRepository.save(v);
        return ResponseEntity.ok(Map.of("status", "ok", "vehicle", v));
    }

    @Transactional
    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<Map<String, Object>> deleteVehicle(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long id
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        Optional<Vehicle> opt = vehicleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Vehicle v = opt.get();
        historyRepository.deleteByVehicleId(v.getVehicleId());
        if (videoClipRepository != null) {
            videoClipRepository.deleteByVehicleId(v.getVehicleId());
        }
        if (vehicleLoanRepository != null) {
            vehicleLoanRepository.deleteByVehicleId(v.getVehicleId());
        }
        vehicleRepository.delete(v);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Đã xóa xe " + v.getVehicleId()));
    }

    @PostMapping("/settings/telegram")
    public ResponseEntity<Map<String, Object>> saveTelegramToken(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> body
    ) {
        ResponseEntity<Map<String, Object>> guardResp = forbidIfNotAdmin(auth);
        if (guardResp != null) return guardResp;

        String token = body.get("botToken") != null ? body.get("botToken").trim() : "";
        settingRepository.save(new AppSetting(TelegramNotificationService.KEY_TELEGRAM_BOT_TOKEN, token));
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "configured", !token.isEmpty(),
                "message", token.isEmpty() ? "Đã xóa Telegram Bot Token." : "Đã lưu Telegram Bot Token vào hệ thống!"
        ));
    }

    public static class AdminUserRequest {
        private String username;
        private String password;
        private String fullName;
        private String phone;
        private String role;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class AdminVehicleRequest {
        private String vehicleId;
        private String vehicleName;
        private Long ownerId;
        private String activationCode;
        private Boolean requireActivationCode;
        private Boolean regenerateCode;
        private Integer planMonths;
        private String planExpiresAt;
        private Integer maxSpeedAlertKmh;
        private String imei;
        private Integer cameraChannels;
        private String streamBaseUrl;
        private String googleDriveFolderUrl;

        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
        public String getVehicleName() { return vehicleName; }
        public void setVehicleName(String vehicleName) { this.vehicleName = vehicleName; }
        public Long getOwnerId() { return ownerId; }
        public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
        public String getActivationCode() { return activationCode; }
        public void setActivationCode(String activationCode) { this.activationCode = activationCode; }
        public Boolean getRequireActivationCode() { return requireActivationCode; }
        public void setRequireActivationCode(Boolean requireActivationCode) { this.requireActivationCode = requireActivationCode; }
        public Boolean getRegenerateCode() { return regenerateCode; }
        public void setRegenerateCode(Boolean regenerateCode) { this.regenerateCode = regenerateCode; }
        public Integer getPlanMonths() { return planMonths; }
        public void setPlanMonths(Integer planMonths) { this.planMonths = planMonths; }
        public String getPlanExpiresAt() { return planExpiresAt; }
        public void setPlanExpiresAt(String planExpiresAt) { this.planExpiresAt = planExpiresAt; }
        public Integer getMaxSpeedAlertKmh() { return maxSpeedAlertKmh; }
        public void setMaxSpeedAlertKmh(Integer maxSpeedAlertKmh) { this.maxSpeedAlertKmh = maxSpeedAlertKmh; }
        public String getImei() { return imei; }
        public void setImei(String imei) { this.imei = imei; }
        public Integer getCameraChannels() { return cameraChannels; }
        public void setCameraChannels(Integer cameraChannels) { this.cameraChannels = cameraChannels; }
        public String getStreamBaseUrl() { return streamBaseUrl; }
        public void setStreamBaseUrl(String streamBaseUrl) { this.streamBaseUrl = streamBaseUrl; }
        public String getGoogleDriveFolderUrl() { return googleDriveFolderUrl; }
        public void setGoogleDriveFolderUrl(String googleDriveFolderUrl) { this.googleDriveFolderUrl = googleDriveFolderUrl; }
    }
}

