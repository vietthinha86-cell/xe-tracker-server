package com.xetheodoi.xe_tracker_server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class AuthController {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._@-]{3,40}$");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppUserRepository userRepository;
    private final AuthService authService;
    private final TelegramNotificationService telegramService;

    public AuthController(
            AppUserRepository userRepository,
            AuthService authService,
            TelegramNotificationService telegramService
    ) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.telegramService = telegramService;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthRequest req) {
        Map<String, Object> resp = new HashMap<>();
        String username = req.getUsername() != null ? req.getUsername().trim().toLowerCase() : "";
        String password = req.getPassword() != null ? req.getPassword() : "";

        Optional<AppUser> userOpt = userRepository.findByUsernameIgnoreCase(username);
        if (userOpt.isEmpty() || !authService.verifyPassword(password, userOpt.get().getPasswordHash())) {
            resp.put("status", "error");
            resp.put("message", "Sai tên đăng nhập hoặc mật khẩu!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(resp);
        }

        AppUser user = userOpt.get();
        if (!user.isAccountActive()) {
            resp.put("status", "error");
            resp.put("message", "Tài khoản này đang bị tạm khóa. Vui lòng liên hệ quản trị viên!");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(resp);
        }

        resp.put("status", "ok");
        resp.put("token", authService.generateToken(user));
        resp.put("user", user.toPublicMap());
        resp.put("telegramBotConfigured", telegramService.isConfigured());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody AuthRequest req) {
        Map<String, Object> resp = new HashMap<>();
        String username = req.getUsername() != null ? req.getUsername().trim().toLowerCase() : "";
        String password = req.getPassword() != null ? req.getPassword() : "";
        String fullName = req.getFullName() != null ? req.getFullName().trim() : "";
        String phone = req.getPhone() != null ? req.getPhone().trim() : "";

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            resp.put("status", "error");
            resp.put("message", "Tên đăng nhập 3-40 ký tự (chữ, số, ., _, @, -), không dấu cách!");
            return ResponseEntity.badRequest().body(resp);
        }
        if (password.length() < 6) {
            resp.put("status", "error");
            resp.put("message", "Mật khẩu phải từ 6 ký tự trở lên!");
            return ResponseEntity.badRequest().body(resp);
        }
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            resp.put("status", "error");
            resp.put("message", "Tên đăng nhập hoặc SĐT này đã tồn tại!");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(authService.hashPassword(password));
        user.setFullName(fullName.isEmpty() ? username : fullName);
        user.setPhone(phone);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now().format(FORMATTER));
        user = userRepository.save(user);

        resp.put("status", "ok");
        resp.put("token", authService.generateToken(user));
        resp.put("user", user.toPublicMap());
        resp.put("telegramBotConfigured", telegramService.isConfigured());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<AppUser> userOpt = authService.authenticateRequest(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập hoặc phiên đã hết hạn"));
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("user", userOpt.get().toPublicMap());
        resp.put("telegramBotConfigured", telegramService.isConfigured());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auth/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody ProfileRequest req
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Vui lòng đăng nhập lại!"));
        }
        AppUser user = userOpt.get();
        Map<String, Object> resp = new HashMap<>();

        if (req.getFullName() != null && !req.getFullName().trim().isEmpty()) {
            user.setFullName(req.getFullName().trim());
        }
        if (req.getPhone() != null) {
            user.setPhone(req.getPhone().trim());
        }
        if (req.getTelegramChatId() != null) {
            user.setTelegramChatId(req.getTelegramChatId().trim());
        }

        if (req.getNewPassword() != null && !req.getNewPassword().isEmpty()) {
            if (req.getNewPassword().length() < 6) {
                resp.put("status", "error");
                resp.put("message", "Mật khẩu mới phải từ 6 ký tự trở lên!");
                return ResponseEntity.badRequest().body(resp);
            }
            if (!authService.verifyPassword(req.getCurrentPassword(), user.getPasswordHash())) {
                resp.put("status", "error");
                resp.put("message", "Mật khẩu hiện tại không đúng!");
                return ResponseEntity.badRequest().body(resp);
            }
            user.setPasswordHash(authService.hashPassword(req.getNewPassword()));
        }

        user = userRepository.save(user);
        resp.put("status", "ok");
        resp.put("user", user.toPublicMap());
        resp.put("message", "Đã lưu thông tin tài khoản!");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/telegram/test")
    public ResponseEntity<Map<String, Object>> testTelegram(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody(required = false) Map<String, String> body
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "Vui lòng đăng nhập lại!"));
        }
        AppUser user = userOpt.get();
        String chatId = (body != null && body.get("chatId") != null && !body.get("chatId").trim().isEmpty())
                ? body.get("chatId").trim()
                : user.getTelegramChatId();

        Map<String, Object> res = telegramService.sendTestMessage(
                chatId,
                user.getFullName() != null ? user.getFullName() : user.getUsername()
        );
        return ResponseEntity.ok(res);
    }

    public static class AuthRequest {
        private String username;
        private String password;
        private String fullName;
        private String phone;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class ProfileRequest {
        private String fullName;
        private String phone;
        private String telegramChatId;
        private String currentPassword;
        private String newPassword;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getTelegramChatId() { return telegramChatId; }
        public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }
}

