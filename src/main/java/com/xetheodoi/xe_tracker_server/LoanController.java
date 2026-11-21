package com.xetheodoi.xe_tracker_server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * API quản lý lượt cho mượn / giao xe của chủ xe.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/loans")
public class LoanController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VehicleLoanRepository loanRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthService authService;

    public LoanController(
            VehicleLoanRepository loanRepository,
            VehicleRepository vehicleRepository,
            AuthService authService
    ) {
        this.loanRepository = loanRepository;
        this.vehicleRepository = vehicleRepository;
        this.authService = authService;
    }

    private List<Vehicle> getAccessibleVehicles(AppUser user) {
        return user.isAdmin()
                ? vehicleRepository.findAllByOrderByIdAsc()
                : vehicleRepository.findByOwnerIdOrderByIdAsc(user.getId());
    }

    private Optional<Vehicle> findAuthorizedVehicle(String vehicleId, AppUser user) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return Optional.empty();
        }
        String cleanId = vehicleId.trim().toUpperCase();
        Optional<Vehicle> opt = vehicleRepository.findByVehicleId(cleanId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Vehicle v = opt.get();
        if (!user.isAdmin() && !Objects.equals(v.getOwnerId(), user.getId())) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listLoans(
            @RequestParam(value = "vehicleId", required = false) String vehicleId,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        AppUser user = userOpt.get();

        List<VehicleLoan> raw;
        if (vehicleId != null && !vehicleId.isBlank()) {
            Optional<Vehicle> vOpt = findAuthorizedVehicle(vehicleId, user);
            if (vOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("status", "error", "message", "Không có quyền xem lượt mượn của xe này!"));
            }
            raw = loanRepository.findByVehicleIdOrderByIdDesc(vOpt.get().getVehicleId());
        } else {
            List<Vehicle> vehicles = getAccessibleVehicles(user);
            if (vehicles.isEmpty()) {
                raw = List.of();
            } else {
                List<String> ids = vehicles.stream().map(Vehicle::getVehicleId).toList();
                raw = loanRepository.findByVehicleIdInOrderByIdDesc(ids);
            }
        }

        String statusFilter = status != null ? status.trim().toUpperCase() : "";
        List<VehicleLoan> filtered = new ArrayList<>();
        Map<String, VehicleLoan> activeByVehicle = new LinkedHashMap<>();
        for (VehicleLoan loan : raw) {
            if (VehicleLoan.STATUS_ACTIVE.equalsIgnoreCase(loan.getStatus())) {
                activeByVehicle.putIfAbsent(loan.getVehicleId(), loan);
            }
            if (!statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(loan.getStatus())) {
                continue;
            }
            filtered.add(loan);
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "loans", filtered,
                "activeByVehicle", activeByVehicle
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> startLoan(
            @RequestBody LoanCreateRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        if (req.getVehicleId() == null || req.getVehicleId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Vui lòng chọn xe cho mượn!"));
        }
        if (req.getBorrowerName() == null || req.getBorrowerName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Vui lòng nhập tên người mượn xe!"));
        }

        Optional<Vehicle> vOpt = findAuthorizedVehicle(req.getVehicleId(), userOpt.get());
        if (vOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Không tìm thấy hoặc không có quyền giao xe này!"));
        }
        Vehicle vehicle = vOpt.get();
        String now = LocalDateTime.now().format(FORMATTER);

        // Tự động đóng phiên mượn cũ (nếu còn đang mở) để mỗi xe chỉ có 1 người mượn hiện tại
        loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc(vehicle.getVehicleId(), VehicleLoan.STATUS_ACTIVE)
                .ifPresent(prev -> {
                    prev.setStatus(VehicleLoan.STATUS_RETURNED);
                    prev.setReturnedAt(now);
                    loanRepository.save(prev);
                });

        VehicleLoan loan = new VehicleLoan();
        loan.setVehicleId(vehicle.getVehicleId());
        loan.setBorrowerName(req.getBorrowerName().trim());
        loan.setBorrowerPhone(req.getBorrowerPhone() != null ? req.getBorrowerPhone().trim() : "");
        loan.setBorrowerNote(req.getBorrowerNote() != null ? req.getBorrowerNote().trim() : "");
        loan.setStartedAt(now);
        loan.setExpectedReturnAt(req.getExpectedReturnAt() != null ? req.getExpectedReturnAt().trim() : "");
        loan.setStatus(VehicleLoan.STATUS_ACTIVE);
        loan.setAutoClipOnAccOn(req.getAutoClipOnAccOn() == null || req.getAutoClipOnAccOn());
        loan.setAutoClipOnAlert(req.getAutoClipOnAlert() == null || req.getAutoClipOnAlert());

        if (req.getMaxSpeedAlertKmh() != null && req.getMaxSpeedAlertKmh() >= 30 && req.getMaxSpeedAlertKmh() <= 200) {
            loan.setMaxSpeedAlertKmh(req.getMaxSpeedAlertKmh());
            vehicle.setMaxSpeedAlertKmh(req.getMaxSpeedAlertKmh());
            vehicleRepository.save(vehicle);
        } else {
            loan.setMaxSpeedAlertKmh(vehicle.getMaxSpeedAlertKmh() != null ? vehicle.getMaxSpeedAlertKmh() : 90);
        }

        loan = loanRepository.save(loan);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "loan", loan,
                "message", "Đã bắt đầu lượt cho mượn xe " + vehicle.getVehicleId() + " cho " + loan.getBorrowerName() + "!"
        ));
    }

    @PostMapping("/{loanId}/return")
    public ResponseEntity<Map<String, Object>> returnLoan(
            @PathVariable Long loanId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        Optional<VehicleLoan> opt = loanRepository.findById(loanId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "Không tìm thấy lượt mượn này!"));
        }
        VehicleLoan loan = opt.get();
        if (findAuthorizedVehicle(loan.getVehicleId(), userOpt.get()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Không có quyền kết thúc lượt mượn này!"));
        }

        loan.setStatus(VehicleLoan.STATUS_RETURNED);
        loan.setReturnedAt(LocalDateTime.now().format(FORMATTER));
        if (body != null && body.get("returnNote") != null && !body.get("returnNote").isBlank()) {
            String prev = loan.getBorrowerNote() != null ? loan.getBorrowerNote().trim() : "";
            String extra = "Trả xe: " + body.get("returnNote").trim();
            loan.setBorrowerNote(prev.isEmpty() ? extra : (prev + " | " + extra));
        }
        loan = loanRepository.save(loan);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "loan", loan,
                "message", "Đã xác nhận nhận lại xe " + loan.getVehicleId() + "!"
        ));
    }

    public static class LoanCreateRequest {
        private String vehicleId;
        private String borrowerName;
        private String borrowerPhone;
        private String borrowerNote;
        private String expectedReturnAt;
        private Integer maxSpeedAlertKmh;
        private Boolean autoClipOnAccOn;
        private Boolean autoClipOnAlert;

        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
        public String getBorrowerName() { return borrowerName; }
        public void setBorrowerName(String borrowerName) { this.borrowerName = borrowerName; }
        public String getBorrowerPhone() { return borrowerPhone; }
        public void setBorrowerPhone(String borrowerPhone) { this.borrowerPhone = borrowerPhone; }
        public String getBorrowerNote() { return borrowerNote; }
        public void setBorrowerNote(String borrowerNote) { this.borrowerNote = borrowerNote; }
        public String getExpectedReturnAt() { return expectedReturnAt; }
        public void setExpectedReturnAt(String expectedReturnAt) { this.expectedReturnAt = expectedReturnAt; }
        public Integer getMaxSpeedAlertKmh() { return maxSpeedAlertKmh; }
        public void setMaxSpeedAlertKmh(Integer maxSpeedAlertKmh) { this.maxSpeedAlertKmh = maxSpeedAlertKmh; }
        public Boolean getAutoClipOnAccOn() { return autoClipOnAccOn; }
        public void setAutoClipOnAccOn(Boolean autoClipOnAccOn) { this.autoClipOnAccOn = autoClipOnAccOn; }
        public Boolean getAutoClipOnAlert() { return autoClipOnAlert; }
        public void setAutoClipOnAlert(Boolean autoClipOnAlert) { this.autoClipOnAlert = autoClipOnAlert; }
    }
}
