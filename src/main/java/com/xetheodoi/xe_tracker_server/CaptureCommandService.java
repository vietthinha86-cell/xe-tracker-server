package com.xetheodoi.xe_tracker_server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Điều phối hàng đợi lệnh đẩy clip/ảnh ngắn tiết kiệm 4G SIM (chống spam bằng cooldown và tự hết hạn lệnh cũ).
 */
@Service
public class CaptureCommandService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long AUTO_CAPTURE_COOLDOWN_MS = 180_000L; // Tối đa 1 clip tự động / 3 phút / loại sự kiện
    private static final long COMMAND_TTL_MINUTES = 15L;

    private final CaptureCommandRepository commandRepository;
    private final VehicleLoanRepository loanRepository;
    private final Map<String, Long> autoCooldownMap = new ConcurrentHashMap<>();

    public CaptureCommandService(
            CaptureCommandRepository commandRepository,
            @Autowired(required = false) VehicleLoanRepository loanRepository
    ) {
        this.commandRepository = commandRepository;
        this.loanRepository = loanRepository;
    }

    public CaptureCommand enqueueOwnerRequest(
            Vehicle vehicle,
            int channel,
            String commandType,
            int durationSeconds,
            String quality
    ) {
        int safeCh = Math.max(1, Math.min(4, channel));
        String safeType = CaptureCommand.TYPE_SNAPSHOT.equalsIgnoreCase(commandType)
                ? CaptureCommand.TYPE_SNAPSHOT
                : CaptureCommand.TYPE_CLIP;
        int safeDuration = CaptureCommand.TYPE_SNAPSHOT.equals(safeType)
                ? 1
                : Math.max(5, Math.min(30, durationSeconds));
        String safeQuality = "MED_480P".equalsIgnoreCase(quality) ? "MED_480P" : "LOW_360P";

        return saveCommand(vehicle.getVehicleId(), safeCh, safeType, safeDuration, safeQuality, "OWNER_REQUEST");
    }

    public Optional<CaptureCommand> enqueueAutoCaptureIfAllowed(
            Vehicle vehicle,
            String triggerReason,
            int channel,
            String commandType,
            int durationSeconds
    ) {
        if (vehicle == null || vehicle.getVehicleId() == null) {
            return Optional.empty();
        }
        String vId = vehicle.getVehicleId();
        String reason = triggerReason != null ? triggerReason.trim().toUpperCase() : "EVENT";

        // Kiểm tra cấu hình của lượt mượn đang hoạt động (nếu có)
        Optional<VehicleLoan> activeLoan = findActiveLoan(vId);
        if (activeLoan.isPresent()) {
            VehicleLoan loan = activeLoan.get();
            if ("ACC_ON".equals(reason) && Boolean.FALSE.equals(loan.getAutoClipOnAccOn())) {
                return Optional.empty();
            }
            if (("OVERSPEED".equals(reason) || "GEOFENCE_BREACH".equals(reason))
                    && Boolean.FALSE.equals(loan.getAutoClipOnAlert())) {
                return Optional.empty();
            }
        }

        // Chống tốn 4G SIM: áp dụng cooldown cho cùng loại sự kiện trên cùng xe
        String cooldownKey = vId + ":" + reason;
        long nowMs = System.currentTimeMillis();
        Long lastMs = autoCooldownMap.get(cooldownKey);
        if (lastMs != null && (nowMs - lastMs) < AUTO_CAPTURE_COOLDOWN_MS) {
            return Optional.empty();
        }

        // Không xếp chồng nếu đã có lệnh PENDING cùng sự kiện đang chờ thiết bị thực hiện
        List<CaptureCommand> pending = getPendingCommands(vId);
        for (CaptureCommand cmd : pending) {
            if (reason.equalsIgnoreCase(cmd.getTriggerReason()) && Objects.equals(cmd.getChannel(), channel)) {
                return Optional.empty();
            }
        }

        autoCooldownMap.put(cooldownKey, nowMs);
        int safeCh = Math.max(1, Math.min(4, channel));
        String safeType = CaptureCommand.TYPE_SNAPSHOT.equalsIgnoreCase(commandType)
                ? CaptureCommand.TYPE_SNAPSHOT
                : CaptureCommand.TYPE_CLIP;
        int safeDuration = CaptureCommand.TYPE_SNAPSHOT.equals(safeType)
                ? 1
                : Math.max(5, Math.min(30, durationSeconds));

        return Optional.of(saveCommand(vId, safeCh, safeType, safeDuration, "LOW_360P", reason));
    }

    public List<CaptureCommand> getPendingCommands(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return List.of();
        }
        String cleanId = vehicleId.trim().toUpperCase();
        List<CaptureCommand> pending = commandRepository.findByVehicleIdAndStatusOrderByIdAsc(cleanId, CaptureCommand.STATUS_PENDING);
        LocalDateTime now = LocalDateTime.now();
        List<CaptureCommand> active = new ArrayList<>();
        for (CaptureCommand cmd : pending) {
            if (isExpired(cmd, now)) {
                cmd.setStatus(CaptureCommand.STATUS_EXPIRED);
                commandRepository.save(cmd);
            } else {
                active.add(cmd);
            }
        }
        return active;
    }

    public List<CaptureCommand> getRecentCommands(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return List.of();
        }
        String cleanId = vehicleId.trim().toUpperCase();
        // Đảm bảo lệnh quá hạn được cập nhật trạng thái trước khi trả về UI
        getPendingCommands(cleanId);
        return commandRepository.findTop15ByVehicleIdOrderByIdDesc(cleanId);
    }

    public void markCompleted(Long commandId, String vehicleId, Long clipId) {
        String now = LocalDateTime.now().format(FORMATTER);
        if (commandId != null) {
            Optional<CaptureCommand> opt = commandRepository.findById(commandId);
            if (opt.isPresent() && vehicleId.equalsIgnoreCase(opt.get().getVehicleId())) {
                CaptureCommand cmd = opt.get();
                cmd.setStatus(CaptureCommand.STATUS_COMPLETED);
                cmd.setCompletedAt(now);
                cmd.setResultClipId(clipId);
                commandRepository.save(cmd);
                return;
            }
        }
        List<CaptureCommand> pending = commandRepository.findByVehicleIdAndStatusOrderByIdAsc(
                vehicleId.trim().toUpperCase(), CaptureCommand.STATUS_PENDING);
        if (!pending.isEmpty()) {
            CaptureCommand first = pending.get(0);
            first.setStatus(CaptureCommand.STATUS_COMPLETED);
            first.setCompletedAt(now);
            first.setResultClipId(clipId);
            commandRepository.save(first);
        }
    }

    private CaptureCommand saveCommand(
            String vehicleId,
            int channel,
            String commandType,
            int durationSeconds,
            String quality,
            String triggerReason
    ) {
        CaptureCommand cmd = new CaptureCommand();
        cmd.setVehicleId(vehicleId);
        cmd.setChannel(channel);
        cmd.setCommandType(commandType);
        cmd.setDurationSeconds(durationSeconds);
        cmd.setQuality(quality);
        cmd.setTriggerReason(triggerReason);
        cmd.setStatus(CaptureCommand.STATUS_PENDING);
        cmd.setRequestedAt(LocalDateTime.now().format(FORMATTER));
        findActiveLoan(vehicleId).ifPresent(loan -> {
            cmd.setLoanId(loan.getId());
            cmd.setBorrowerName(loan.getBorrowerName());
        });
        return commandRepository.save(cmd);
    }

    private Optional<VehicleLoan> findActiveLoan(String vehicleId) {
        if (loanRepository == null) {
            return Optional.empty();
        }
        return loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc(vehicleId, VehicleLoan.STATUS_ACTIVE);
    }

    private boolean isExpired(CaptureCommand cmd, LocalDateTime now) {
        if (cmd.getRequestedAt() == null || cmd.getRequestedAt().isBlank()) {
            return false;
        }
        try {
            LocalDateTime req = LocalDateTime.parse(cmd.getRequestedAt().trim(), FORMATTER);
            return Duration.between(req, now).toMinutes() >= COMMAND_TTL_MINUTES;
        } catch (Exception ignored) {
            return false;
        }
    }
}
