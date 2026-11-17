package com.xetheodoi.xe_tracker_server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tự động tạo tài khoản Admin mặc định và gắn xe cũ (XE001) khi nâng cấp hệ thống lên đa người dùng.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppUserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthService authService;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    public DataInitializer(
            AppUserRepository userRepository,
            VehicleRepository vehicleRepository,
            AuthService authService
    ) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        AppUser admin = userRepository.findByUsernameIgnoreCase(adminUsername).orElse(null);
        if (admin == null && !userRepository.existsByRole("ADMIN")) {
            admin = new AppUser();
            admin.setUsername(adminUsername.trim().toLowerCase());
            admin.setPasswordHash(authService.hashPassword(adminPassword));
            admin.setFullName("Quản Trị Viên Hệ Thống");
            admin.setPhone("");
            admin.setRole("ADMIN");
            admin.setActive(true);
            admin.setCreatedAt(LocalDateTime.now().format(FORMATTER));
            admin = userRepository.save(admin);
        }

        if (admin != null) {
            List<Vehicle> allVehicles = vehicleRepository.findAll();
            for (Vehicle v : allVehicles) {
                boolean changed = false;
                if (v.getOwnerId() == null) {
                    v.setOwnerId(admin.getId());
                    changed = true;
                }
                if (v.getActivationCode() == null || v.getActivationCode().isBlank()) {
                    v.setActivationCode("CT-" + v.getVehicleId());
                    changed = true;
                }
                if (v.getVehicleName() == null || v.getVehicleName().isBlank()) {
                    v.setVehicleName("Xe " + v.getVehicleId());
                    changed = true;
                }
                if (v.getRequireActivationCode() == null) {
                    // Giữ tương thích ngược cho xe test cũ (XE001) cho tới khi bật yêu cầu mã kích hoạt
                    v.setRequireActivationCode(false);
                    changed = true;
                }
                if (changed) {
                    vehicleRepository.save(v);
                }
            }
        }
    }
}

